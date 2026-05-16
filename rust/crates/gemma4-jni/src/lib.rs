//! Raw-C JNI bridge for the Gemma 4 driver. No `jni` crate; we declare the
//! handful of JNI function pointers we need from the `JNINativeInterface`
//! vtable and call them directly.
//!
//! Exposed surface (one per `GeminiCore` method on the Kotlin side):
//!
//!   nativeInit(modelPath, configBytes) -> handle: long
//!   nativeSendMessage(handle, msgBytes, callback) -> status: int
//!   nativeReset(handle)
//!   nativeFree(handle)
//!
//! Multi-byte tensors aren't marshalled through JNI fields — we use byte
//! arrays carrying our own tiny msgpack-shaped wire format defined in
//! [`wire`].

#![forbid(unsafe_op_in_unsafe_fn)]
#![allow(non_snake_case)]
#![allow(non_camel_case_types)]

use std::os::raw::{c_char, c_int, c_void};
use std::sync::Mutex;

mod wire;
mod jni_types;

use jni_types::{JNIEnv, jobject, jstring, jbyteArray, jlong, jint};

/// Process-global session table. Handles are stable integers we hand back
/// to Kotlin; the actual Rust state lives behind a mutex.
static SESSIONS: Mutex<Vec<Option<Session>>> = Mutex::new(Vec::new());

/// One inference session. Owns the loaded weights + KV cache + arena.
/// Concrete fields TBD once the loader is wired up — for now this is a
/// placeholder marker so the JNI surface compiles and locks down the
/// shape Kotlin will call.
pub struct Session {
    pub model_path: String,
    // pub weights: Arc<LoadedWeights>,
    // pub kv: gemma4_model::KvCache,
    // pub arena: gemma4_core::alloc::Arena,
    // pub config: gemma4_model::Gemma4Config,
}

fn store_session(sess: Session) -> jlong {
    let mut g = SESSIONS.lock().unwrap_or_else(|e| e.into_inner());
    // Reuse a free slot if available.
    for (i, slot) in g.iter_mut().enumerate() {
        if slot.is_none() {
            *slot = Some(sess);
            return (i as jlong) + 1; // 1-based to keep 0 as "invalid"
        }
    }
    g.push(Some(sess));
    g.len() as jlong
}

fn take_session(handle: jlong) -> Option<Session> {
    let mut g = SESSIONS.lock().unwrap_or_else(|e| e.into_inner());
    let idx = (handle as usize).checked_sub(1)?;
    g.get_mut(idx).and_then(|s| s.take())
}

// ---- JNI exports ----

/// `long initNative(String modelPath, byte[] config)` — returns a session
/// handle, or 0 on failure.
#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_initNative(
    env: *mut JNIEnv,
    _cls: jobject,
    model_path: jstring,
    config: jbyteArray,
) -> jlong {
    let path = match jni_types::jstring_to_string(env, model_path) {
        Some(s) => s,
        None => return 0,
    };
    let _config_bytes = jni_types::jbytearray_to_vec(env, config);
    // TODO: parse config_bytes (msgpack), load weights, allocate KV cache.
    let sess = Session { model_path: path };
    store_session(sess)
}

/// `int sendMessageNative(long handle, byte[] msg, TokenCallback cb)` —
/// returns 0 on success, non-zero on error. Tokens are emitted to `cb`.
#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_sendMessageNative(
    _env: *mut JNIEnv,
    _cls: jobject,
    _handle: jlong,
    _msg: jbyteArray,
    _callback: jobject,
) -> jint {
    // TODO: tokenize msg, run prefill + decode loop, emit tokens via callback.
    // Returning 1 (= "not yet implemented") so the Kotlin side falls back to
    // the remote driver cleanly.
    1
}

#[no_mangle]
pub extern "C" fn Java_com_gemini_localdriver_Gemma4LocalCore_resetNative(
    _env: *mut JNIEnv,
    _cls: jobject,
    _handle: jlong,
) {
    // TODO: reset KV cache + turn history; preserve loaded weights.
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
#[allow(dead_code)]
const _: () = {
    let _: c_int = 0;
    let _: *mut c_void = core::ptr::null_mut();
    let _: *const c_char = core::ptr::null();
};
