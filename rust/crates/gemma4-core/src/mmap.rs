//! Read-only mmap over weight files. Raw libc FFI — no `memmap2`, no `libc`.

use core::ptr::NonNull;
use std::ffi::CString;
use std::os::raw::{c_int, c_void};
use std::path::Path;

const O_RDONLY: c_int = 0;
const PROT_READ: c_int = 1;
const MAP_PRIVATE: c_int = 0x02;
const MAP_FAILED: *mut c_void = !0usize as *mut c_void;

extern "C" {
    fn open(path: *const i8, flags: c_int) -> c_int;
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
}

const SEEK_END: c_int = 2;

pub struct FileMmap {
    base: NonNull<u8>,
    len: usize,
}

impl FileMmap {
    pub fn open(path: &Path) -> Result<Self, &'static str> {
        let c_path = CString::new(path.as_os_str().to_string_lossy().as_bytes())
            .map_err(|_| "path contains NUL byte")?;
        // SAFETY: c_path is a valid NUL-terminated string for `open`.
        let fd = unsafe { open(c_path.as_ptr() as *const i8, O_RDONLY) };
        if fd < 0 { return Err("open failed"); }
        // Discover size by seeking to end.
        // SAFETY: fd is a valid descriptor returned by open() above.
        let size = unsafe { lseek(fd, 0, SEEK_END) };
        if size <= 0 {
            // SAFETY: closing a valid fd; ignore the result.
            unsafe { close(fd); }
            return Err("empty or unseekable file");
        }
        // SAFETY: PROT_READ mapping over a valid fd; caller must not write.
        let p = unsafe {
            mmap(core::ptr::null_mut(), size as usize, PROT_READ, MAP_PRIVATE, fd, 0)
        };
        // SAFETY: fd was valid; mmap keeps its own reference to the file.
        unsafe { close(fd); }
        if p == MAP_FAILED { return Err("mmap failed"); }
        let base = NonNull::new(p as *mut u8).ok_or("mmap returned null")?;
        Ok(FileMmap { base, len: size as usize })
    }

    pub fn as_slice(&self) -> &[u8] {
        // SAFETY: pointer is owned by us, mapping size is recorded, read-only.
        unsafe { core::slice::from_raw_parts(self.base.as_ptr(), self.len) }
    }

    pub fn len(&self) -> usize { self.len }
    pub fn is_empty(&self) -> bool { self.len == 0 }
}

impl Drop for FileMmap {
    fn drop(&mut self) {
        // SAFETY: matching call to mmap.
        unsafe { munmap(self.base.as_ptr() as *mut c_void, self.len); }
    }
}

unsafe impl Send for FileMmap {}
unsafe impl Sync for FileMmap {}
