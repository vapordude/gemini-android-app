//! Hand-rolled `JNINativeInterface_` bindings. The workspace forbids
//! external dependencies, so we declare the slots we use as function
//! pointers and fill the rest with `*mut c_void` padding sized to match
//! the JNI ABI verbatim.
//!
//! Slot indices below come from the OpenJDK `jni.h` (the same layout
//! the Android NDK ships):
//!
//! - 0..3   `reserved0..3`
//! - 4      `GetVersion`
//! - 23     `DeleteLocalRef`
//! - 31     `GetObjectClass`
//! - 33     `GetMethodID`
//! - 63     `CallVoidMethodA`
//! - 167    `NewStringUTF`
//! - 169    `GetStringUTFChars`
//! - 170    `ReleaseStringUTFChars`
//! - 171    `GetArrayLength`
//! - 200    `GetByteArrayRegion`
//!
//! All `Java_*` callers are responsible for invoking these on a thread
//! the JVM has attached and for passing valid object references.

use core::ptr;
use std::ffi::CString;
use std::os::raw::{c_char, c_void};

pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jthrowable = jobject;
pub type jarray = jobject;
pub type jbyteArray = jobject;
pub type jmethodID = *mut c_void;
pub type jlong = i64;
pub type jint = i32;
pub type jboolean = u8;
pub type jsize = i32;

/// `jvalue` is a C union of every JNI primitive plus jobject. On every
/// platform we target the union is 8 bytes (jlong / jdouble enforce the
/// alignment). Declaring it as a Rust union with both `l` and `j` ensures
/// that size invariant whether jobject is 4 or 8 bytes wide.
#[repr(C)]
pub union jvalue {
    pub l: jobject,
    pub j: jlong,
}

/// Subset of `JNINativeInterface_` we actually use. Every named field
/// sits at the slot index given in the comment; gap fillers between
/// named slots are sized to match the JNI ABI exactly.
#[repr(C)]
#[allow(non_snake_case)]
pub struct JNINativeInterface {
    // 0..3 — reserved.
    pub _reserved: [*mut c_void; 4],

    // 4 — GetVersion. We don't call it, but keep the slot.
    pub GetVersion: *mut c_void,

    // 5..22 — DefineClass through DeleteGlobalRef (18 slots).
    pub _gap_5_22: [*mut c_void; 18],

    // 23 — DeleteLocalRef.
    pub DeleteLocalRef: unsafe extern "C" fn(*mut JNIEnv, jobject),

    // 24..30 — IsSameObject through NewObjectA (7 slots).
    pub _gap_24_30: [*mut c_void; 7],

    // 31 — GetObjectClass.
    pub GetObjectClass: unsafe extern "C" fn(*mut JNIEnv, jobject) -> jclass,

    // 32 — IsInstanceOf (1 slot).
    pub _gap_32: *mut c_void,

    // 33 — GetMethodID.
    pub GetMethodID:
        unsafe extern "C" fn(*mut JNIEnv, jclass, *const c_char, *const c_char) -> jmethodID,

    // 34..62 — CallObjectMethod{,V,A} through CallVoidMethod{,V} (29 slots).
    pub _gap_34_62: [*mut c_void; 29],

    // 63 — CallVoidMethodA. The jvalue-array form is the only callable
    // shape from Rust (we can't varargs into C).
    pub CallVoidMethodA: unsafe extern "C" fn(*mut JNIEnv, jobject, jmethodID, *const jvalue),

    // 64..166 — CallNonvirtual* + GetField/SetField + GetStatic/CallStatic
    // + NewString/GetStringChars families (103 slots).
    pub _gap_64_166: [*mut c_void; 103],

    // 167 — NewStringUTF.
    pub NewStringUTF: unsafe extern "C" fn(*mut JNIEnv, *const c_char) -> jstring,

    // 168 — GetStringUTFLength (1 slot).
    pub _gap_168: *mut c_void,

    // 169 — GetStringUTFChars.
    pub GetStringUTFChars:
        unsafe extern "C" fn(*mut JNIEnv, jstring, *mut jboolean) -> *const c_char,

    // 170 — ReleaseStringUTFChars.
    pub ReleaseStringUTFChars: unsafe extern "C" fn(*mut JNIEnv, jstring, *const c_char),

    // 171 — GetArrayLength.
    pub GetArrayLength: unsafe extern "C" fn(*mut JNIEnv, jarray) -> jsize,

    // 172..199 — NewObjectArray through GetPrimitiveArrayCritical etc. (28 slots).
    pub _gap_172_199: [*mut c_void; 28],

    // 200 — GetByteArrayRegion.
    pub GetByteArrayRegion: unsafe extern "C" fn(*mut JNIEnv, jbyteArray, jsize, jsize, *mut i8),
}

#[repr(C)]
pub struct JNIEnv {
    pub functions: *mut JNINativeInterface,
}

/// Read a Java `String` into an owned Rust `String`. Returns `None` on
/// null env, null string, or a `GetStringUTFChars` failure.
pub fn jstring_to_string(env: *mut JNIEnv, s: jstring) -> Option<String> {
    if env.is_null() || s.is_null() {
        return None;
    }
    // SAFETY: env, s are valid pointers supplied by the JVM.
    unsafe {
        let funcs = (*env).functions;
        let chars = ((*funcs).GetStringUTFChars)(env, s, ptr::null_mut());
        if chars.is_null() {
            return None;
        }
        let cstr = std::ffi::CStr::from_ptr(chars);
        let owned = cstr.to_string_lossy().into_owned();
        ((*funcs).ReleaseStringUTFChars)(env, s, chars);
        Some(owned)
    }
}

/// Read a Java `byte[]` into an owned `Vec<u8>`. Returns empty on null.
pub fn jbytearray_to_vec(env: *mut JNIEnv, arr: jbyteArray) -> Vec<u8> {
    if env.is_null() || arr.is_null() {
        return Vec::new();
    }
    // SAFETY: env, arr are valid pointers supplied by the JVM.
    unsafe {
        let funcs = (*env).functions;
        let len = ((*funcs).GetArrayLength)(env, arr);
        if len <= 0 {
            return Vec::new();
        }
        let mut buf = vec![0i8; len as usize];
        ((*funcs).GetByteArrayRegion)(env, arr, 0, len, buf.as_mut_ptr());
        buf.into_iter().map(|b| b as u8).collect()
    }
}

/// Resolve `callback.<method_name>(<signature>)` to a JNI method id.
/// Returns `None` if anything fails (object null, class lookup failed,
/// method not found).
pub fn resolve_callback_method(
    env: *mut JNIEnv,
    callback: jobject,
    method_name: &str,
    signature: &str,
) -> Option<jmethodID> {
    if env.is_null() || callback.is_null() {
        return None;
    }
    let name = CString::new(method_name).ok()?;
    let sig = CString::new(signature).ok()?;
    // SAFETY: env + callback are JVM-owned valid pointers; name + sig
    // outlive the JNI call.
    unsafe {
        let funcs = (*env).functions;
        let cls = ((*funcs).GetObjectClass)(env, callback);
        if cls.is_null() {
            return None;
        }
        let mid = ((*funcs).GetMethodID)(env, cls, name.as_ptr(), sig.as_ptr());
        ((*funcs).DeleteLocalRef)(env, cls);
        if mid.is_null() {
            None
        } else {
            Some(mid)
        }
    }
}

/// Call `callback.<method>(piece)` where `piece` is a UTF-8 Rust
/// string. Creates a Java `String` (via `NewStringUTF`), invokes
/// `CallVoidMethodA`, and frees the local reference afterwards.
///
/// `mid` must be a `(Ljava/lang/String;)V` method id resolved via
/// [`resolve_callback_method`].
pub fn call_void_with_string(env: *mut JNIEnv, callback: jobject, mid: jmethodID, piece: &str) {
    if env.is_null() || callback.is_null() || mid.is_null() {
        return;
    }
    let Ok(c_piece) = CString::new(piece) else {
        // Pieces containing NUL bytes would break NewStringUTF; skip
        // those silently rather than crashing. SentencePiece doesn't
        // emit NUL inside a piece in practice.
        return;
    };
    // SAFETY: env is JVM-owned; CString lives across the call.
    unsafe {
        let funcs = (*env).functions;
        let jstr = ((*funcs).NewStringUTF)(env, c_piece.as_ptr());
        if jstr.is_null() {
            return;
        }
        let arg = jvalue { l: jstr };
        ((*funcs).CallVoidMethodA)(env, callback, mid, &arg as *const jvalue);
        ((*funcs).DeleteLocalRef)(env, jstr);
    }
}
