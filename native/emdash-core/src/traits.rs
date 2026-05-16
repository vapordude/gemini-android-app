//! Abstract trait seams from the design doc. Server-side impls live in
//! their dedicated crates. On Android we only use the types in this module.

use crate::context::RequestContext;
use crate::schema::Collection;

pub trait DatabaseProvider: Send + Sync {
    fn list_collections(&self, ctx: &RequestContext) -> Result<Vec<Collection>, String>;
    // TODO: insert/update/delete row APIs.
}

pub trait StorageProvider: Send + Sync {
    fn get(&self, key: &str) -> Result<Vec<u8>, String>;
    fn put(&self, key: &str, value: &[u8]) -> Result<(), String>;
}

pub trait LlmProvider: Send + Sync {
    fn complete(&self, prompt: &str) -> Result<String, String>;
}

pub trait PluginRunner: Send + Sync {
    fn run_hook(&self, hook: &str, payload: &[u8]) -> Result<Vec<u8>, String>;
}
