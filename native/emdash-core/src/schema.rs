//! Collection schema models. Mirrors `ec_*` tables EmDash generates.

#[derive(Debug, Clone)]
pub struct Collection {
    pub name: String,
    pub fields: Vec<Field>,
}

#[derive(Debug, Clone)]
pub struct Field {
    pub name: String,
    pub ty: FieldType,
    pub required: bool,
}

#[derive(Debug, Clone)]
pub enum FieldType {
    String,
    Number,
    Boolean,
    DateTime,
    Json,
    PortableText,
    Reference(String),
}
