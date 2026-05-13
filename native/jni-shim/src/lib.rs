//! The only crate that knows about Android/JNI. Other crates build on
//! desktop for testing.
//!
//! Three separate JNI namespaces:
//!   Java_com_gemini_inference_*
//!   Java_com_gemini_agent_*
//!   Java_com_gemini_emdash_*
//!
//! Plus an optional diagnostics namespace when built with `--features diag`.
//!
//! v0: the namespaces exist with stub returns so the Kotlin side can call
//! them without `UnsatisfiedLinkError`.

#![allow(non_snake_case)]

#[cfg(target_os = "android")]
mod android {
    use jni::objects::{JClass, JString};
    use jni::sys::{jint, jstring};
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_com_gemini_inference_NativeInference_nativeVersion<'a>(
        env: JNIEnv<'a>,
        _class: JClass<'a>,
    ) -> jstring {
        env.new_string("0.1.0").unwrap().into_raw()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_gemini_inference_NativeInference_nativeLoadModel<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass<'a>,
        path: JString<'a>,
    ) -> jstring {
        let path: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
        let arch_tag = std::path::Path::new(&path)
            .file_name()
            .and_then(|s| s.to_str())
            .map(|s| {
                if s.contains("gemma") {
                    "gemma4"
                } else {
                    "unknown"
                }
            })
            .unwrap_or("unknown");
        env.new_string(arch_tag).unwrap().into_raw()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_gemini_agent_NativeAgent_nativeMaxIterations<'a>(
        _env: JNIEnv<'a>,
        _class: JClass<'a>,
    ) -> jint {
        50
    }

    #[no_mangle]
    pub extern "system" fn Java_com_gemini_emdash_NativeEmdash_nativeClientVersion<'a>(
        env: JNIEnv<'a>,
        _class: JClass<'a>,
    ) -> jstring {
        env.new_string("0.1.0").unwrap().into_raw()
    }
}

#[cfg(not(target_os = "android"))]
pub fn host_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
