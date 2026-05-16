//! Raw-C JNI bridge for the Gemma 4 driver. No `jni` crate; we declare
//! the handful of `JNINativeInterface` slots we need in [`jni_types`]
//! and call them directly.
//!
//! Exposed symbols (one per `GeminiCore` method on the Kotlin side):
//!
//!   initNative(String modelDir, byte[] config) -> long handle
//!   sendMessageNative(long handle, byte[] msg, TokenCallback cb) -> int status
//!   resetNative(long handle)
//!   freeNative(long handle)
//!
//! `TokenCallback` must declare a Java method
//! `void onToken(String piece)` — the bridge resolves it by signature
//! the first time `sendMessageNative` runs on a handle and caches the
//! id alongside the session.
//!
//! Status codes returned by `sendMessageNative`:
//!
//!   0  - Done. Tokens were emitted via the callback.
//!   1  - WeightsMissing.
//!   2  - InvalidHandle.
//!   3  - EmptyPrompt.
//!   4  - HitMaxTokens.
//!  98  - LoadFailed (paths invalid, malformed safetensors, dtype unsupported).
//!  99  - Unrecoverable internal error.

#![forbid(unsafe_op_in_unsafe_fn)]
#![allow(non_snake_case)]
#![allow(non_camel_case_types)]
#![allow(dead_code)]

use std::os::raw::{c_char, c_int, c_void};
use std::path::Path;
use std::sync::Mutex;

use gemma4_core::SafeTensors;
use gemma4_driver::{Session, StepStatus, Weights};
use gemma4_model::{Gemma4Config, load_global, load_layer};
use gemma4_ops::sampler::SamplerCfg;
use gemma4_tokenizer::Tokenizer;

mod jni_types;
mod wire;

use jni_types::{jbyteArray, jint, jlong, jobject, jstring, JNIEnv};

pub const STATUS_DONE: jint = 0;
pub const STATUS_WEIGHTS_MISSING: jint = 1;
pub const STATUS_INVALID_HANDLE: jint = 2;
pub const STATUS_EMPTY_PROMPT: jint = 3;
pub const STATUS_HIT_MAX_TOKENS: jint = 4;
pub const STATUS_LOAD_FAILED: jint = 98;
pub const STATUS_ERROR: jint = 99;

/// Process-global session table. Handles are 1-based; 0 = "invalid".
static SESSIONS: Mutex<Vec<Option<SessionEntry>>> = Mutex::new(Vec::new());
/// Last error string captured by [`load_session`]; surfaced via
/// `nativeLastError` so the Kotlin side can render a meaningful
/// failure reason instead of a generic 0 handle.
static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);

pub struct SessionEntry {
    pub model_dir: String,
    pub session: Session,
    /// Cached id for `TokenCallback.onToken(String)`. Resolved the
    /// first time `sendMessageNative` is invoked on this handle.
    pub on_token_mid: Option<MethodIdHandle>,
}

/// `jmethodID` is `*mut c_void`, which isn't `Send` by default. The
/// JVM guarantees method ids are stable for the life of the class, and
/// our access is serialized through `SESSIONS` Mutex anyway, so it's
/// safe to mark this wrapper Send + Sync.
#[derive(Clone, Copy)]
pub struct MethodIdHandle(pub jni_types::jmethodID);

// SAFETY: see [`MethodIdHandle`] doc — stable JVM handle, mutex-gated.
unsafe impl Send for MethodIdHandle {}
unsafe impl Sync for MethodIdHandle {}

fn set_last_error(s: impl Into<String>) {
    *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = Some(s.into());
}

fn store_session(sess: SessionEntry) -> jlong {
    let mut g = SESSIONS.lock().unwrap_or_else(|e| e.into_inner());
    for (i, slot) in g.iter_mut().enumerate() {
        if slot.is_none() {
            *slot = Some(sess);
            return (i as jlong) + 1;
        }
    }
    g.push(Some(sess));
    g.len() as jlong
}

fn take_session(handle: jlong) -> Option<SessionEntry> {
    let mut g = SESSIONS.lock().unwrap_or_else(|e| e.into_inner());
    let idx = (handle as usize).checked_sub(1)?;
    g.get_mut(idx).and_then(|s| s.take())
}

fn with_session<R, F: FnOnce(&mut SessionEntry) -> R>(handle: jlong, f: F) -> Option<R> {
    let mut g = SESSIONS.lock().unwrap_or_else(|e| e.into_inner());
    let idx = (handle as usize).checked_sub(1)?;
    let slot = g.get_mut(idx)?.as_mut()?;
    Some(f(slot))
}

/// Find `model.safetensors` + `tokenizer.json` + `config.json` under
/// `dir` and build a fully-weighted [`Session`]. Errors land in
/// [`LAST_ERROR`]; callers translate `None` into `STATUS_LOAD_FAILED`.
fn load_session(dir: &Path) -> Option<Session> {
    if !dir.is_dir() {
        set_last_error(format!("not a directory: {}", dir.display()));
        return None;
    }
    let tokenizer = match load_tokenizer(dir) {
        Ok(t) => t,
        Err(e) => {
            set_last_error(format!("tokenizer: {e}"));
            return None;
        }
    };
    let cfg = match load_config(dir) {
        Ok(c) => c,
        Err(e) => {
            set_last_error(format!("config: {e}"));
            return None;
        }
    };
    let st_path = dir.join("model.safetensors");
    let bytes = match std::fs::read(&st_path) {
        Ok(b) => b,
        Err(e) => {
            set_last_error(format!("read {}: {e}", st_path.display()));
            return None;
        }
    };
    let st = match SafeTensors::parse(&bytes) {
        Ok(s) => s,
        Err(e) => {
            set_last_error(format!("safetensors: {e}"));
            return None;
        }
    };
    let global = match load_global(&st, &cfg) {
        Ok(g) => g,
        Err(e) => {
            set_last_error(format!("global weights: {e}"));
            return None;
        }
    };
    let mut layers = Vec::with_capacity(cfg.num_layers);
    for i in 0..cfg.num_layers {
        match load_layer(&st, &cfg, i) {
            Ok(l) => layers.push(l),
            Err(e) => {
                set_last_error(format!("layer {i}: {e}"));
                return None;
            }
        }
    }
    let weights = Weights { global, layers };
    Some(Session::with_weights(cfg, tokenizer, weights, /*seed=*/ 0))
}

fn load_tokenizer(dir: &Path) -> Result<Tokenizer, String> {
    let path = dir.join("tokenizer.json");
    let text = std::fs::read_to_string(&path)
        .map_err(|e| format!("read {}: {e}", path.display()))?;
    Tokenizer::from_json(&text).ok_or_else(|| format!("could not parse {}", path.display()))
}

fn load_config(dir: &Path) -> Result<Gemma4Config, String> {
    let path = dir.join("config.json");
    let text = std::fs::read_to_string(&path)
        .map_err(|e| format!("read {}: {e}", path.display()))?;
    parse_gemma_config(&text)
}

/// Tiny JSON pull-parser dedicated to the Gemma `config.json` keys we
/// read. We avoid `serde_json` to keep the no-deps invariant.
fn parse_gemma_config(text: &str) -> Result<Gemma4Config, String> {
    let mut cfg = Gemma4Config::e2b_placeholder();
    let get_usize = |key: &str| -> Option<usize> {
        find_number_field(text, key).map(|v| v as usize)
    };
    let get_f32 = |key: &str| -> Option<f32> { find_number_field(text, key) };
    let get_bool = |key: &str| -> Option<bool> { find_bool_field(text, key) };

    if let Some(v) = get_usize("hidden_size") {
        cfg.hidden_size = v;
    }
    if let Some(v) = get_usize("intermediate_size") {
        cfg.intermediate_size = v;
    }
    if let Some(v) = get_usize("num_hidden_layers") {
        cfg.num_layers = v;
    }
    if let Some(v) = get_usize("num_attention_heads") {
        cfg.num_query_heads = v;
    }
    if let Some(v) = get_usize("num_key_value_heads") {
        cfg.num_kv_heads = v;
    }
    if let Some(v) = get_usize("head_dim") {
        cfg.head_dim = v;
    } else if cfg.num_query_heads > 0 {
        cfg.head_dim = cfg.hidden_size / cfg.num_query_heads;
    }
    if let Some(v) = get_usize("max_position_embeddings") {
        cfg.max_position = v;
    }
    if let Some(v) = get_usize("vocab_size") {
        cfg.vocab_size = v;
    }
    if let Some(v) = get_f32("rope_theta") {
        cfg.rope_theta = v;
    }
    if let Some(v) = get_f32("rms_norm_eps") {
        cfg.rms_norm_eps = v;
    }
    if let Some(v) = get_bool("tie_word_embeddings") {
        cfg.tied_embeddings = v;
    }
    Ok(cfg)
}

fn find_number_field(text: &str, key: &str) -> Option<f32> {
    let needle = format!("\"{key}\"");
    let i = text.find(&needle)?;
    let after = &text[i + needle.len()..];
    let colon = after.find(':')?;
    let mut rest = after[colon + 1..].trim_start();
    // Strip a leading minus into `negate` so the parse-as-positive path
    // can stay simple.
    let negate = rest.starts_with('-');
    if negate {
        rest = &rest[1..];
    }
    let end = rest
        .find(|c: char| !(c.is_ascii_digit() || c == '.' || c == 'e' || c == 'E' || c == '+'))
        .unwrap_or(rest.len());
    let num_str = &rest[..end];
    let v: f32 = num_str.parse().ok()?;
    Some(if negate { -v } else { v })
}

fn find_bool_field(text: &str, key: &str) -> Option<bool> {
    let needle = format!("\"{key}\"");
    let i = text.find(&needle)?;
    let after = &text[i + needle.len()..];
    let colon = after.find(':')?;
    let rest = after[colon + 1..].trim_start();
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

// ---- JNI exports ----

/// Load model + tokenizer and allocate a [`Session`]. Returns the
/// session handle (positive) or 0 on failure. Read the failure reason
/// via `nativeLastError`.
#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_initNative(
    env: *mut JNIEnv,
    _cls: jobject,
    model_path: jstring,
    _config: jbyteArray,
) -> jlong {
    let path = match jni_types::jstring_to_string(env, model_path) {
        Some(s) => s,
        None => {
            set_last_error("model_path is null or empty");
            return 0;
        }
    };
    let dir = Path::new(&path);
    match load_session(dir) {
        Some(session) => store_session(SessionEntry {
            model_dir: path,
            session,
            on_token_mid: None,
        }),
        None => 0,
    }
}

/// Run prefill + decode for one user message. Each generated piece is
/// pushed to `callback.onToken(piece)`; on completion returns one of
/// the STATUS_* codes.
#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_sendMessageNative(
    env: *mut JNIEnv,
    _cls: jobject,
    handle: jlong,
    msg: jbyteArray,
    callback: jobject,
) -> jint {
    let bytes = jni_types::jbytearray_to_vec(env, msg);
    if bytes.is_empty() {
        return STATUS_EMPTY_PROMPT;
    }
    let prompt = match String::from_utf8(bytes) {
        Ok(s) => s,
        Err(_) => return STATUS_ERROR,
    };

    // Resolve the callback method id once per session and cache it.
    // Re-resolution is safe (same JVM, same class) but the cache keeps
    // every step fast.
    let mid = if callback.is_null() {
        None
    } else {
        match with_session(handle, |entry| entry.on_token_mid) {
            Some(Some(m)) => Some(m.0),
            Some(None) => {
                let resolved = jni_types::resolve_callback_method(
                    env,
                    callback,
                    "onToken",
                    "(Ljava/lang/String;)V",
                );
                let Some(r) = resolved else {
                    return STATUS_ERROR;
                };
                let _ = with_session(handle, |entry| {
                    entry.on_token_mid = Some(MethodIdHandle(r))
                });
                Some(r)
            }
            None => return STATUS_INVALID_HANDLE,
        }
    };

    // The closure below crosses an FFI boundary to call into the JVM
    // per token. Both `env` and `callback` outlive the JNI call (the
    // JVM keeps them valid for the duration of this native frame).
    let result = with_session(handle, |entry| {
        entry.session.step(
            &prompt,
            /*max_new_tokens=*/ 256,
            SamplerCfg {
                temperature: 0.7,
                top_k: 40,
                top_p: 0.95,
                seed: 0,
            },
            &mut |piece| {
                if let Some(m) = mid {
                    jni_types::call_void_with_string(env, callback, m, piece);
                }
            },
        )
    });

    match result {
        None => STATUS_INVALID_HANDLE,
        Some(StepStatus::Done) => STATUS_DONE,
        Some(StepStatus::HitMaxTokens) => STATUS_HIT_MAX_TOKENS,
        Some(StepStatus::EmptyPrompt) => STATUS_EMPTY_PROMPT,
        Some(StepStatus::WeightsMissing) => STATUS_WEIGHTS_MISSING,
    }
}

#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_resetNative(
    _env: *mut JNIEnv,
    _cls: jobject,
    handle: jlong,
) {
    let _ = with_session(handle, |entry| entry.session.reset());
}

#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_freeNative(
    _env: *mut JNIEnv,
    _cls: jobject,
    handle: jlong,
) {
    drop(take_session(handle));
}

/// Last error string from a failed load. Caller wraps the returned
/// pointer in a Java `String` via `NewStringUTF`. Returns an empty
/// jstring when there's no pending error.
#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_lastErrorNative(
    env: *mut JNIEnv,
    _cls: jobject,
) -> jstring {
    let msg = LAST_ERROR
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
        .unwrap_or_default();
    let Ok(c_msg) = std::ffi::CString::new(msg) else {
        return std::ptr::null_mut();
    };
    if env.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: env is JVM-owned; c_msg outlives the call.
    unsafe {
        let funcs = (*env).functions;
        ((*funcs).NewStringUTF)(env, c_msg.as_ptr())
    }
}

// Defeat unused-imports lints on the C ABI types we keep around for the
// header dance.
const _: () = {
    let _: c_int = 0;
    let _: *mut c_void = core::ptr::null_mut();
    let _: *const c_char = core::ptr::null();
};
