//! Just enough HTTP to relay a request and a response verbatim.
//!
//! A full HTTP library would normalise headers, re-encode bodies and rewrite framing. This proxy
//! must do as little of that as possible: an iLO 3's web server is idiosyncratic enough that the
//! safest thing is to pass through what it sends.

use std::io::{self, BufRead, BufReader, Read, Write};

pub struct Request {
    pub method: String,
    pub path: String,
    pub query: Option<String>,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }
}

pub fn read_request<R: Read>(reader: &mut BufReader<R>) -> io::Result<Option<Request>> {
    let mut line = String::new();
    if reader.read_line(&mut line)? == 0 {
        return Ok(None); // Client closed the connection.
    }
    let mut parts = line.trim_end().split_whitespace();
    let method = parts.next().unwrap_or_default().to_string();
    let target = parts.next().unwrap_or_default().to_string();
    if method.is_empty() || target.is_empty() {
        return Ok(None);
    }

    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p.to_string(), Some(q.to_string())),
        None => (target, None),
    };

    let headers = read_headers(reader)?;
    let length = headers
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case("content-length"))
        .and_then(|(_, v)| v.trim().parse::<usize>().ok())
        .unwrap_or(0);

    let mut body = vec![0u8; length];
    if length > 0 {
        reader.read_exact(&mut body)?;
    }

    Ok(Some(Request { method, path, query, headers, body }))
}

fn read_headers<R: Read>(reader: &mut BufReader<R>) -> io::Result<Vec<(String, String)>> {
    let mut headers = Vec::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            break;
        }
        let line = line.trim_end_matches(['\r', '\n']);
        if line.is_empty() {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            headers.push((name.trim().to_string(), value.trim().to_string()));
        }
    }
    Ok(headers)
}

pub struct Response {
    pub status_line: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    /// Whether the upstream connection can be handed back to the pool afterwards.
    pub reusable: bool,
}

/// Reads one response, resolving its framing so the body can be re-sent with a known length.
///
/// Chunked bodies are decoded rather than forwarded: the browser gets a plain Content-Length, which
/// keeps the local side simple and lets the upstream connection be reused.
pub fn read_response<R: Read>(reader: &mut BufReader<R>) -> io::Result<Response> {
    let mut status_line = String::new();
    if reader.read_line(&mut status_line)? == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "réponse vide de l'iLO"));
    }
    let status_line = status_line.trim_end().to_string();
    let headers = read_headers(reader)?;

    let find = |name: &str| {
        headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.trim().to_ascii_lowercase())
    };

    let head_only = status_line.contains(" 204") || status_line.contains(" 304");
    let chunked = find("transfer-encoding").is_some_and(|v| v.contains("chunked"));
    let length = find("content-length").and_then(|v| v.parse::<usize>().ok());
    let closes = find("connection").is_some_and(|v| v.contains("close"));

    let mut body = Vec::new();
    let mut reusable = !closes;
    if head_only {
        // Nothing to read.
    } else if chunked {
        read_chunked(reader, &mut body)?;
    } else if let Some(n) = length {
        body.resize(n, 0);
        reader.read_exact(&mut body)?;
    } else {
        // No framing at all: the body ends when the connection does, so it cannot be reused.
        reader.read_to_end(&mut body)?;
        reusable = false;
    }

    Ok(Response { status_line, headers, body, reusable })
}

fn read_chunked<R: Read>(reader: &mut BufReader<R>, body: &mut Vec<u8>) -> io::Result<()> {
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "morceau tronqué"));
        }
        let size_text = line.trim().split(';').next().unwrap_or("").to_string();
        let size = usize::from_str_radix(&size_text, 16)
            .map_err(|_| io::Error::other(format!("taille de morceau illisible : {size_text:?}")))?;
        if size == 0 {
            // Trailers, then the final blank line.
            loop {
                let mut trailer = String::new();
                if reader.read_line(&mut trailer)? == 0 {
                    break;
                }
                if trailer.trim().is_empty() {
                    break;
                }
            }
            return Ok(());
        }
        let start = body.len();
        body.resize(start + size, 0);
        reader.read_exact(&mut body[start..])?;
        let mut crlf = [0u8; 2];
        reader.read_exact(&mut crlf)?;
    }
}

/// Writes a response to the browser, replacing the framing headers with a plain length.
pub fn write_response<W: Write>(out: &mut W, response: &Response, extra: &[(String, String)]) -> io::Result<()> {
    let mut head = String::new();
    head.push_str(&response.status_line);
    head.push_str("\r\n");
    for (name, value) in &response.headers {
        let lower = name.to_ascii_lowercase();
        if lower == "transfer-encoding" || lower == "content-length" || lower == "connection" {
            continue;
        }
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    for (name, value) in extra {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str(&format!("Content-Length: {}\r\n", response.body.len()));
    head.push_str("Connection: keep-alive\r\n\r\n");

    out.write_all(head.as_bytes())?;
    out.write_all(&response.body)?;
    out.flush()
}

pub fn simple_response<W: Write>(out: &mut W, status: &str, message: &str) -> io::Result<()> {
    let body = message.as_bytes();
    let head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\n\
         Content-Length: {}\r\nConnection: keep-alive\r\n\r\n",
        body.len()
    );
    out.write_all(head.as_bytes())?;
    out.write_all(body)?;
    out.flush()
}
