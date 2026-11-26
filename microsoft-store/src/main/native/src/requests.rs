//! In-flight request bookkeeping: cancellation flags and the canceller of the platform
//! operation a request is currently blocked on.

use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use crate::protocol::StoreError;

type Canceller = Box<dyn FnOnce() + Send>;

#[derive(Default)]
struct State {
    cancelled: bool,
    canceller: Option<Canceller>,
}

/// One request, from `nativeRequest` until its single response.
pub struct Request {
    pub id: i64,
    state: Mutex<State>,
    wake: Condvar,
}

impl Request {
    fn new(id: i64) -> Self {
        Self {
            id,
            state: Mutex::new(State::default()),
            wake: Condvar::new(),
        }
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        // A poisoned lock only means another request thread panicked while holding it; the
        // flag and canceller are still consistent, so keep going.
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn is_cancelled(&self) -> bool {
        self.lock().cancelled
    }

    pub fn check_cancelled(&self) -> Result<(), StoreError> {
        if self.is_cancelled() {
            Err(cancelled_error())
        } else {
            Ok(())
        }
    }

    /// Installs the canceller of the operation about to block this request. Runs it at once
    /// when the request was already cancelled, so a cancel racing the start is never lost.
    pub fn arm(&self, canceller: Canceller) -> Armed<'_> {
        let run_now = {
            let mut state = self.lock();
            if state.cancelled {
                Some(canceller)
            } else {
                state.canceller = Some(canceller);
                None
            }
        };
        if let Some(cancel) = run_now {
            cancel();
        }
        Armed(self)
    }

    fn cancel(&self) {
        let canceller = {
            let mut state = self.lock();
            state.cancelled = true;
            state.canceller.take()
        };
        self.wake.notify_all();
        if let Some(cancel) = canceller {
            cancel();
        }
    }

    /// Sleeps for `duration` unless cancelled first; `false` when cancelled.
    pub fn sleep(&self, duration: Duration) -> bool {
        let deadline = Instant::now() + duration;
        let mut state = self.lock();
        loop {
            if state.cancelled {
                return false;
            }
            let now = Instant::now();
            if now >= deadline {
                return true;
            }
            state = self
                .wake
                .wait_timeout(state, deadline - now)
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .0;
        }
    }
}

/// Clears the armed canceller when the blocking operation returns.
pub struct Armed<'a>(&'a Request);

impl Drop for Armed<'_> {
    fn drop(&mut self) {
        self.0.lock().canceller = None;
    }
}

pub fn cancelled_error() -> StoreError {
    StoreError::new("cancelled", "The request was cancelled")
}

fn registry() -> &'static Mutex<HashMap<i64, Arc<Request>>> {
    static REQUESTS: OnceLock<Mutex<HashMap<i64, Arc<Request>>>> = OnceLock::new();
    REQUESTS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn requests() -> MutexGuard<'static, HashMap<i64, Arc<Request>>> {
    registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Registers request `id`; `None` if that id is already in flight.
pub fn begin(id: i64) -> Option<Arc<Request>> {
    let mut map = requests();
    if map.contains_key(&id) {
        return None;
    }
    let request = Arc::new(Request::new(id));
    map.insert(id, request.clone());
    Some(request)
}

pub fn finish(id: i64) {
    requests().remove(&id);
}

/// Cancels request `id`; unknown or finished ids are ignored.
pub fn cancel(id: i64) {
    let request = requests().get(&id).cloned();
    if let Some(request) = request {
        request.cancel();
    }
}

pub fn in_flight() -> usize {
    requests().len()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn duplicate_ids_are_refused_until_finished() {
        let id = 1_000_001;
        assert!(begin(id).is_some());
        assert!(begin(id).is_none());
        finish(id);
        assert!(begin(id).is_some());
        finish(id);
    }

    #[test]
    fn cancel_runs_the_armed_canceller_once() {
        let id = 1_000_002;
        let request = begin(id).unwrap();
        let runs = Arc::new(AtomicUsize::new(0));
        let counter = runs.clone();
        let armed = request.arm(Box::new(move || {
            counter.fetch_add(1, Ordering::SeqCst);
        }));
        cancel(id);
        cancel(id);
        drop(armed);
        assert_eq!(runs.load(Ordering::SeqCst), 1);
        assert!(request.check_cancelled().is_err());
        finish(id);
    }

    #[test]
    fn arming_after_cancel_runs_immediately() {
        let id = 1_000_003;
        let request = begin(id).unwrap();
        cancel(id);
        let runs = Arc::new(AtomicUsize::new(0));
        let counter = runs.clone();
        let _armed = request.arm(Box::new(move || {
            counter.fetch_add(1, Ordering::SeqCst);
        }));
        assert_eq!(runs.load(Ordering::SeqCst), 1);
        finish(id);
    }

    #[test]
    fn disarmed_canceller_never_runs() {
        let id = 1_000_004;
        let request = begin(id).unwrap();
        let runs = Arc::new(AtomicUsize::new(0));
        let counter = runs.clone();
        drop(request.arm(Box::new(move || {
            counter.fetch_add(1, Ordering::SeqCst);
        })));
        cancel(id);
        assert_eq!(runs.load(Ordering::SeqCst), 0);
        finish(id);
    }

    #[test]
    fn sleep_wakes_on_cancel() {
        let id = 1_000_005;
        let request = begin(id).unwrap();
        let sleeper = request.clone();
        let started = Instant::now();
        let handle = std::thread::spawn(move || sleeper.sleep(Duration::from_secs(30)));
        std::thread::sleep(Duration::from_millis(50));
        cancel(id);
        assert!(!handle.join().unwrap());
        assert!(started.elapsed() < Duration::from_secs(5));
        finish(id);
    }

    #[test]
    fn cancel_of_unknown_id_is_a_no_op() {
        cancel(i64::MIN);
        cancel(-1);
    }

    #[test]
    fn concurrent_cancel_storm() {
        let base = 2_000_000;
        let requests: Vec<_> = (0..500).map(|i| begin(base + i).unwrap()).collect();
        let runs = Arc::new(AtomicUsize::new(0));
        let guards: Vec<_> = requests
            .iter()
            .map(|r| {
                let counter = runs.clone();
                r.arm(Box::new(move || {
                    counter.fetch_add(1, Ordering::SeqCst);
                }))
            })
            .collect();
        let threads: Vec<_> = (0..8)
            .map(|_| {
                std::thread::spawn(move || {
                    for i in 0..500 {
                        cancel(base + i);
                    }
                })
            })
            .collect();
        threads.into_iter().for_each(|t| t.join().unwrap());
        drop(guards);
        assert_eq!(runs.load(Ordering::SeqCst), 500);
        (0..500).for_each(|i| finish(base + i));
    }
}
