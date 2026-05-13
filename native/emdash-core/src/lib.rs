//! Shared types between the emdash-rs server and the Android client.
//! Per the design doc: PortableText AST, RequestContext, abstract trait
//! seams. Server-side trait impls live in their respective crates;
//! Android only consumes the types and the client crate.

pub mod context;
pub mod portable_text;
pub mod schema;
pub mod traits;
