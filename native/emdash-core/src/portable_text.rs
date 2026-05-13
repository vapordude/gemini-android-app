//! PortableText AST as nested structs. Serde-derives land in a follow-up
//! once serde is added; v0 is plain Rust types.

#[derive(Debug, Clone)]
pub struct Block {
    pub ty: String,
    pub key: String,
    pub children: Vec<Span>,
    pub style: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Span {
    pub ty: String,
    pub key: String,
    pub text: String,
    pub marks: Vec<String>,
}
