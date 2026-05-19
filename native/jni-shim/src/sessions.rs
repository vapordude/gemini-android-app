//! Generation-counted slot table for the JNI session map.
//!
//! Lives at the crate root so it builds on both Android and host (the
//! `mod android` is target-gated; the slot table itself is not). This
//! lets us unit-test the lifecycle invariants on desktop where the JNI
//! types and the loaded model are unavailable.
//!
//! ## What this fixes
//!
//! Two earlier bugs lived in the slot table:
//!
//! 1. **Stale-handle-hits-new-session.** Handles were `slot_idx + 1`
//!    with no generation counter. After a slot was emptied and reused
//!    by a new session, any lingering Kotlin-side handle from before
//!    the swap would silently operate on the new session.
//!
//! 2. **Cancel races with close.** The cancel `Arc<AtomicBool>` lived
//!    inside the session struct itself. If a concurrent `take()`
//!    removed the session before a cancel could fire, the cancel was
//!    silently lost — even though an in-flight generate was holding
//!    its own clone of the same Arc.
//!
//! Both are addressed by the [`SessionTable`] design here:
//!
//!  - Handles encode `(slot_idx, generation)` as a single `i64`. Every
//!    `store()` bumps the slot's generation, so old handles for that
//!    slot stop matching the moment the slot is reused.
//!  - Each slot holds its own `Arc<AtomicBool>` for cancellation,
//!    distinct from any cancel Arc cloned by an in-flight generate.
//!    [`clone_cancel`](SessionTable::clone_cancel) hands out a clone
//!    after a gen-check; the cancel signal reaches both the current
//!    session (if any) and any generate that already cloned the Arc
//!    before the close raced in.
//!
//! ## Handle encoding
//!
//! `i64` layout (signed but treated as two `u32` halves):
//!
//! ```text
//!   high 32 bits = generation (u32, wraps with wrapping_add)
//!   low  32 bits = slot_idx + 1 (u32, so 0 stays the "load failed" sentinel)
//! ```
//!
//! A whole-word zero handle never matches any slot (no slot has both
//! `idx = -1` and `gen = 0`), which preserves the existing invariant
//! that the Kotlin façade treats `0` as "load failed."

use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

/// Opaque handle type. Mirrors `jni::sys::jlong` so the Android JNI
/// entries can pass values through unchanged. We use a plain `i64`
/// here so this module compiles on host.
pub type Handle = i64;

/// Per-slot state. `cancel` lives at the slot level (not inside `T`)
/// so cancellation survives `take()` removing the inner session.
pub struct Slot<T> {
    pub sess: Option<T>,
    pub gen: u32,
    pub cancel: Arc<AtomicBool>,
}

impl<T> Slot<T> {
    fn empty() -> Self {
        Self {
            sess: None,
            gen: 0,
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }
}

/// Generation-counted slot table for inference sessions.
///
/// The single public method group is `store` / `take` / `with` /
/// `clone_cancel`. All four operate on the same mutex; lock acquisition
/// is brief in each.
pub struct SessionTable<T> {
    slots: Mutex<Vec<Slot<T>>>,
}

impl<T> SessionTable<T> {
    pub const fn new() -> Self {
        Self {
            slots: Mutex::new(Vec::new()),
        }
    }

    /// Insert a session. Bumps the chosen slot's generation, swaps in
    /// a fresh `cancel` Arc, and returns a handle encoding the new
    /// `(slot, gen)`. Old handles for the same slot stop matching.
    pub fn store(&self, sess: T) -> Handle {
        let mut g = self.slots.lock().unwrap_or_else(|e| e.into_inner());
        for (i, slot) in g.iter_mut().enumerate() {
            if slot.sess.is_none() {
                // Bump on store. A stale handle minted before this
                // swap will fail the gen-check; any in-flight generate
                // for the OLD session already cloned its own cancel
                // Arc (via clone_cancel) — replacing the slot's cancel
                // Arc here doesn't poison it.
                slot.gen = slot.gen.wrapping_add(1);
                slot.cancel = Arc::new(AtomicBool::new(false));
                slot.sess = Some(sess);
                return encode(i, slot.gen);
            }
        }
        // No empty slot — push a new one. Generation starts at 1 so
        // a freshly-pushed slot is distinct from the all-zero handle.
        let mut slot = Slot::empty();
        slot.gen = 1;
        slot.sess = Some(sess);
        g.push(slot);
        encode(g.len() - 1, 1)
    }

    /// Remove and return the session for `handle`, or `None` if the
    /// handle is stale / out of range / already empty.
    ///
    /// The slot's generation is **not** bumped here. The next
    /// `store()` for this slot does that bump. Bumping on both `take`
    /// and `store` would double-bump and complicate the wrap analysis
    /// for no behavioural benefit.
    pub fn take(&self, handle: Handle) -> Option<T> {
        let (idx, gen) = decode(handle)?;
        let mut g = self.slots.lock().unwrap_or_else(|e| e.into_inner());
        let slot = g.get_mut(idx)?;
        if slot.gen != gen {
            return None;
        }
        slot.sess.take()
    }

    /// Run `f` against the session, returning its result. `None` if
    /// the handle is stale / out of range / the slot is empty.
    pub fn with<R>(&self, handle: Handle, f: impl FnOnce(&mut T) -> R) -> Option<R> {
        let (idx, gen) = decode(handle)?;
        let mut g = self.slots.lock().unwrap_or_else(|e| e.into_inner());
        let slot = g.get_mut(idx)?;
        if slot.gen != gen {
            return None;
        }
        let inner = slot.sess.as_mut()?;
        Some(f(inner))
    }

    /// Clone the slot's `cancel` Arc out from under the mutex, then
    /// release the lock. Returns `None` if the handle is stale.
    ///
    /// Callers should `.store(true, Ordering::Relaxed)` on the returned
    /// Arc *after* the lock is released. The Arc remains valid even if
    /// a concurrent `take()` removes the session — that's the whole
    /// point: a generate that already cloned the same Arc (at generate
    /// start) will still observe the signal.
    pub fn clone_cancel(&self, handle: Handle) -> Option<Arc<AtomicBool>> {
        let (idx, gen) = decode(handle)?;
        let g = self.slots.lock().unwrap_or_else(|e| e.into_inner());
        let slot = g.get(idx)?;
        if slot.gen != gen {
            return None;
        }
        Some(Arc::clone(&slot.cancel))
    }
}

impl<T> Default for SessionTable<T> {
    fn default() -> Self {
        Self::new()
    }
}

/// Pack `(slot_idx, gen)` into a `Handle`. `slot_idx + 1` in the low
/// 32 bits keeps `0` as the "load failed" sentinel.
fn encode(slot_idx: usize, gen: u32) -> Handle {
    let slot_plus_one = (slot_idx as u32).wrapping_add(1);
    (((gen as u64) << 32) | (slot_plus_one as u64)) as i64
}

/// Unpack a `Handle` back into `(slot_idx, gen)`. Returns `None` for
/// the all-zero handle.
fn decode(handle: Handle) -> Option<(usize, u32)> {
    if handle == 0 {
        return None;
    }
    let raw = handle as u64;
    let slot_plus_one = (raw & 0xFFFF_FFFF) as u32;
    if slot_plus_one == 0 {
        return None;
    }
    let gen = (raw >> 32) as u32;
    Some((slot_plus_one as usize - 1, gen))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::Ordering;

    #[test]
    fn store_then_with_returns_payload() {
        let t: SessionTable<String> = SessionTable::new();
        let h = t.store("hello".to_string());
        let result = t.with(h, |s| s.clone());
        assert_eq!(result.as_deref(), Some("hello"));
    }

    #[test]
    fn store_after_take_invalidates_old_handle() {
        let t: SessionTable<u32> = SessionTable::new();
        let h1 = t.store(42);
        let prior = t.take(h1);
        assert_eq!(prior, Some(42));
        let h2 = t.store(99);
        // Old handle must not see the new session.
        assert_eq!(t.with(h1, |v| *v), None);
        // New handle works.
        assert_eq!(t.with(h2, |v| *v), Some(99));
    }

    #[test]
    fn with_returns_none_on_gen_mismatch() {
        let t: SessionTable<u32> = SessionTable::new();
        let h1 = t.store(7);
        // Synthesise a handle with the same slot but bumped gen.
        let (idx, gen) = decode(h1).unwrap();
        let fake = encode(idx, gen.wrapping_add(1));
        assert_eq!(t.with(fake, |v| *v), None);
        // Original still works.
        assert_eq!(t.with(h1, |v| *v), Some(7));
    }

    #[test]
    fn with_returns_none_after_take() {
        let t: SessionTable<u32> = SessionTable::new();
        let h = t.store(5);
        let _ = t.take(h);
        assert_eq!(t.with(h, |v| *v), None);
    }

    #[test]
    fn cancel_arc_survives_take() {
        // The whole point of cancel-Arc hoist: cloning out the cancel
        // Arc, then having a concurrent take remove the session, must
        // not invalidate the Arc — any in-flight generate that cloned
        // the same Arc earlier still observes the store(true).
        let t: SessionTable<u32> = SessionTable::new();
        let h = t.store(1);
        let cancel = t.clone_cancel(h).expect("cancel Arc present");
        let _ = t.take(h);
        // Even though the session is gone, the cancel Arc is still
        // valid and the store is observable to any holder of a clone.
        let mirror = Arc::clone(&cancel);
        cancel.store(true, Ordering::Relaxed);
        assert!(mirror.load(Ordering::Relaxed));
    }

    #[test]
    fn clone_cancel_rejects_stale_handle() {
        let t: SessionTable<u32> = SessionTable::new();
        let h1 = t.store(1);
        let _ = t.take(h1);
        let _h2 = t.store(2);
        // Old handle should not get a cancel handle into the NEW session.
        assert!(t.clone_cancel(h1).is_none());
    }

    #[test]
    fn fresh_store_replaces_cancel_arc() {
        // A stale cancel Arc held from before the slot was reused must
        // NOT poison the next session. The new session gets a fresh
        // Arc; the stale Arc's stores reach only old generate clones.
        let t: SessionTable<u32> = SessionTable::new();
        let h1 = t.store(1);
        let stale = t.clone_cancel(h1).unwrap();
        let _ = t.take(h1);
        let h2 = t.store(2);
        let fresh = t.clone_cancel(h2).unwrap();
        // They must be DIFFERENT Arcs (not pointing at the same inner).
        assert!(!Arc::ptr_eq(&stale, &fresh));
        // Setting the stale one does not affect the fresh one.
        stale.store(true, Ordering::Relaxed);
        assert!(!fresh.load(Ordering::Relaxed));
    }

    #[test]
    fn encode_decode_roundtrip() {
        let cases = [(0usize, 1u32), (5, 42), (1000, u32::MAX), (0, u32::MAX)];
        for (idx, gen) in cases {
            let h = encode(idx, gen);
            let decoded = decode(h);
            assert_eq!(decoded, Some((idx, gen)), "roundtrip {idx} {gen}");
        }
    }

    #[test]
    fn decode_rejects_zero() {
        assert_eq!(decode(0), None);
    }

    #[test]
    fn gen_wraps_deterministically() {
        // Force the wrap path by directly poking the slot generation
        // and then storing — `wrapping_add` makes wrap behaviour defined.
        let t: SessionTable<u32> = SessionTable::new();
        let h = t.store(0);
        {
            let mut g = t.slots.lock().unwrap();
            g[0].sess = None; // simulate take
            g[0].gen = u32::MAX;
        }
        let h_after_wrap = t.store(1);
        // Old handle (gen=1) must not match (slot's gen is now 0 after wrap).
        assert_eq!(t.with(h, |v| *v), None);
        // New handle works.
        assert_eq!(t.with(h_after_wrap, |v| *v), Some(1));
        let (_, new_gen) = decode(h_after_wrap).unwrap();
        assert_eq!(new_gen, 0); // u32::MAX.wrapping_add(1) == 0
    }
}
