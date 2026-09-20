//! Keeps upstream TLS connections open between requests.
//!
//! A TLS 1.0 handshake against an iLO 3's management processor takes the best part of a second.
//! A single page pulls dozens of scripts and images, so without reuse the interface is unusable —
//! this is the difference between "slow" and "not worth opening".

use crate::tls;
use openssl::ssl::SslStream;
use std::collections::HashMap;
use std::io;
use std::net::TcpStream;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

/// Long enough to cover a page load, short enough that the iLO closes nothing under us first.
const MAX_IDLE: Duration = Duration::from_secs(5);
const MAX_PER_HOST: usize = 6;

struct Idle {
    stream: SslStream<TcpStream>,
    since: Instant,
}

fn pool() -> &'static Mutex<HashMap<(String, u16), Vec<Idle>>> {
    static POOL: OnceLock<Mutex<HashMap<(String, u16), Vec<Idle>>>> = OnceLock::new();
    POOL.get_or_init(|| Mutex::new(HashMap::new()))
}

/// An existing idle connection, or a fresh one.
///
/// The caller is told which it got: a pooled connection may have been closed by the other end
/// without anything being sent, in which case the request has to be retried on a new one.
pub struct Lease {
    pub stream: SslStream<TcpStream>,
    pub from_pool: bool,
}

pub fn acquire(host: &str, port: u16) -> io::Result<Lease> {
    let key = (host.to_string(), port);
    if let Ok(mut map) = pool().lock() {
        if let Some(entries) = map.get_mut(&key) {
            entries.retain(|idle| idle.since.elapsed() < MAX_IDLE);
            if let Some(idle) = entries.pop() {
                return Ok(Lease { stream: idle.stream, from_pool: true });
            }
        }
    }
    let _ = tls::connect_timeout();
    Ok(Lease { stream: tls::connect(host, port)?, from_pool: false })
}

pub fn release(host: &str, port: u16, stream: SslStream<TcpStream>) {
    if let Ok(mut map) = pool().lock() {
        let entries = map.entry((host.to_string(), port)).or_default();
        entries.retain(|idle| idle.since.elapsed() < MAX_IDLE);
        if entries.len() < MAX_PER_HOST {
            entries.push(Idle { stream, since: Instant::now() });
        }
    }
}
