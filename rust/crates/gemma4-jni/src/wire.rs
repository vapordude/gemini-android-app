//! Tiny msgpack-shaped wire format for JNI messages.
//!
//! Why not a full msgpack: keeps the crate dep-free (`rmp-serde` is
//! external). We only need a couple of message shapes; a hand-rolled
//! length-prefixed format costs ~80 lines and zero deps.
//!
//! Frame layout:
//!
//! ```text
//! [1 byte: tag] [4 bytes BE: length] [length bytes: payload]
//! ```
//!
//! Tags:
//!   0x01  InitConfig    (utf8 json text)
//!   0x02  SendMessage   (utf8 user text)
//!   0x10  Token         (utf8 piece) — emitted via callback
//!   0x1f  Done          (zero-length)
//!   0xff  Error         (utf8 message)

pub const TAG_INIT_CONFIG: u8 = 0x01;
pub const TAG_SEND_MESSAGE: u8 = 0x02;
pub const TAG_TOKEN: u8 = 0x10;
pub const TAG_DONE: u8 = 0x1f;
pub const TAG_ERROR: u8 = 0xff;

pub fn encode_frame(tag: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + payload.len());
    out.push(tag);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

pub fn decode_frame(buf: &[u8]) -> Option<(u8, &[u8])> {
    if buf.len() < 5 { return None; }
    let tag = buf[0];
    let len = u32::from_be_bytes([buf[1], buf[2], buf[3], buf[4]]) as usize;
    if buf.len() < 5 + len { return None; }
    Some((tag, &buf[5..5 + len]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let payload = b"hello, gemma4";
        let frame = encode_frame(TAG_TOKEN, payload);
        let (tag, recovered) = decode_frame(&frame).unwrap();
        assert_eq!(tag, TAG_TOKEN);
        assert_eq!(recovered, payload);
    }

    #[test]
    fn rejects_short_buffer() {
        assert!(decode_frame(&[]).is_none());
        assert!(decode_frame(&[1, 2, 3]).is_none());
        // Length says 100 but only 4 bytes follow.
        assert!(decode_frame(&[1, 0, 0, 0, 100, 0, 0, 0, 0]).is_none());
    }
}
