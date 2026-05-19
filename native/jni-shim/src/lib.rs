//! The only crate that knows about Android/JNI. Other crates build on
//! desktop for testing.
//!
//! Kotlin-side namespaces wired here:
//!
//!   nz.kaimahi.inference.NativeInference  → version + load/generate loop
//!   nz.kaimahi.agent.NativeAgent         → max-iterations stub
//!   nz.kaimahi.emdash.NativeEmdash       → client version stub
//!
//! The interesting one is `NativeInference`. The Kotlin façade owns the
//! session handle returned by `nativeLoadModel(path) -> Long` and feeds
//! it back into `nativeGenerate(handle, prompt, callback, maxTokens,
//! temperature, topK, seed)`. The callback receives each decoded piece
//! as a UTF-8 `String`.
//!
//! `nativeFreeModel` releases the session (and the dequantized weights
//! that go with it). All handles are 0-or-positive; 0 means "load
//! failed" and is never reused.

#![allow(non_snake_case)]

pub mod sessions;

#[cfg(target_os = "android")]
mod android {
    use crate::sessions::SessionTable;
    use jni::objects::{JClass, JObject, JString, JValue};
    use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
    use jni::JNIEnv;
    use model_runtime::arch::lm::gemma4::{argmax, sample, SamplerState};
    use model_runtime::{KvCache, LanguageModel, LoadedModel};
    use std::any::Any;
    use std::panic::{catch_unwind, AssertUnwindSafe};
    use std::path::Path;
    use std::ptr;
    use std::sync::atomic::Ordering;
    use std::sync::Mutex;

    /// Status codes for `nativeGenerate`. Must mirror
    /// `inference-bridge/.../NativeInference.kt::Status`.
    pub(crate) const STATUS_OK: jint = 0;
    pub(crate) const STATUS_INVALID_HANDLE: jint = 1;
    pub(crate) const STATUS_NO_TOKENIZER: jint = 2;
    pub(crate) const STATUS_EMPTY_PROMPT: jint = 3;
    pub(crate) const STATUS_INTERNAL: jint = 4;

    /// Extract a human-readable message from the payload yielded by a
    /// panic caught with [`std::panic::catch_unwind`].
    fn panic_message(payload: &(dyn Any + Send)) -> String {
        if let Some(s) = payload.downcast_ref::<&'static str>() {
            (*s).to_string()
        } else if let Some(s) = payload.downcast_ref::<String>() {
            s.clone()
        } else {
            "<panic with non-string payload>".to_string()
        }
    }

    /// Wrap an `extern "system"` JNI entry's body in `catch_unwind`.
    /// A Rust panic that unwinds across the FFI boundary into the JVM is
    /// undefined behaviour — typically a process crash, sometimes worse.
    /// `ffi_guard!` traps any panic raised by the body, records the
    /// message via [`set_last_error`], and returns the supplied default
    /// sentinel so the Kotlin façade sees a clean failure status it can
    /// inspect with `nativeLastError()`.
    ///
    /// Use `STATUS_INTERNAL` for `jint` entries, `0` for `jlong`
    /// entries (the "load failed" handle), `ptr::null_mut()` for
    /// `jstring`, and `()` for `void`.
    macro_rules! ffi_guard {
        ($default:expr, $body:block) => {{
            match catch_unwind(AssertUnwindSafe(move || $body)) {
                Ok(v) => v,
                Err(payload) => {
                    set_last_error(format!("panic across FFI: {}", panic_message(&*payload)));
                    $default
                }
            }
        }};
    }

    /// One loaded inference session. We hold the model + its KV cache
    /// + a sampler RNG so per-token state isn't shared across handles.
    ///
    /// Cancellation does NOT live in this struct — it lives on the
    /// table slot (see [`crate::sessions::Slot`]) so a concurrent
    /// `take()` removing the session doesn't drop the cancel signal
    /// on the floor for an in-flight generate.
    struct Session {
        model: Box<dyn LanguageModel>,
        kv: KvCache,
        tokenizer: Option<model_runtime::tokenizer::Tokenizer>,
        rng: SamplerState,
        #[allow(dead_code)] // kept for diag dumps + future logging
        arch_tag: String,
    }

    static SESSIONS: SessionTable<Session> = SessionTable::new();

    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeVersion<'a>(
        env: JNIEnv<'a>,
        _class: JClass<'a>,
    ) -> jstring {
        ffi_guard!(ptr::null_mut(), {
            match env.new_string(env!("CARGO_PKG_VERSION")) {
                Ok(j) => j.into_raw(),
                Err(_) => {
                    set_last_error("nativeVersion: JString alloc failed".to_string());
                    ptr::null_mut()
                }
            }
        })
    }

    /// Probe a model file for its arch tag without loading weights.
    /// Returns "unknown" on any error so the Kotlin side can still
    /// render a label.
    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeProbe<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass<'a>,
        path: JString<'a>,
    ) -> jstring {
        ffi_guard!(ptr::null_mut(), {
            let path_s: String = env.get_string(&path).map(Into::into).unwrap_or_default();
            let tag = match model_runtime::probe(Path::new(&path_s)) {
                Ok(_cfg) => infer_arch_tag(&path_s),
                Err(_) => "unknown".to_string(),
            };
            match env.new_string(&tag) {
                Ok(j) => j.into_raw(),
                Err(_) => {
                    set_last_error(format!("nativeProbe: JString alloc failed for tag='{tag}'"));
                    ptr::null_mut()
                }
            }
        })
    }

    fn infer_arch_tag(path: &str) -> String {
        // Re-read the metadata only path so we report what the file
        // actually declares, not what the filename hints.
        if let Ok(gguf) = gguf_loader::read(Path::new(path)) {
            return gguf.arch_tag().unwrap_or("unknown").to_string();
        }
        "unknown".to_string()
    }

    /// Backwards-compat name kept for the Kotlin façade's older bindings.
    /// Same behaviour as `nativeProbe`: returns the arch tag.
    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeLoadModel<'a>(
        env: JNIEnv<'a>,
        cls: JClass<'a>,
        path: JString<'a>,
    ) -> jstring {
        ffi_guard!(ptr::null_mut(), {
            Java_nz_kaimahi_inference_NativeInference_nativeProbe(env, cls, path)
        })
    }

    /// Load a model with weights. Returns a positive handle on success or
    /// `0` on failure (with an error string published via the static
    /// `lastError` slot, accessible from Kotlin through
    /// `nativeLastError`).
    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeOpenSession<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass<'a>,
        path: JString<'a>,
    ) -> jlong {
        ffi_guard!(0, {
            let path_s: String = match env.get_string(&path) {
                Ok(s) => s.into(),
                Err(_) => return 0,
            };
            let loaded = match model_runtime::load(Path::new(&path_s)) {
                Ok(m) => m,
                Err(e) => {
                    set_last_error(format!("load: {e:?}"));
                    return 0;
                }
            };
            let model = match loaded {
                LoadedModel::Language(m) => m,
                LoadedModel::Image(_) => {
                    set_last_error("expected language model, got image model".to_string());
                    return 0;
                }
            };
            // The tokenizer is shared by every step of every session — we
            // re-read it here from the same file (cheap; GGUF metadata is
            // already parsed).
            let tokenizer = match gguf_loader::read(Path::new(&path_s))
                .ok()
                .and_then(|g| model_runtime::tokenizer::Tokenizer::from_gguf(&g).ok())
            {
                Some(t) => Some(t),
                None => {
                    // Missing vocab → the model is unusable for text gen.
                    set_last_error("tokenizer.ggml.tokens missing in GGUF".to_string());
                    None
                }
            };
            let arch_tag = model.info().arch_tag.clone();
            let kv = KvCache::new();
            let sess = Session {
                model,
                kv,
                tokenizer,
                rng: SamplerState::new(0),
                arch_tag,
            };
            SESSIONS.store(sess)
        })
    }

    /// Run autoregressive decode for `prompt`. `callback` must expose a
    /// method `fun onToken(piece: String)` — we look it up by signature
    /// once and call it per generated piece.
    ///
    /// Returns 0 on success, or a non-zero status code on failure (see
    /// constants below).
    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeGenerate<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass<'a>,
        handle: jlong,
        prompt: JString<'a>,
        callback: JObject<'a>,
        max_tokens: jint,
        temperature: jfloat,
        top_k: jint,
        seed: jlong,
    ) -> jint {
        ffi_guard!(STATUS_INTERNAL, {
            let prompt_s: String = match env.get_string(&prompt) {
                Ok(s) => s.into(),
                Err(_) => return STATUS_EMPTY_PROMPT,
            };
            if prompt_s.is_empty() {
                return STATUS_EMPTY_PROMPT;
            }

            // Resolve onToken(String) once.
            let cb_class = match env.get_object_class(&callback) {
                Ok(c) => c,
                Err(_) => return STATUS_INTERNAL,
            };
            let method = match env.get_method_id(&cb_class, "onToken", "(Ljava/lang/String;)V") {
                Ok(m) => m,
                Err(_) => return STATUS_INTERNAL,
            };

            // Cancel Arc lives at the slot level — clone it out under
            // the table mutex, then release the lock. If close races
            // and removes the session before generation finishes, the
            // Arc we hold here is still observable to any concurrent
            // `nativeRequestCancel` that already cloned the same Arc.
            //
            // Reset is atomic with the clone (single lock acquisition):
            // a concurrent `nativeRequestCancel` landing between a
            // separate clone-then-reset pair would have its store(true)
            // immediately wiped. `reset_and_clone_cancel` closes that
            // window.
            let cancel = match SESSIONS.reset_and_clone_cancel(handle) {
                Some(c) => c,
                None => return STATUS_INVALID_HANDLE,
            };

            // Pull tokenizer + token ids out of the session under the lock
            // so we can release the lock for the long generation loop.
            let (prompt_ids, eos_id) = match SESSIONS.with(handle, |sess| {
                let tok = sess.tokenizer.as_ref()?;
                let prompt_ids = tok.encode(&prompt_s);
                Some((prompt_ids, tok.eos_id))
            }) {
                Some(Some(v)) => v,
                Some(None) => return STATUS_NO_TOKENIZER,
                None => return STATUS_INVALID_HANDLE,
            };
            if prompt_ids.is_empty() {
                return STATUS_EMPTY_PROMPT;
            }

            // Reseed if requested.
            if seed != 0
                && SESSIONS
                    .with(handle, |sess| sess.rng = SamplerState::new(seed as u64))
                    .is_none()
            {
                return STATUS_INVALID_HANDLE;
            }

            // Prefill: forward every prompt token EXCEPT the last. The
            // final prompt token becomes `last_token` and is forwarded
            // once at the start of the decode loop, where its logits
            // are sampled for the first generated token.
            //
            // Forwarding the final prompt token in BOTH the prefill and
            // the decode loop would advance the model's KV cache an
            // extra position for that token (it'd sit twice at adjacent
            // positions), and the first sample would attend to both
            // copies — biasing generation toward repeating that token.
            //
            // If the session goes away mid-prefill (concurrent close)
            // bail with INVALID_HANDLE rather than silently no-op and
            // proceed to decode with stale state.
            let mut last_token: u32 = *prompt_ids
                .last()
                .expect("prompt_ids non-empty: checked above");
            let prefill_count = prompt_ids.len() - 1;
            for &id in &prompt_ids[..prefill_count] {
                if cancel.load(Ordering::Relaxed) {
                    return STATUS_OK;
                }
                let stepped = SESSIONS.with(handle, |sess| {
                    let _ = sess.model.forward(id, &mut sess.kv);
                });
                if stepped.is_none() {
                    return STATUS_INVALID_HANDLE;
                }
            }

            let max_new = max_tokens.max(1) as usize;
            let temp = temperature.max(0.0);
            let k = top_k.max(1) as usize;

            // Decode loop. We sample using the most recent forward()'s
            // logits, then feed the sampled token back as the next input.
            for _ in 0..max_new {
                // Cooperative cancellation point. Cheap atomic load per
                // token; the Kotlin side flips this via
                // `nativeRequestCancel(handle)` when the collecting
                // coroutine is cancelled (app backgrounded, user
                // closed chat, model swap requested, etc).
                if cancel.load(Ordering::Relaxed) {
                    break;
                }
                let next_id = match SESSIONS.with(handle, |sess| {
                    let logits = sess.model.forward(last_token, &mut sess.kv);
                    if temp <= 0.0 {
                        argmax(logits)
                    } else {
                        sample(logits, temp, k, &mut sess.rng)
                    }
                }) {
                    Some(id) => id,
                    None => return STATUS_INVALID_HANDLE,
                };
                last_token = next_id;
                // Stop on EOS / any other structural token (turn boundary,
                // image placeholder, pad, unknown). These shouldn't appear
                // in the streamed text — they're either end-of-response
                // markers or signs the model has gone off the rails.
                // Either way, don't emit them to the UI and stop decoding.
                let is_structural = match SESSIONS.with(handle, |sess| {
                    sess.tokenizer.as_ref().map(|t| t.is_special(next_id))
                }) {
                    Some(Some(b)) => b,
                    Some(None) => return STATUS_NO_TOKENIZER,
                    None => return STATUS_INVALID_HANDLE,
                };
                if is_structural || eos_id == Some(next_id) {
                    break;
                }
                // Decode the single new token as a streaming piece. We use
                // `decode_piece` (not `decode(&[next_id])`) because the bulk
                // decoder strips one leading space, which would collapse the
                // space between every word in streamed output —
                // `▁hello ▁world` → `helloworld` instead of `hello world`.
                let piece = match SESSIONS.with(handle, |sess| {
                    sess.tokenizer.as_ref().map(|t| t.decode_piece(next_id))
                }) {
                    Some(Some(p)) => p,
                    Some(None) => return STATUS_NO_TOKENIZER,
                    None => return STATUS_INVALID_HANDLE,
                };
                // Emit the piece.
                let jpiece = match env.new_string(&piece) {
                    Ok(j) => j,
                    Err(_) => return STATUS_INTERNAL,
                };
                // SAFETY: callable_method validated above; signature matches.
                let _ = unsafe {
                    env.call_method_unchecked(
                        &callback,
                        method,
                        jni::signature::ReturnType::Primitive(jni::signature::Primitive::Void),
                        &[JValue::Object(&JObject::from(jpiece)).as_jni()],
                    )
                };
            }
            STATUS_OK
        })
    }

    /// Request an in-flight `nativeGenerate` to stop at the next
    /// token. Sets a cheap atomic flag the decode loop polls each
    /// iteration. Safe to call from any thread and from outside an
    /// active generate — the flag is reset at the start of every
    /// `nativeGenerate`.
    ///
    /// Clones the cancel Arc out from under the table mutex first,
    /// then releases the lock before storing. This means a concurrent
    /// `nativeCloseSession` can race with this cancel without losing
    /// the signal: if cancel wins, the Arc is still observable to any
    /// generate that already cloned it; if close wins, this returns a
    /// stale handle and the cancel is correctly a no-op.
    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeRequestCancel<'a>(
        _env: JNIEnv<'a>,
        _class: JClass<'a>,
        handle: jlong,
    ) {
        ffi_guard!((), {
            if let Some(cancel) = SESSIONS.clone_cancel(handle) {
                cancel.store(true, Ordering::Relaxed);
            }
        })
    }

    /// Reset the per-session KV cache so the next `nativeGenerate`
    /// starts a fresh conversation.
    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeResetSession<'a>(
        _env: JNIEnv<'a>,
        _class: JClass<'a>,
        handle: jlong,
    ) {
        ffi_guard!((), {
            let _ = SESSIONS.with(handle, |sess| sess.model.reset(&mut sess.kv));
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeCloseSession<'a>(
        _env: JNIEnv<'a>,
        _class: JClass<'a>,
        handle: jlong,
    ) {
        ffi_guard!((), {
            drop(SESSIONS.take(handle));
        })
    }

    /// True iff `mlock` succeeded for the model's backing mmap. On
    /// unprivileged Android this is almost always `false`; the chat
    /// UI uses it to display `pinned` vs `reclaimable`.
    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeIsPinned<'a>(
        _env: JNIEnv<'a>,
        _class: JClass<'a>,
        handle: jlong,
    ) -> jboolean {
        ffi_guard!(JNI_FALSE, {
            let pinned = SESSIONS
                .with(handle, |sess| sess.model.info().mmap_pinned)
                .unwrap_or(false);
            if pinned {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_inference_NativeInference_nativeLastError<'a>(
        env: JNIEnv<'a>,
        _class: JClass<'a>,
    ) -> jstring {
        ffi_guard!(ptr::null_mut(), {
            let s = LAST_ERROR
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
                .unwrap_or_default();
            // If JString allocation itself fails (likely JVM OOM) we
            // cannot record a new error via set_last_error and re-emit
            // through nativeLastError — that would recurse on the same
            // failure mode. Just return null and let the caller see
            // "(no last error)" via the stale slot.
            match env.new_string(&s) {
                Ok(j) => j.into_raw(),
                Err(_) => ptr::null_mut(),
            }
        })
    }

    static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);

    fn set_last_error(s: String) {
        *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = Some(s);
    }

    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_agent_NativeAgent_nativeMaxIterations<'a>(
        _env: JNIEnv<'a>,
        _class: JClass<'a>,
    ) -> jint {
        ffi_guard!(0, { 50 })
    }

    #[no_mangle]
    pub extern "system" fn Java_nz_kaimahi_emdash_NativeEmdash_nativeClientVersion<'a>(
        env: JNIEnv<'a>,
        _class: JClass<'a>,
    ) -> jstring {
        ffi_guard!(ptr::null_mut(), {
            match env.new_string(env!("CARGO_PKG_VERSION")) {
                Ok(j) => j.into_raw(),
                Err(_) => {
                    set_last_error("nativeClientVersion: JString alloc failed".to_string());
                    ptr::null_mut()
                }
            }
        })
    }
}

#[cfg(not(target_os = "android"))]
pub fn host_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
