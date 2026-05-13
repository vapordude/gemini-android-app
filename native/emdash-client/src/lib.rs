//! Typed HTTP/JSON client for remote emdash-rs instances over `/_emdash/api/*`.
//! Hand-rolled to keep the dep surface zero — uses std TCP + a small HTTP/1.1
//! encoder/decoder once implemented. v0 ships the connection-profile model
//! and a `Client` shell.

use emdash_core::context::RequestContext;
use emdash_core::schema::Collection;

#[derive(Debug, Clone)]
pub struct Profile {
    pub name: String,
    pub base_url: String,
    pub env: Env,
    pub auth: Option<AuthToken>,
}

#[derive(Debug, Clone, Copy)]
pub enum Env {
    Dev,
    Staging,
    Prod,
}

#[derive(Debug, Clone)]
pub struct AuthToken(pub String);

pub struct Client {
    pub profile: Profile,
}

impl Client {
    pub fn new(profile: Profile) -> Self {
        Self { profile }
    }

    pub fn list_collections(&self, _ctx: &RequestContext) -> Result<Vec<Collection>, String> {
        // TODO: hand-rolled HTTP GET to {base_url}/_emdash/api/collections.
        Ok(Vec::new())
    }

    pub fn preview_diff(&self, _ctx: &RequestContext, _payload: &str) -> Result<String, String> {
        // TODO.
        Ok("{}".to_string())
    }
}
