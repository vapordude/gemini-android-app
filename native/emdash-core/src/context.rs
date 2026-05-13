/// Per-request context propagated through trait boundaries. Mirrors the
/// TypeScript original's `RequestContext`.
#[derive(Debug, Clone, Default)]
pub struct RequestContext {
    pub user_id: Option<String>,
    pub env: Option<String>,
    pub correlation_id: Option<String>,
}
