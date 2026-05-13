//! Tiny work-stealing-ish row-tile scheduler. Replaces Rayon (banned by
//! the no-deps rule). Target ≤ 200 LOC when complete.

use std::thread;

pub fn parallel_for_tiles<F>(rows: usize, tile: usize, threads: usize, body: F)
where
    F: Fn(usize, usize) + Sync + Send,
{
    if threads <= 1 || rows <= tile {
        let mut start = 0;
        while start < rows {
            let end = (start + tile).min(rows);
            body(start, end);
            start = end;
        }
        return;
    }
    thread::scope(|s| {
        let next = std::sync::atomic::AtomicUsize::new(0);
        for _ in 0..threads {
            let next = &next;
            let body = &body;
            s.spawn(move || loop {
                let start = next.fetch_add(tile, std::sync::atomic::Ordering::Relaxed);
                if start >= rows {
                    break;
                }
                let end = (start + tile).min(rows);
                body(start, end);
            });
        }
    });
}
