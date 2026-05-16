//! Hand-rolled JNI vtable bindings. We only need a small subset of the
//! `JNINativeInterface` so we declare just those function pointers; the rest
//! of the vtable is ignored.
//!
//! References:
//! - JNI specification, "JNINativeInterface" struct layout
//! - Android NDK's `jni.h` header
//!
//! All functions here are `unsafe` because they dereference raw pointers
//! supplied by the JVM. Caller (the exported `Java_*` symbols) must
//! invoke them from a thread the JVM has attached.

use std::os::raw::{c_char, c_void};

pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jbyteArray = jobject;
pub type jlong = i64;
pub type jint = i32;
pub type jboolean = u8;
pub type jsize = i32;

/// Minimal subset of `JNINativeInterface` that we use.
#[repr(C)]
pub struct JNINativeInterface {
    _reserved: [*mut c_void; 4],

    // 4..27 are version / class-loader / object operations we don't use.
    _unused1: [*mut c_void; 165],

    // GetStringUTFChars at index 169 in the table.
    pub GetStringUTFChars:
        unsafe extern "C" fn(*mut JNIEnv, jstring, *mut jboolean) -> *const c_char,
    pub ReleaseStringUTFChars:
        unsafe extern "C" fn(*mut JNIEnv, jstring, *const c_char),

    // GetArrayLength at index 171.
    pub GetArrayLength: unsafe extern "C" fn(*mut JNIEnv, jobject) -> jsize,

    _unused2: [*mut c_void; 14],

    // GetByteArrayRegion at index 199.
    pub GetByteArrayRegion:
        unsafe extern "C" fn(*mut JNIEnv, jbyteArray, jsize, jsize, *mut i8),
}

#[repr(C)]
pub struct JNIEnv {
    pub functions: *mut JNINativeInterface,
}

/// Read a Java `String` into an owned Rust `String`. Returns `None` on null.
pub fn jstring_to_string(env: *mut JNIEnv, s: jstring) -> Option<String> {
    if env.is_null() || s.is_null() { return None; }
    // SAFETY: env, s are valid pointers as supplied by the JVM.
    unsafe {
        let funcs = (*env).functions;
        let chars = ((*funcs).GetStringUTFChars)(env, s, core::ptr::null_mut());
        if chars.is_null() { return None; }
        let cstr = std::ffi::CStr::from_ptr(chars);
        let owned = cstr.to_string_lossy().into_owned();
        ((*funcs).ReleaseStringUTFChars)(env, s, chars);
        Some(owned)
    }
}

/// Read a Java `byte[]` into an owned `Vec<u8>`. Returns empty on null.
pub fn jbytearray_to_vec(env: *mut JNIEnv, arr: jbyteArray) -> Vec<u8> {
    if env.is_null() || arr.is_null() { return Vec::new(); }
    // SAFETY: env, arr are valid pointers as supplied by the JVM.
    unsafe {
        let funcs = (*env).functions;
        let len = ((*funcs).GetArrayLength)(env, arr);
        if len <= 0 { return Vec::new(); }
        let mut buf = vec![0i8; len as usize];
        ((*funcs).GetByteArrayRegion)(env, arr, 0, len, buf.as_mut_ptr());
        buf.into_iter().map(|b| b as u8).collect()
    }
}
