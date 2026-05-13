//! Pure-Rust GGUF v3 parser. File format only — no third-party deps. No
//! weight content inspection beyond architecture detection.

use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::Path;

/// GGUF magic: `GGUF` little-endian.
const MAGIC: u32 = 0x46554747;

#[derive(Debug)]
pub struct GgufHeader {
    pub version: u32,
    pub tensor_count: u64,
    pub metadata_count: u64,
    pub arch_tag: String,
}

#[derive(Debug)]
pub enum LoadError {
    Io(io::Error),
    BadMagic,
    UnsupportedVersion(u32),
    MissingArchitecture,
    Truncated,
}

impl From<io::Error> for LoadError {
    fn from(e: io::Error) -> Self {
        LoadError::Io(e)
    }
}

pub fn read_header(path: &Path) -> Result<GgufHeader, LoadError> {
    let mut f = File::open(path)?;
    let mut buf = [0u8; 24];
    f.read_exact(&mut buf)?;
    let magic = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
    if magic != MAGIC {
        return Err(LoadError::BadMagic);
    }
    let version = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
    if version != 3 {
        return Err(LoadError::UnsupportedVersion(version));
    }
    let tensor_count = u64::from_le_bytes([
        buf[8], buf[9], buf[10], buf[11], buf[12], buf[13], buf[14], buf[15],
    ]);
    let metadata_count = u64::from_le_bytes([
        buf[16], buf[17], buf[18], buf[19], buf[20], buf[21], buf[22], buf[23],
    ]);
    // TODO: scan metadata KV entries for `general.architecture`. Stub for now.
    let arch_tag = scan_architecture_tag(&mut f).unwrap_or_else(|| "unknown".to_string());
    Ok(GgufHeader {
        version,
        tensor_count,
        metadata_count,
        arch_tag,
    })
}

fn scan_architecture_tag(f: &mut File) -> Option<String> {
    // TODO: real metadata scan. v0 just returns None so we fall through to
    // architecture detection by other means.
    let _ = f.seek(SeekFrom::Start(24));
    None
}
