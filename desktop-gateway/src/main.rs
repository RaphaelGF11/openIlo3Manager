//! A local reverse proxy for HP iLO 3 management interfaces.
//!
//! An iLO 3 serves its web interface over TLS 1.0 with 3DES, behind a self-signed certificate.
//! Every current browser refuses all of that, so the interface became unreachable without keeping
//! an obsolete browser around. This proxy speaks the old protocol on one side and plain HTTP on
//! the other, and the browser is none the wiser.
//!
//! Point it at a device by putting the address and port in the path:
//!
//! ```text
//! http://127.0.0.1:8080/192.168.1.230/443/
//! ```

mod http;
mod pool;
mod tls;

use http::{Request, Response};
use std::io::{BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;

const DEFAULT_BIND: &str = "127.0.0.1:8080";
const TARGET_COOKIE: &str = "ilo3_target";

struct Target {
    host: String,
    port: u16,
    /// The path to ask the iLO for, with the routing prefix removed.
    path: String,
    /// Set when this request carried the prefix, so the browser can be told to remember it.
    from_prefix: bool,
}

fn main() {
    let bind = std::env::args().nth(1).unwrap_or_else(|| DEFAULT_BIND.to_string());
    let listener = match TcpListener::bind(&bind) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("Impossible d'écouter sur {bind} : {e}");
            std::process::exit(1);
        }
    };

    println!("Passerelle iLO 3 à l'écoute sur http://{bind}");
    println!("Ouvrez http://{bind}/<adresse-ilo>/<port>/  — par exemple http://{bind}/192.168.1.230/443/");

    for incoming in listener.incoming() {
        match incoming {
            Ok(stream) => {
                thread::spawn(move || {
                    if let Err(e) = serve(stream) {
                        // A browser closing a tab aborts connections routinely; it is not an error
                        // worth reporting, so only the message is shown, never a backtrace.
                        if e.kind() != std::io::ErrorKind::UnexpectedEof {
                            eprintln!("connexion terminée : {e}");
                        }
                    }
                });
            }
            Err(e) => eprintln!("connexion refusée : {e}"),
        }
    }
}

fn serve(stream: TcpStream) -> std::io::Result<()> {
    stream.set_nodelay(true)?;
    let mut out = stream.try_clone()?;
    let mut reader = BufReader::new(stream);

    // One browser connection carries many requests; each is resolved independently so a page and
    // its assets can be served without a new local connection each time.
    while let Some(request) = http::read_request(&mut reader)? {
        let Some(target) = resolve(&request) else {
            http::simple_response(&mut out, "400 Bad Request", USAGE)?;
            continue;
        };

        match forward(&request, &target) {
            Ok(response) => {
                let mut extra = Vec::new();
                if target.from_prefix {
                    // The iLO's pages reference absolute paths such as /json/login_session, which
                    // would escape the routing prefix. Remembering the target in a cookie lets
                    // those requests resolve without rewriting any of its HTML or scripts.
                    extra.push((
                        "Set-Cookie".to_string(),
                        format!("{TARGET_COOKIE}={}:{}; Path=/", target.host, target.port),
                    ));
                }
                http::write_response(&mut out, &response, &extra)?;
            }
            Err(e) => {
                let message = format!(
                    "<h1>iLO injoignable</h1><p>{}:{} — {}</p>",
                    target.host, target.port, e
                );
                http::simple_response(&mut out, "502 Bad Gateway", &message)?;
            }
        }
    }
    out.flush()
}

/// Works out which device a request is for.
///
/// Three sources, in order: the routing prefix, the cookie left by an earlier prefixed request,
/// and the Referer of the page that issued it. The last two exist because the iLO's own pages ask
/// for absolute paths, which carry no prefix.
fn resolve(request: &Request) -> Option<Target> {
    if let Some(target) = from_prefix(&request.path) {
        return Some(target);
    }
    let remembered = request
        .header("cookie")
        .and_then(|cookies| {
            cookies.split(';').find_map(|c| {
                let (name, value) = c.trim().split_once('=')?;
                (name == TARGET_COOKIE).then(|| value.to_string())
            })
        })
        .or_else(|| {
            let referer = request.header("referer")?;
            let after_authority = referer.split_once("://")?.1.split_once('/')?.1;
            let target = from_prefix(&format!("/{after_authority}"))?;
            Some(format!("{}:{}", target.host, target.port))
        })?;

    let (host, port) = remembered.rsplit_once(':')?;
    Some(Target {
        host: host.to_string(),
        port: port.parse().ok()?,
        path: request.path.clone(),
        from_prefix: false,
    })
}

fn from_prefix(path: &str) -> Option<Target> {
    let rest = path.strip_prefix('/')?;
    let (host, rest) = rest.split_once('/')?;
    let (port, rest) = rest.split_once('/').unwrap_or((rest, ""));
    if host.is_empty() {
        return None;
    }
    let port: u16 = port.parse().ok()?;
    Some(Target {
        host: host.to_string(),
        port,
        path: format!("/{rest}"),
        from_prefix: true,
    })
}

fn forward(request: &Request, target: &Target) -> std::io::Result<Response> {
    // A connection taken from the pool may have been closed by the iLO while idle, which only shows
    // up on the write. One retry on a fresh connection covers it; a second failure is real.
    match attempt(request, target) {
        Ok(response) => Ok(response),
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => attempt(request, target),
        Err(e) => Err(e),
    }
}

fn attempt(request: &Request, target: &Target) -> std::io::Result<Response> {
    let lease = pool::acquire(&target.host, target.port)?;
    let mut stream = lease.stream;

    let head = build_request(request, target);
    stream.write_all(head.as_bytes())?;
    if !request.body.is_empty() {
        stream.write_all(&request.body)?;
    }
    stream.flush()?;

    let mut reader = BufReader::new(stream);
    let response = http::read_response(&mut reader)?;
    if response.reusable {
        pool::release(&target.host, target.port, reader.into_inner());
    }
    Ok(response)
}

fn build_request(request: &Request, target: &Target) -> String {
    // iLO 3's web server cannot parse a request body when the request line carries a query string —
    // *any* query string, even a bare "?". It answers "Malformed object, expected '{' at start of
    // object" and the setting silently fails to apply. Verified against a real unit for "?",
    // "?null", "?_=123" and "?x=1". Dropping the query on a request that has a body is harmless:
    // the interface only ever uses it for cache-busting.
    let query = match (&request.query, request.body.is_empty()) {
        (Some(q), true) if !q.is_empty() => format!("?{q}"),
        _ => String::new(),
    };

    let mut head = format!("{} {}{} HTTP/1.1\r\n", request.method, target.path, query);
    head.push_str(&format!("Host: {}:{}\r\n", target.host, target.port));

    for (name, value) in &request.headers {
        let lower = name.to_ascii_lowercase();
        // Host is rewritten above; the framing and transport headers belong to the local hop; and
        // the routing cookie is ours, not the iLO's.
        if matches!(
            lower.as_str(),
            "host" | "connection" | "keep-alive" | "proxy-connection" | "upgrade"
                | "transfer-encoding" | "content-length" | "accept-encoding"
        ) {
            continue;
        }
        if lower == "cookie" {
            let kept: Vec<&str> = value
                .split(';')
                .map(str::trim)
                .filter(|c| !c.starts_with(&format!("{TARGET_COOKIE}=")))
                .collect();
            if kept.is_empty() {
                continue;
            }
            head.push_str(&format!("Cookie: {}\r\n", kept.join("; ")));
            continue;
        }
        head.push_str(&format!("{name}: {value}\r\n"));
    }

    if !request.body.is_empty() {
        head.push_str(&format!("Content-Length: {}\r\n", request.body.len()));
    }
    // Identity only: an iLO 3 handles compression poorly and the saving is irrelevant on a LAN.
    head.push_str("Accept-Encoding: identity\r\n");
    head.push_str("Connection: keep-alive\r\n\r\n");
    head
}

const USAGE: &str = "<h1>Passerelle iLO 3</h1>\
<p>Indiquez l'adresse et le port de l'iLO dans le chemin :</p>\
<pre>http://127.0.0.1:8080/192.168.1.230/443/</pre>";
