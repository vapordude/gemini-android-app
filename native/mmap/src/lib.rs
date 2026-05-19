//! Read-only mmap over weight files. Raw libc FFI — no `memmap2`, no `libc`.
//!
//! Always-attempt `mlock` so the kernel is asked to pin our weights resident.
//! On unprivileged Android `RLIMIT_MEMLOCK` is tiny (64 KiB on most builds)
//! and the call EPERMs silently — `pinned()` reports the truth either way so
//! the UI chip can show `pinned` vs `reclaimable`. Always `madvise(WILLNEED)`
//! regardless of the mlock outcome so first-token latency doesn't pay for
//! every cold page on its own.

#![deny(unsafe_op_in_unsafe_fn)]

use core::ptr::NonNull;
use std::ffi::CString;
use std::io;
use std::os::raw::{c_char, c_int, c_void};
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

const O_RDONLY: c_int = 0;
const PROT_READ: c_int = 1;
const MAP_PRIVATE: c_int = 0x02;
const MAP_FAILED: *mut c_void = !0usize as *mut c_void;

const SEEK_END: c_int = 2;
const MADV_WILLNEED: c_int = 3;

extern "C" {
    fn open(path: *const c_char, flags: c_int) -> c_int;
    fn close(fd: c_int) -> c_int;
    fn lseek(fd: c_int, offset: i64, whence: c_int) -> i64;
    fn mmap(
        addr: *mut c_void,
        len: usize,
        prot: c_int,
        flags: c_int,
        fd: c_int,
        offset: i64,
    ) -> *mut c_void;
    fn munmap(addr: *mut c_void, len: usize) -> c_int;
    fn mlock(addr: *const c_void, len: usize) -> c_int;
    fn madvise(addr: *mut c_void, len: usize, advice: c_int) -> c_int;
}

pub struct FileMmap {
    base: NonNull<u8>,
    len: usize,
    pinned: bool,
}

impl FileMmap {
    pub fn open(path: &Path) -> io::Result<Self> {
        // Use raw OS bytes (not `to_string_lossy`) so non-UTF-8 filenames
        // on Unix pass through unchanged. Android filesystem paths are
        // ASCII/UTF-8 in practice, but lossy conversion would silently
        // corrupt any non-UTF-8 sequence with U+FFFD.
        let c_path = CString::new(path.as_os_str().as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path contains NUL byte"))?;
        // SAFETY: c_path is a valid NUL-terminated string for `open`.
        let fd = unsafe { open(c_path.as_ptr(), O_RDONLY) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: fd is a valid descriptor returned by open() above.
        let size = unsafe { lseek(fd, 0, SEEK_END) };
        if size <= 0 {
            // SAFETY: closing a valid fd; ignore the result.
            unsafe { close(fd) };
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "empty or unseekable file",
            ));
        }
        // SAFETY: PROT_READ mapping over a valid fd; caller must not write.
        let p = unsafe {
            mmap(
                core::ptr::null_mut(),
                size as usize,
                PROT_READ,
                MAP_PRIVATE,
                fd,
                0,
            )
        };
        // SAFETY: fd was valid; mmap keeps its own reference to the file.
        unsafe { close(fd) };
        if p == MAP_FAILED {
            return Err(io::Error::last_os_error());
        }
        let base = NonNull::new(p as *mut u8)
            .ok_or_else(|| io::Error::other("mmap returned null with non-FAILED"))?;
        let len = size as usize;
        // SAFETY: base/len are the freshly-mmapped region. madvise is advisory;
        // we ignore its return.
        unsafe {
            madvise(base.as_ptr() as *mut c_void, len, MADV_WILLNEED);
        }
        // SAFETY: same region. mlock may EPERM/ENOMEM on Android — that's
        // expected, just record the boolean.
        let pinned = unsafe { mlock(base.as_ptr() as *const c_void, len) } == 0;
        Ok(FileMmap { base, len, pinned })
    }

    pub fn as_slice(&self) -> &[u8] {
        // SAFETY: pointer is owned by us, mapping size is recorded, read-only.
        unsafe { core::slice::from_raw_parts(self.base.as_ptr(), self.len) }
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// True iff the `mlock` succeeded at open time. On stock Android this
    /// is almost always `false` (unprivileged `RLIMIT_MEMLOCK` is 64 KiB);
    /// the UI uses this to show `pinned` vs `reclaimable`.
    pub fn pinned(&self) -> bool {
        self.pinned
    }
}

impl Drop for FileMmap {
    fn drop(&mut self) {
        // SAFETY: matching call to mmap. munmap implicitly drops mlock.
        unsafe {
            munmap(self.base.as_ptr() as *mut c_void, self.len);
        }
    }
}

// SAFETY: the mapped region is read-only; the underlying pointer is stable
// for the lifetime of `self` and never aliased mutably.
unsafe impl Send for FileMmap {}
unsafe impl Sync for FileMmap {}
