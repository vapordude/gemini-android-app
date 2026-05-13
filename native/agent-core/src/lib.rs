//! Ported DeepAgent state machine. Marker-driven cycle:
//! `[BEGIN_TOOL_SEARCH]`, `[BEGIN_TOOL_CALL]`, `[FOLD_THOUGHT]`.
//! Generic over `InferenceBackend` so the agent can be unit-tested with a
//! mock and so the same code path serves the future private engines.

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
    ToolCallPending { call_id: String, name: String, args: String },
    ToolCallCompleted { call_id: String, ok: bool, output_len: usize },
    Message(String),
    Done,
    Error(String),
}

pub trait InferenceBackend: Send {
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
        Self { inference, tools, max_iterations: 50 }
    }

    pub fn run<F: FnMut(AgentEvent)>(&mut self, _goal: &str, mut emit: F) {
        // TODO: port the marker-driven loop from DeepAgent-scar/src/agent.rs.
        // v0 just emits Done so the bridge can wire end-to-end.
        emit(AgentEvent::Done);
    }
}
