//! Hand-rolled HTTP/1.1 server bound to 127.0.0.1, kernel-assigned port.
//! No third-party HTTP framework. Implements the contract defined in
//! `native/openapi.yaml`. Used by both the Android app (via JNI start/stop)
//! and by daily devops tools (CLIs, editor plugins).
//!
//! v0: connection accept loop + a route-table stub. Full request parsing
//! and NDJSON streaming land in follow-ups.

use std::io::{BufRead, BufReader, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::thread;

pub struct Server {
    listener: TcpListener,
}

impl Server {
    /// Bind to 127.0.0.1 on a kernel-assigned port. Returns the bound addr.
    pub fn bind() -> std::io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0))?;
        Ok(Self { listener })
    }

    pub fn local_addr(&self) -> std::io::Result<SocketAddr> {
        self.listener.local_addr()
    }

    pub fn serve(self) -> std::io::Result<()> {
        for stream in self.listener.incoming() {
            let stream = stream?;
            thread::spawn(move || {
                let _ = handle(stream);
            });
        }
        Ok(())
    }
}

fn handle(stream: TcpStream) -> std::io::Result<()> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut request_line = String::new();
    reader.read_line(&mut request_line)?;
    let parts: Vec<&str> = request_line.trim().split_whitespace().collect();
    let (method, path) = match parts.as_slice() {
        [m, p, _v] => (*m, *p),
        _ => ("", ""),
    };
    let body = route(method, path);
    let mut out = stream;
    write!(
        out,
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        body
    )?;
    Ok(())
}

fn route(method: &str, path: &str) -> String {
    match (method, path) {
        ("GET", "/info") => r#"{"version":"0.1.0","arch":"unknown","isa":"scalar","threads":1,"model_loaded":null}"#.to_string(),
        ("GET", "/models") => "[]".to_string(),
        _ => r#"{"error":"not_implemented"}"#.to_string(),
    }
}
