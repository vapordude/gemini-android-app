//! Arena allocator backed by anonymous `mmap`. Resets per token in decode;
//! per chunk in prefill. Single-threaded — caller ensures no overlap.
//!
//! Why an arena: per-token forward emits ~50 transient tensors (qkv, attn
//! scores, attn out, mlp gate, up, down, post-norm scratch …). With a GC'd
//! `Vec` per tensor we'd thrash the allocator on every token. With an arena,
//! the whole batch frees in O(1).

use core::ptr::NonNull;
use std::os::raw::{c_int, c_void};

const PROT_READ: c_int = 1;
const PROT_WRITE: c_int = 2;
const MAP_PRIVATE: c_int = 0x02;
const MAP_ANONYMOUS: c_int = 0x20;
const MAP_FAILED: *mut c_void = !0usize as *mut c_void;

extern "C" {
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

/// Bump arena. 64-byte aligned slots; reset() rewinds the cursor without
/// touching pages so subsequent allocations are first-page-fault-free.
pub struct Arena {
    base: NonNull<u8>,
    capacity: usize,
    cursor: usize,
}

impl Arena {
    /// Reserve [capacity] bytes from the OS. Rounded up to a multiple of the
    /// page size (4 KiB on Android) by `mmap`. Returns `None` if the kernel
    /// rejects the request.
    pub fn new(capacity: usize) -> Option<Self> {
        // SAFETY: anonymous mapping doesn't read uninit memory until we write.
        let p = unsafe {
            mmap(
                core::ptr::null_mut(),
                capacity,
                PROT_READ | PROT_WRITE,
                MAP_PRIVATE | MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        if p == MAP_FAILED { return None; }
        let base = NonNull::new(p as *mut u8)?;
        Some(Arena { base, capacity, cursor: 0 })
    }

    /// Allocate [size] bytes aligned to 64 (cache-line). Panics if the
    /// arena is full — caller is expected to size the arena above the
    /// peak working-set of one forward pass.
    pub fn alloc_raw(&mut self, size: usize) -> *mut u8 {
        let aligned = (self.cursor + 63) & !63;
        let end = aligned.checked_add(size).expect("arena overflow");
        assert!(end <= self.capacity, "arena exhausted: need {end}, capacity {}", self.capacity);
        // SAFETY: pointer is inside the mmap'd region.
        let p = unsafe { self.base.as_ptr().add(aligned) };
        self.cursor = end;
        p
    }

    /// Allocate a slice of `T`. Zero-initialized iff `T: Default` and caller
    /// loops through. We do NOT zero the memory here — the kernels overwrite
    /// every element they read so there's no UB, and zeroing is the most
    /// expensive part of allocation in profile traces.
    pub fn alloc_slice<T>(&mut self, count: usize) -> &mut [T] {
        let size = count * core::mem::size_of::<T>();
        let p = self.alloc_raw(size) as *mut T;
        // SAFETY: pointer + count form a valid slice within the arena.
        unsafe { core::slice::from_raw_parts_mut(p, count) }
    }

    /// Rewind to position [mark]. Save with [Self::mark]. Lets nested forwards
    /// share an arena without permanent growth.
    pub fn reset_to(&mut self, mark: usize) {
        debug_assert!(mark <= self.cursor);
        self.cursor = mark;
    }

    pub fn mark(&self) -> usize { self.cursor }

    pub fn reset(&mut self) { self.cursor = 0; }

    pub fn used(&self) -> usize { self.cursor }
    pub fn capacity(&self) -> usize { self.capacity }
}

impl Drop for Arena {
    fn drop(&mut self) {
        // SAFETY: we own the mapping; munmap is the matching call.
        unsafe { munmap(self.base.as_ptr() as *mut c_void, self.capacity); }
    }
}

// Arena is single-threaded by design (no atomic cursor). Send is safe because
// the underlying memory is owned and not shared; Sync is NOT implemented.
unsafe impl Send for Arena {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arena_allocates_and_resets() {
        let mut a = Arena::new(4096).expect("mmap should succeed in tests");
        let s: &mut [u32] = a.alloc_slice(64);
        for (i, v) in s.iter_mut().enumerate() { *v = i as u32; }
        assert_eq!(s[63], 63);
        let mark = a.mark();
        let _t: &mut [u8] = a.alloc_slice(32);
        assert!(a.used() > mark);
        a.reset_to(mark);
        assert_eq!(a.used(), mark);
        a.reset();
        assert_eq!(a.used(), 0);
    }

    #[test]
    fn arena_aligns_to_64() {
        let mut a = Arena::new(4096).expect("mmap should succeed in tests");
        let s: &mut [u8] = a.alloc_slice(1);
        let p = s.as_ptr() as usize;
        assert_eq!(p % 64, 0);
        let s2: &mut [u8] = a.alloc_slice(1);
        let p2 = s2.as_ptr() as usize;
        assert_eq!(p2 % 64, 0);
    }
}
