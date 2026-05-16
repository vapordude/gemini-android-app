//! Tiny work-stealing-ish row-tile scheduler. Replaces Rayon (banned by
//! the no-deps rule). Target ≤ 200 LOC when complete.

use std::sync::atomic::{AtomicUsize, Ordering};
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
    let next = AtomicUsize::new(0);
    let next_ref = &next;
    let body_ref = &body;
    thread::scope(|s| {
        for _ in 0..threads {
            s.spawn(move || loop {
                let start = next_ref.fetch_add(tile, Ordering::Relaxed);
                if start >= rows {
                    break;
                }
                let end = (start + tile).min(rows);
                body_ref(start, end);
            });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    #[test]
    fn single_thread_path() {
        let visited = AtomicUsize::new(0);
        parallel_for_tiles(100, 10, 1, |_s, _e| {
            visited.fetch_add(1, Ordering::Relaxed);
        });
        assert_eq!(visited.load(Ordering::Relaxed), 10);
    }

    #[test]
    fn covers_all_rows() {
        let touched: Vec<AtomicUsize> = (0..100).map(|_| AtomicUsize::new(0)).collect();
        parallel_for_tiles(100, 7, 4, |s, e| {
            for t in &touched[s..e] {
                t.fetch_add(1, Ordering::Relaxed);
            }
        });
        for (i, t) in touched.iter().enumerate() {
            assert_eq!(
                t.load(Ordering::Relaxed),
                1,
                "row {i} not visited exactly once"
            );
        }
    }
}
