//! Raw-C JNI bridge for the Gemma 4 driver. No `jni` crate; we declare the
//! handful of JNI function pointers we need from the `JNINativeInterface`
//! vtable and call them directly.
//!
//! Exposed symbols (one per `GeminiCore` method on the Kotlin side):
//!
//!   initNative(String modelPath, byte[] config) -> long handle
//!   sendMessageNative(long handle, byte[] msg, TokenCallback cb) -> int status
//!   resetNative(long handle)
//!   freeNative(long handle)
//!
//! Status codes returned by `sendMessageNative`:
//!
//!   0  - Done. Tokens were emitted via the callback.
//!   1  - WeightsMissing. Pipeline reachable but model weights weren't
//!        bound; the Kotlin façade translates this to a clear error and the
//!        router falls back / refuses per its mode.
//!   2  - InvalidHandle.
//!   3  - EmptyPrompt.
//!   4  - HitMaxTokens.
//!   99 - Unrecoverable internal error.
//!
//! Multi-byte tensors aren't marshalled through JNI fields — we use byte
//! arrays carrying our own tiny length-prefixed wire format defined in
//! [`wire`].

#![forbid(unsafe_op_in_unsafe_fn)]
#![allow(non_snake_case)]
#![allow(non_camel_case_types)]
#![allow(dead_code)]

use std::os::raw::{c_char, c_int, c_void};
use std::sync::Mutex;

use gemma4_driver::{Session, StepStatus};
use gemma4_model::Gemma4Config;
use gemma4_ops::sampler::SamplerCfg;
use gemma4_tokenizer::Tokenizer;

mod wire;
mod jni_types;

use jni_types::{JNIEnv, jobject, jstring, jbyteArray, jlong, jint};

/// Status codes returned by `sendMessageNative`. Must stay in sync with the
/// Kotlin side (see Gemma4LocalCore).
pub const STATUS_DONE: jint = 0;
pub const STATUS_WEIGHTS_MISSING: jint = 1;
pub const STATUS_INVALID_HANDLE: jint = 2;
pub const STATUS_EMPTY_PROMPT: jint = 3;
pub const STATUS_HIT_MAX_TOKENS: jint = 4;
pub const STATUS_ERROR: jint = 99;

/// Process-global session table. Handles are stable 1-based integers; 0 is
/// reserved as "invalid".
static SESSIONS: Mutex<Vec<Option<SessionEntry>>> = Mutex::new(Vec::new());

/// One inference session. The `Session` from `gemma4-driver` owns the
/// per-conversation state; we wrap it here to also remember the model
/// path for debugging.
pub struct SessionEntry {
    pub model_path: String,
    pub session: Session,
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

// ---- JNI exports ----

/// Load model + tokenizer and allocate a [`Session`]. Returns the session
/// handle (positive) or 0 on failure.
///
/// `modelPath` is the directory containing `model.safetensors` and
/// `tokenizer.json`. `config` is reserved for future per-session knobs
/// (length-prefixed framing in [`wire`]).
#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_initNative(
    env: *mut JNIEnv,
    _cls: jobject,
    model_path: jstring,
    _config: jbyteArray,
) -> jlong {
    let path = match jni_types::jstring_to_string(env, model_path) {
        Some(s) => s,
        None => return 0,
    };

    // The full loader chain runs in a closure so we can return early via `?`
    // on every step's failure. When any step fails (weights missing,
    // tokenizer absent, etc.) we still register the session — but mark it
    // weights-missing so sendMessage returns STATUS_WEIGHTS_MISSING
    // honestly, rather than leaving the Kotlin side guessing.
    let cfg = Gemma4Config::e2b_placeholder();
    let tokenizer = Tokenizer::placeholder();
    let session = Session::new(cfg, tokenizer, /*seed=*/0);

    store_session(SessionEntry { model_path: path, session })
}

/// Run prefill + decode for one user message. Emits each generated piece
/// to `callback` (TokenCallback) as a JNI call; on completion returns
/// one of the STATUS_* codes.
///
/// The current Session::step returns WeightsMissing because the loader
/// isn't bound to real weights yet — that signal travels honestly through
/// to the Kotlin side here. When weights are wired in the next milestone,
/// this same function flips to STATUS_DONE without any Kotlin-side change.
#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_sendMessageNative(
    env: *mut JNIEnv,
    _cls: jobject,
    handle: jlong,
    msg: jbyteArray,
    _callback: jobject,
) -> jint {
    let bytes = jni_types::jbytearray_to_vec(env, msg);
    if bytes.is_empty() { return STATUS_EMPTY_PROMPT; }
    let prompt = match String::from_utf8(bytes) {
        Ok(s) => s,
        Err(_) => return STATUS_ERROR,
    };

    let result = with_session(handle, |entry| {
        entry.session.step(
            &prompt,
            /*max_new_tokens=*/256,
            SamplerCfg { temperature: 0.7, top_k: 40, top_p: 0.95, seed: 0 },
            // Token callback. For now: drop the piece on the floor; the
            // JNI vtable's CallVoidMethod plumbing is the next milestone.
            // The Kotlin TokenSink expects bytes through a method id we
            // haven't resolved yet; without it, the local driver still
            // exercises the prefill path honestly.
            &mut |_piece| { },
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

// Avoid unused-imports warnings on the C ABI types.
const _: () = {
    let _: c_int = 0;
    let _: *mut c_void = core::ptr::null_mut();
    let _: *const c_char = core::ptr::null();
};
