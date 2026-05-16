//! Multi-backend `InferenceBackend` — wraps any number of underlying
//! engines and picks one per call by policy. The canonical setup for
//! Kaimahi is exactly two: the on-device LM and the cloud Gemini path
//! authenticated in parallel. Caller can request a specific backend or
//! let the policy decide.

use crate::InferenceBackend;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Policy {
    /// Try the first backend that's declared `available`. Fall back
    /// down the list on error. Order = declaration order.
    PreferFirst,
    /// Same as PreferFirst but inverted — useful when configured as
    /// `[cloud, local]` with policy = PreferLast to bias on-device.
    PreferLast,
    /// Round-robin over available backends; lets a long session amortise
    /// cost across both engines.
    RoundRobin,
}

pub struct Backend {
    pub name: &'static str,
    pub available: bool,
    pub inner: Box<dyn InferenceBackend>,
}

pub struct MultiBackend {
    backends: Vec<Backend>,
    policy: Policy,
    cursor: usize,
}

impl MultiBackend {
    pub fn new(backends: Vec<Backend>, policy: Policy) -> Self {
        Self {
            backends,
            policy,
            cursor: 0,
        }
    }

    fn pick_order(&mut self) -> Vec<usize> {
        let n = self.backends.len();
        match self.policy {
            Policy::PreferFirst => (0..n).collect(),
            Policy::PreferLast => (0..n).rev().collect(),
            Policy::RoundRobin => {
                let start = self.cursor % n.max(1);
                self.cursor = self.cursor.wrapping_add(1);
                (0..n).map(|i| (start + i) % n).collect()
            }
        }
    }
}

impl InferenceBackend for MultiBackend {
    fn complete(&mut self, prompt: &str, stop: &[&str]) -> Result<String, String> {
        let order = self.pick_order();
        let mut last_err = String::from("no available backend");
        for idx in order {
            let b = &mut self.backends[idx];
            if !b.available {
                continue;
            }
            match b.inner.complete(prompt, stop) {
                Ok(out) => return Ok(out),
                Err(e) => {
                    last_err = format!("[{}] {}", b.name, e);
                }
            }
        }
        Err(last_err)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Mock {
        replies: Vec<Result<String, String>>,
        idx: usize,
    }
    impl InferenceBackend for Mock {
        fn complete(&mut self, _prompt: &str, _stop: &[&str]) -> Result<String, String> {
            let r = self.replies[self.idx.min(self.replies.len() - 1)].clone();
            self.idx += 1;
            r
        }
    }

    fn b(name: &'static str, replies: Vec<Result<String, String>>) -> Backend {
        Backend {
            name,
            available: true,
            inner: Box::new(Mock { replies, idx: 0 }),
        }
    }

    #[test]
    fn first_succeeds() {
        let mut m = MultiBackend::new(
            vec![
                b("cloud", vec![Ok("hi".into())]),
                b("local", vec![Ok("ignored".into())]),
            ],
            Policy::PreferFirst,
        );
        assert_eq!(m.complete("p", &[]).unwrap(), "hi");
    }

    #[test]
    fn falls_back_on_error() {
        let mut m = MultiBackend::new(
            vec![
                b("cloud", vec![Err("rate-limited".into())]),
                b("local", vec![Ok("local-ans".into())]),
            ],
            Policy::PreferFirst,
        );
        assert_eq!(m.complete("p", &[]).unwrap(), "local-ans");
    }

    #[test]
    fn all_fail_returns_last_err_with_tag() {
        let mut m = MultiBackend::new(
            vec![
                b("cloud", vec![Err("rate".into())]),
                b("local", vec![Err("oom".into())]),
            ],
            Policy::PreferFirst,
        );
        let e = m.complete("p", &[]).unwrap_err();
        assert!(e.contains("[local] oom"), "got: {e}");
    }

    #[test]
    fn round_robin_alternates() {
        let mut m = MultiBackend::new(
            vec![
                b("a", vec![Ok("A".into()), Ok("A".into())]),
                b("b", vec![Ok("B".into()), Ok("B".into())]),
            ],
            Policy::RoundRobin,
        );
        let first = m.complete("p", &[]).unwrap();
        let second = m.complete("p", &[]).unwrap();
        assert_ne!(first, second);
    }
}
