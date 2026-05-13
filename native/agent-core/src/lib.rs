//! Ported DeepAgent state machine. Marker-driven cycle. Generic over
//! `InferenceBackend` so the agent can be unit-tested with a mock and so
//! the same code path serves any future inference engine.

pub mod markers {
    pub const BEGIN_TOOL_SEARCH: &str = "[BEGIN_TOOL_SEARCH]";
    pub const END_TOOL_SEARCH: &str = "[END_TOOL_SEARCH]";
    pub const BEGIN_TOOL_CALL: &str = "[BEGIN_TOOL_CALL]";
    pub const END_TOOL_CALL: &str = "[END_TOOL_CALL]";
    pub const FOLD_THOUGHT: &str = "[FOLD_THOUGHT]";
}

#[derive(Debug, Clone)]
pub struct ToolDescriptor {
    pub name: String,
    pub description: String,
    pub destructive: bool,
}

#[derive(Debug, Clone)]
pub enum AgentEvent {
    Thinking(String),
    ToolCallPending {
        call_id: String,
        name: String,
        args: String,
    },
    ToolCallCompleted {
        call_id: String,
        ok: bool,
        output_len: usize,
    },
    Message(String),
    Done,
    Error(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Reasoning,
    SearchingTools,
    CallingTool,
    Folding,
    Done,
}

pub trait InferenceBackend: Send {
    /// Run the model until any of `stop` appears in the output. Returns
    /// the accumulated text (excluding the stop sequence).
    fn complete(&mut self, prompt: &str, stop: &[&str]) -> Result<String, String>;
}

pub trait ToolDispatcher: Send {
    fn list(&self) -> Vec<ToolDescriptor>;
    fn call(&mut self, name: &str, args: &str) -> Result<String, String>;
}

pub struct Agent<I: InferenceBackend, T: ToolDispatcher> {
    pub inference: I,
    pub tools: T,
    pub max_iterations: usize,
}

impl<I: InferenceBackend, T: ToolDispatcher> Agent<I, T> {
    pub fn new(inference: I, tools: T) -> Self {
        Self {
            inference,
            tools,
            max_iterations: 50,
        }
    }

    /// Run the agent loop. Emits events for every state transition,
    /// tool call, and message produced. Returns when the model emits
    /// `Done` (no markers in the trailing completion) or `max_iterations`
    /// is exhausted.
    pub fn run<F: FnMut(AgentEvent)>(&mut self, goal: &str, mut emit: F) {
        let mut transcript = String::from(goal);
        let mut done = false;
        let mut iter = 0;
        let mut next_call = 0u64;
        let _ = (
            State::Reasoning,
            State::SearchingTools,
            State::CallingTool,
            State::Folding,
            State::Done,
        );

        while iter < self.max_iterations && !done {
            iter += 1;
            let stops = [
                markers::BEGIN_TOOL_SEARCH,
                markers::BEGIN_TOOL_CALL,
                markers::FOLD_THOUGHT,
            ];
            let completion = match self.inference.complete(&transcript, &stops) {
                Ok(c) => c,
                Err(e) => {
                    emit(AgentEvent::Error(e));
                    return;
                }
            };

            // Decide next state by inspecting which marker was hit.
            let marker = detect_marker(&completion);
            let body = strip_marker(&completion, marker);
            if !body.trim().is_empty() {
                emit(AgentEvent::Thinking(body.clone()));
            }
            transcript.push_str(&completion);

            match marker {
                Some(m) if m == markers::BEGIN_TOOL_SEARCH => {
                    let listing = self
                        .tools
                        .list()
                        .iter()
                        .map(|t| format!("- {}: {}", t.name, t.description))
                        .collect::<Vec<_>>()
                        .join("\n");
                    transcript.push('\n');
                    transcript.push_str(&listing);
                    transcript.push('\n');
                    transcript.push_str(markers::END_TOOL_SEARCH);
                    transcript.push('\n');
                }
                Some(m) if m == markers::BEGIN_TOOL_CALL => {
                    // Continue generation to read the tool name + args
                    // until END_TOOL_CALL.
                    let stops = [markers::END_TOOL_CALL];
                    let payload = match self.inference.complete(&transcript, &stops) {
                        Ok(p) => p,
                        Err(e) => {
                            emit(AgentEvent::Error(e));
                            return;
                        }
                    };
                    transcript.push_str(&payload);
                    transcript.push_str(markers::END_TOOL_CALL);
                    let (name, args) = parse_tool_call(&payload);
                    let call_id = format!("c{}", next_call);
                    next_call += 1;
                    emit(AgentEvent::ToolCallPending {
                        call_id: call_id.clone(),
                        name: name.clone(),
                        args: args.clone(),
                    });
                    let result = self.tools.call(&name, &args);
                    match result {
                        Ok(out) => {
                            transcript.push('\n');
                            transcript.push_str(&out);
                            transcript.push('\n');
                            emit(AgentEvent::ToolCallCompleted {
                                call_id,
                                ok: true,
                                output_len: out.len(),
                            });
                        }
                        Err(e) => {
                            transcript.push_str("\nERROR: ");
                            transcript.push_str(&e);
                            transcript.push('\n');
                            emit(AgentEvent::ToolCallCompleted {
                                call_id,
                                ok: false,
                                output_len: e.len(),
                            });
                        }
                    }
                }
                Some(m) if m == markers::FOLD_THOUGHT => {
                    // Fold = compress transcript to a summary slot. v0
                    // collapses the transcript to just the goal + the
                    // most recent N chars; a real impl persists via sled.
                    let tail_len = 1024.min(transcript.len());
                    let tail_start = transcript.len() - tail_len;
                    let kept_tail = transcript[tail_start..].to_string();
                    transcript.clear();
                    transcript.push_str(goal);
                    transcript.push('\n');
                    transcript.push_str(&kept_tail);
                }
                _ => {
                    // No marker → model is done reasoning.
                    if !body.trim().is_empty() {
                        emit(AgentEvent::Message(body));
                    }
                    done = true;
                }
            }
        }
        emit(AgentEvent::Done);
    }
}

fn detect_marker(s: &str) -> Option<&'static str> {
    [
        markers::BEGIN_TOOL_SEARCH,
        markers::BEGIN_TOOL_CALL,
        markers::FOLD_THOUGHT,
    ]
    .into_iter()
    .find(|m| s.contains(*m))
}

fn strip_marker(s: &str, marker: Option<&str>) -> String {
    match marker {
        Some(m) => s.replace(m, ""),
        None => s.to_string(),
    }
}

/// Parse a tool call payload. v0 accepts `name(args_json)` or `name args`.
fn parse_tool_call(payload: &str) -> (String, String) {
    let trimmed = payload.trim();
    if let Some(open) = trimmed.find('(') {
        if let Some(close) = trimmed.rfind(')') {
            if close > open {
                return (
                    trimmed[..open].trim().to_string(),
                    trimmed[open + 1..close].to_string(),
                );
            }
        }
    }
    if let Some(sp) = trimmed.find(char::is_whitespace) {
        return (trimmed[..sp].to_string(), trimmed[sp + 1..].to_string());
    }
    (trimmed.to_string(), String::new())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    struct MockInference {
        replies: VecDeque<String>,
    }

    impl InferenceBackend for MockInference {
        fn complete(&mut self, _prompt: &str, _stop: &[&str]) -> Result<String, String> {
            Ok(self.replies.pop_front().unwrap_or_default())
        }
    }

    struct MockTools;
    impl ToolDispatcher for MockTools {
        fn list(&self) -> Vec<ToolDescriptor> {
            vec![ToolDescriptor {
                name: "echo".to_string(),
                description: "echoes input".to_string(),
                destructive: false,
            }]
        }
        fn call(&mut self, name: &str, args: &str) -> Result<String, String> {
            if name == "echo" {
                Ok(args.to_string())
            } else {
                Err(format!("unknown tool: {name}"))
            }
        }
    }

    #[test]
    fn parses_paren_call() {
        let (n, a) = parse_tool_call("echo(hello world)");
        assert_eq!(n, "echo");
        assert_eq!(a, "hello world");
    }

    #[test]
    fn parses_space_call() {
        let (n, a) = parse_tool_call("echo hello world");
        assert_eq!(n, "echo");
        assert_eq!(a, "hello world");
    }

    #[test]
    fn agent_completes_without_marker() {
        let mut events = Vec::new();
        let mut agent = Agent::new(
            MockInference {
                replies: VecDeque::from(vec!["Final answer.".to_string()]),
            },
            MockTools,
        );
        agent.run("ping", |e| events.push(e));
        assert!(events.iter().any(|e| matches!(e, AgentEvent::Message(_))));
        assert!(matches!(events.last(), Some(AgentEvent::Done)));
    }

    #[test]
    fn agent_invokes_tool() {
        let mut events = Vec::new();
        let mut agent = Agent::new(
            MockInference {
                replies: VecDeque::from(vec![
                    format!("Thinking{}", markers::BEGIN_TOOL_CALL),
                    "echo(hi)".to_string(),
                    "Done.".to_string(),
                ]),
            },
            MockTools,
        );
        agent.run("call echo", |e| events.push(e));
        let has_pending = events
            .iter()
            .any(|e| matches!(e, AgentEvent::ToolCallPending { name, .. } if name == "echo"));
        let has_completed = events
            .iter()
            .any(|e| matches!(e, AgentEvent::ToolCallCompleted { ok: true, .. }));
        assert!(has_pending, "expected a ToolCallPending event for echo");
        assert!(
            has_completed,
            "expected a successful ToolCallCompleted event"
        );
    }
}
