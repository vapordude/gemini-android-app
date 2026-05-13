//! Hand-rolled HTTP/1.1 server bound to 127.0.0.1, kernel-assigned port.
//! No third-party HTTP framework. Implements the contract in
//! `native/openapi.yaml`. Used by both the Android app (via JNI start/stop)
//! and by daily devops tools (CLIs, editor plugins).
//!
//! v0 ships: blocking thread-per-connection accept loop, minimal HTTP/1.1
//! parser (request line + headers, no chunked, no pipelining), JSON
//! routes. NDJSON streaming routes return a single document for now;
//! true streaming lands when the agent loop is wired through.

#![deny(unsafe_op_in_unsafe_fn)]

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;

pub trait Routes: Send + Sync + 'static {
    fn info_json(&self) -> String;
    fn models_json(&self) -> String;
    fn agent_run(&self, _body: &str) -> String {
        r#"{"events":[{"kind":"done"}]}"#.to_string()
    }
    fn traces_json(&self, _tail: usize) -> String {
        "[]".to_string()
    }
}

pub struct Server<R: Routes> {
    listener: TcpListener,
    routes: Arc<R>,
}

impl<R: Routes> Server<R> {
    pub fn bind(routes: R) -> std::io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0))?;
        Ok(Self {
            listener,
            routes: Arc::new(routes),
        })
    }

    pub fn local_addr(&self) -> std::io::Result<SocketAddr> {
        self.listener.local_addr()
    }

    /// Spawn the accept loop on a background thread. Returns immediately.
    pub fn run(self) {
        thread::spawn(move || {
            for stream in self.listener.incoming() {
                let Ok(stream) = stream else { continue };
                let routes = Arc::clone(&self.routes);
                thread::spawn(move || {
                    let _ = handle(stream, routes);
                });
            }
        });
    }
}

struct Request {
    method: String,
    path: String,
    body: String,
}

fn handle<R: Routes>(mut stream: TcpStream, routes: Arc<R>) -> std::io::Result<()> {
    let req = match read_request(&mut stream) {
        Some(r) => r,
        None => {
            write_response(
                &mut stream,
                400,
                "application/json",
                r#"{"error":"bad_request"}"#,
            )?;
            return Ok(());
        }
    };

    let (status, ctype, body) = route::<R>(&*routes, &req);
    write_response(&mut stream, status, ctype, &body)
}

fn read_request(stream: &mut TcpStream) -> Option<Request> {
    let peer = stream.try_clone().ok()?;
    let mut reader = BufReader::new(peer);
    let mut line = String::new();
    if reader.read_line(&mut line).ok()? == 0 {
        return None;
    }
    let parts: Vec<&str> = line.split_whitespace().collect();
    let (method, path) = match parts.as_slice() {
        [m, p, _v] => (m.to_string(), p.to_string()),
        _ => return None,
    };
    let mut content_length: usize = 0;
    loop {
        line.clear();
        if reader.read_line(&mut line).ok()? == 0 {
            break;
        }
        if line == "\r\n" || line == "\n" || line.is_empty() {
            break;
        }
        if let Some(rest) = line
            .strip_prefix("Content-Length:")
            .or_else(|| line.strip_prefix("content-length:"))
        {
            content_length = rest.trim().parse().unwrap_or(0);
        }
    }
    let mut body = vec![0u8; content_length];
    if content_length > 0 && reader.read_exact(&mut body).is_err() {
        return None;
    }
    Some(Request {
        method,
        path,
        body: String::from_utf8_lossy(&body).into_owned(),
    })
}

fn route<R: Routes>(routes: &R, req: &Request) -> (u16, &'static str, String) {
    match (req.method.as_str(), req.path.as_str()) {
        ("GET", "/info") => (200, "application/json", routes.info_json()),
        ("GET", "/models") => (200, "application/json", routes.models_json()),
        ("GET", path) if path.starts_with("/traces") => {
            let tail = parse_query(path, "tail").unwrap_or(200);
            (200, "application/x-ndjson", routes.traces_json(tail))
        }
        ("POST", "/agent/run") => (200, "application/x-ndjson", routes.agent_run(&req.body)),
        ("GET", _) | ("POST", _) => (
            404,
            "application/json",
            r#"{"error":"not_found"}"#.to_string(),
        ),
        _ => (
            405,
            "application/json",
            r#"{"error":"method_not_allowed"}"#.to_string(),
        ),
    }
}

fn parse_query(path: &str, key: &str) -> Option<usize> {
    let q = path.split('?').nth(1)?;
    for pair in q.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            if k == key {
                return v.parse().ok();
            }
        }
    }
    None
}

fn write_response(
    stream: &mut TcpStream,
    status: u16,
    ctype: &str,
    body: &str,
) -> std::io::Result<()> {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        404 => "Not Found",
        405 => "Method Not Allowed",
        _ => "OK",
    };
    write!(
        stream,
        "HTTP/1.1 {status} {reason}\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpStream;
    use std::time::Duration;

    struct StaticRoutes;
    impl Routes for StaticRoutes {
        fn info_json(&self) -> String {
            r#"{"version":"test","arch":"x86_64","isa":"scalar","threads":1,"model_loaded":null}"#
                .to_string()
        }
        fn models_json(&self) -> String {
            "[]".to_string()
        }
    }

    fn raw_get(addr: SocketAddr, path: &str) -> String {
        let mut s = TcpStream::connect_timeout(&addr, Duration::from_secs(2)).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        write!(s, "GET {path} HTTP/1.1\r\nHost: x\r\n\r\n").unwrap();
        let mut buf = String::new();
        s.read_to_string(&mut buf).unwrap();
        buf
    }

    #[test]
    fn info_route_returns_json() {
        let server = Server::bind(StaticRoutes).unwrap();
        let addr = server.local_addr().unwrap();
        server.run();
        let resp = raw_get(addr, "/info");
        assert!(resp.starts_with("HTTP/1.1 200 OK"), "got: {resp}");
        assert!(resp.contains(r#""version":"test""#));
    }

    #[test]
    fn unknown_route_404() {
        let server = Server::bind(StaticRoutes).unwrap();
        let addr = server.local_addr().unwrap();
        server.run();
        let resp = raw_get(addr, "/nope");
        assert!(resp.starts_with("HTTP/1.1 404"), "got: {resp}");
    }

    #[test]
    fn traces_query_parses_tail() {
        struct R;
        impl Routes for R {
            fn info_json(&self) -> String {
                String::new()
            }
            fn models_json(&self) -> String {
                String::new()
            }
            fn traces_json(&self, tail: usize) -> String {
                format!(r#"{{"tail":{tail}}}"#)
            }
        }
        let server = Server::bind(R).unwrap();
        let addr = server.local_addr().unwrap();
        server.run();
        let resp = raw_get(addr, "/traces?tail=42");
        assert!(resp.contains(r#""tail":42"#));
    }
}
