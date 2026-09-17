use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};
use notify::event::{ModifyKind, RenameMode};
use notify::{
    Config, Event, EventHandler, EventKind, PollWatcher, RecommendedWatcher, RecursiveMode,
    Result as NotifyResult, Watcher, WatcherKind,
};
use notify_debouncer_full::file_id::FileId;
use notify_debouncer_full::{
    new_debouncer_opt, DebounceEventResult, Debouncer, FileIdCache, FileIdMap, RecommendedCache,
};
use once_cell::sync::{Lazy, OnceCell};
use std::collections::HashMap;
use std::ffi::c_void;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

const WATCHER_LEVEL_REGISTRATION_ID: i64 = 0;
const EVENT_KIND_CREATED: i32 = 1;
const EVENT_KIND_MODIFIED: i32 = 2;
const EVENT_KIND_REMOVED: i32 = 3;
const EVENT_KIND_OVERFLOW: i32 = 4;
const EVENT_KIND_MOVED: i32 = 5;
const BACKEND_MODE_NATIVE: i32 = 1;
const BACKEND_MODE_POLLING: i32 = 2;
const DELIVERY_MODE_RAW: i32 = 1;
const DELIVERY_MODE_DEBOUNCED: i32 = 2;

static NEXT_WATCHER_HANDLE: AtomicI64 = AtomicI64::new(1);
static WATCHERS: Lazy<Mutex<HashMap<i64, WatcherState>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static JVM: OnceCell<JavaVM> = OnceCell::new();
static BRIDGE_CLASS: OnceCell<GlobalRef> = OnceCell::new();

#[derive(Clone)]
struct RegistrationState {
    original_root: PathBuf,
    resolved_root: PathBuf,
    /// The spelling handed to the backend; see [`watched_root_for`].
    watched_root: PathBuf,
    recursive: bool,
}

/// One `FsWatcher`. Every registration shares the single `native_watcher` — one inotify instance,
/// one FSEvents stream, one `ReadDirectoryChangesW` loop per `FsWatcher` rather than per path
/// (#571) — and events are routed back to registrations by matching their roots.
struct WatcherState {
    registrations: HashMap<i64, RegistrationState>,
    native_watcher: Option<Arc<NativeWatcher>>,
    /// Serialises watch / unwatch / close for this watcher. Never taken by a callback, so it may
    /// be held while calling into notify (which joins backend threads).
    mutation: Arc<Mutex<()>>,
    closed: Arc<AtomicBool>,
    follow_symlinks: bool,
    backend_mode: BackendMode,
    delivery_mode: DeliveryMode,
}

/// The debouncer's backend: the platform watcher with FSEvents normalisation in front of it.
type DebouncedBackend = NormalizingWatcher<RecommendedWatcher>;

enum NativeWatcher {
    /// Raw delivery hands the backend's own events through untouched — on macOS that includes
    /// the historical flags FSEvents attaches to a path.
    Raw(Mutex<RecommendedWatcher>),
    Debounced(Mutex<Debouncer<DebouncedBackend, SharedFileIdCache>>),
    Polling(Mutex<PollWatcher>),
    PollingDebounced(Mutex<Debouncer<PollWatcher, FileIdMap>>),
}

#[derive(Copy, Clone)]
enum DeliveryMode {
    Raw,
    Debounced { window: Duration },
}

#[derive(Copy, Clone)]
enum BackendMode {
    Native,
    Polling {
        interval: Duration,
        compare_contents: bool,
    },
}

#[derive(Copy, Clone)]
enum MatchedRootKind {
    Original,
    Resolved,
}

// ---------------------------------------------------------------------------------------------
// File-id cache shared between the debouncer and the FSEvents normaliser
// ---------------------------------------------------------------------------------------------

/// The debouncer's file-id store, shared with the event normaliser so the latter can tell a path
/// the watch already tracks from a genuinely new one. `RecommendedCache` is `FileIdMap` on
/// macOS / Windows and the no-op `NoCache` on Linux, where inotify cookies pair renames for free.
#[derive(Clone, Default)]
struct KnownPaths(Arc<Mutex<RecommendedCache>>);

impl KnownPaths {
    fn lock_store(&self) -> Option<MutexGuard<'_, RecommendedCache>> {
        self.0.lock().ok()
    }

    #[cfg(target_os = "macos")]
    fn contains(&self, path: &Path) -> bool {
        self.lock_store()
            .map(|store| store.cached_file_id(path).is_some())
            .unwrap_or(false)
    }

    #[cfg(target_os = "macos")]
    fn refresh_file(&self, path: &Path) {
        if let Some(mut store) = self.lock_store() {
            store.add_path(path, RecursiveMode::NonRecursive);
        }
    }
}

/// `FileIdCache` handed to the debouncer; every call goes to the shared [`KnownPaths`] store.
struct SharedFileIdCache(KnownPaths);

impl FileIdCache for SharedFileIdCache {
    fn cached_file_id(&self, path: &Path) -> Option<impl AsRef<FileId>> {
        self.0
            .lock_store()
            .and_then(|store| store.cached_file_id(path).map(|id| *id.as_ref()))
    }

    fn add_path(&mut self, path: &Path, recursive_mode: RecursiveMode) {
        if let Some(mut store) = self.0.lock_store() {
            store.add_path(path, recursive_mode);
        }
    }

    fn remove_path(&mut self, path: &Path) {
        if let Some(mut store) = self.0.lock_store() {
            store.remove_path(path);
        }
    }

    fn rescan(&mut self, root_paths: &[(PathBuf, RecursiveMode)]) {
        if let Some(mut store) = self.0.lock_store() {
            store.rescan(root_paths);
        }
    }
}

// notify constructs the debouncer's watcher itself (`T::new(handler, config)`) and offers no way
// to hand it state, so the shared store travels through a thread-local set right before that
// synchronous constructor call and cleared right after.
#[cfg(target_os = "macos")]
thread_local! {
    static PENDING_KNOWN_PATHS: std::cell::RefCell<Option<KnownPaths>> = const { std::cell::RefCell::new(None) };
}

fn with_pending_known_paths<R>(known: &KnownPaths, create: impl FnOnce() -> R) -> R {
    #[cfg(target_os = "macos")]
    {
        PENDING_KNOWN_PATHS.with(|slot| *slot.borrow_mut() = Some(known.clone()));
        let result = create();
        PENDING_KNOWN_PATHS.with(|slot| slot.borrow_mut().take());
        result
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = known;
        create()
    }
}

#[cfg(target_os = "macos")]
fn take_pending_known_paths() -> KnownPaths {
    PENDING_KNOWN_PATHS
        .with(|slot| slot.borrow_mut().take())
        .unwrap_or_default()
}

// ---------------------------------------------------------------------------------------------
// FSEvents normalisation (macOS)
// ---------------------------------------------------------------------------------------------

/// Makes FSEvents honest before the debouncer sees it (#570). Only the debounced backend is
/// wrapped: raw delivery promises the backend's events as they come.
///
/// FSEvents attaches an inode's *accumulated* flags to every event it reports, so a plain rename
/// of a long-existing file arrives as `Create` + `Rename` + `Modify` on the old path — and the
/// debouncer, which reads `Create` as "created within this window", folds the rename into a bare
/// `Create(new)` and a delete into `Modify`. Each rule below only drops what cannot be true of
/// the path *right now*, which is all the debouncer needs to pair the rename through file ids.
#[cfg(target_os = "macos")]
#[derive(Default)]
struct FsEventsNormalizer {
    known: KnownPaths,
    last_path: Option<PathBuf>,
    removed_forwarded: bool,
}

#[cfg(target_os = "macos")]
impl FsEventsNormalizer {
    fn new(known: KnownPaths) -> Self {
        Self {
            known,
            last_path: None,
            removed_forwarded: false,
        }
    }

    fn normalize(&mut self, event: Event) -> Option<Event> {
        let Some(path) = event.paths.first() else {
            return Some(event);
        };
        if self.last_path.as_deref() != Some(path.as_path()) {
            self.last_path = Some(path.clone());
            self.removed_forwarded = false;
        }
        let metadata = std::fs::symlink_metadata(path).ok();
        let present = metadata.is_some();
        match event.kind {
            // A create for something that is not there is history: the rename or remove that
            // follows for the same path says what actually happened.
            EventKind::Create(_) if !present => None,
            // A create for a path the watch already tracks is history too; keep its id fresh in
            // case the file was replaced under the same name.
            EventKind::Create(_) if self.known.contains(path) => {
                if metadata.is_some_and(|m| !m.is_dir()) {
                    self.known.refresh_file(path);
                }
                None
            }
            // Nothing that is gone was modified — and a trailing stale `Modify` would also
            // displace the rename `From` the debouncer expects last in the path's queue.
            EventKind::Modify(
                ModifyKind::Data(_) | ModifyKind::Metadata(_) | ModifyKind::Any | ModifyKind::Other,
            ) if !present => None,
            // A remove for something that is present is history.
            EventKind::Remove(_) if present => None,
            EventKind::Remove(_) => {
                self.removed_forwarded = true;
                Some(event)
            }
            // `Removed | Renamed` on a gone path: the rename is the older half of the history.
            EventKind::Modify(ModifyKind::Name(RenameMode::Any)) if !present && self.removed_forwarded => None,
            _ => Some(event),
        }
    }
}

struct NormalizingHandler<F: EventHandler> {
    inner: F,
    #[cfg(target_os = "macos")]
    normalizer: FsEventsNormalizer,
}

impl<F: EventHandler> EventHandler for NormalizingHandler<F> {
    fn handle_event(&mut self, event: NotifyResult<Event>) {
        #[cfg(target_os = "macos")]
        let event = match event {
            Ok(event) => match self.normalizer.normalize(event) {
                Some(event) => Ok(event),
                None => return,
            },
            Err(error) => Err(error),
        };
        self.inner.handle_event(event);
    }
}

/// The platform watcher with [`NormalizingHandler`] in front of its event handler; a plain
/// pass-through everywhere but macOS.
struct NormalizingWatcher<W: Watcher> {
    inner: W,
}

impl<W: Watcher> Watcher for NormalizingWatcher<W> {
    fn new<F: EventHandler>(event_handler: F, config: Config) -> NotifyResult<Self> {
        let handler = NormalizingHandler {
            inner: event_handler,
            #[cfg(target_os = "macos")]
            normalizer: FsEventsNormalizer::new(take_pending_known_paths()),
        };
        Ok(Self {
            inner: W::new(handler, config)?,
        })
    }

    fn watch(&mut self, path: &Path, recursive_mode: RecursiveMode) -> NotifyResult<()> {
        self.inner.watch(path, recursive_mode)
    }

    fn unwatch(&mut self, path: &Path) -> NotifyResult<()> {
        self.inner.unwatch(path)
    }

    fn configure(&mut self, config: Config) -> NotifyResult<bool> {
        self.inner.configure(config)
    }

    fn kind() -> WatcherKind {
        W::kind()
    }
}

// ---------------------------------------------------------------------------------------------
// JNI plumbing
// ---------------------------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_8
}

fn cache_bridge_class(env: &mut JNIEnv, class: JClass) {
    if BRIDGE_CLASS.get().is_some() {
        return;
    }
    if let Ok(global) = env.new_global_ref(class) {
        let _ = BRIDGE_CLASS.set(global);
    }
}

fn detect_is_directory(path: &Path) -> i32 {
    match std::fs::metadata(path) {
        Ok(metadata) if metadata.is_dir() => 1,
        Ok(_) => 0,
        Err(_) => -1,
    }
}

fn path_is_present(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok()
}

/// Maps a notify event onto the bridge's event kinds. Renames the backend could not pair are
/// not discarded: the side that left is `Removed`, the side that appeared is `Created`, and an
/// FSEvents `Any` is told apart by whether its path is still there.
fn classify_event(event: &Event) -> Option<i32> {
    match event.kind {
        EventKind::Create(_) => Some(EVENT_KIND_CREATED),
        EventKind::Modify(ModifyKind::Data(_)) | EventKind::Modify(ModifyKind::Metadata(_)) => {
            Some(EVENT_KIND_MODIFIED)
        }
        EventKind::Modify(ModifyKind::Name(RenameMode::Both)) if event.paths.len() >= 2 => {
            Some(EVENT_KIND_MOVED)
        }
        EventKind::Modify(ModifyKind::Name(RenameMode::From)) => Some(EVENT_KIND_REMOVED),
        EventKind::Modify(ModifyKind::Name(RenameMode::To)) => Some(EVENT_KIND_CREATED),
        EventKind::Modify(ModifyKind::Name(RenameMode::Any | RenameMode::Both)) => {
            let path = event.paths.first()?;
            Some(if path_is_present(path) {
                EVENT_KIND_CREATED
            } else {
                EVENT_KIND_REMOVED
            })
        }
        EventKind::Remove(_) => Some(EVENT_KIND_REMOVED),
        _ => None,
    }
}

fn emit_event(
    watcher_handle: i64,
    registration_id: i64,
    origin_native_registration_id: Option<i64>,
    event_kind: i32,
    path: Option<&Path>,
    secondary_path: Option<&Path>,
    needs_rescan: bool,
    is_directory: i32,
) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Some(bridge_class) = BRIDGE_CLASS.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.with_local_frame(16, |env| -> jni::errors::Result<JObject<'_>> {
        let j_path = path.and_then(|value| {
            env.new_string(value.to_string_lossy().as_ref())
                .ok()
                .map(JObject::from)
        });
        let j_secondary_path = secondary_path.and_then(|value| {
            env.new_string(value.to_string_lossy().as_ref())
                .ok()
                .map(JObject::from)
        });
        let origin_long = origin_native_registration_id.unwrap_or(WATCHER_LEVEL_REGISTRATION_ID);
        let null_object = JObject::null();

        let _ = env.call_static_method(
            bridge_class,
            "onNativeEvent",
            "(JJJILjava/lang/String;Ljava/lang/String;ZI)V",
            &[
                JValue::Long(watcher_handle),
                JValue::Long(registration_id),
                JValue::Long(origin_long),
                JValue::Int(event_kind),
                JValue::Object(j_path.as_ref().unwrap_or(&null_object)),
                JValue::Object(j_secondary_path.as_ref().unwrap_or(&null_object)),
                JValue::Bool(if needs_rescan { JNI_TRUE } else { JNI_FALSE }),
                JValue::Int(is_directory),
            ],
        );
        Ok(JObject::null())
    });
}

fn emit_error(
    watcher_handle: i64,
    registration_id: i64,
    origin_native_registration_id: Option<i64>,
    message: &str,
    recoverable: bool,
    path: Option<&Path>,
) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Some(bridge_class) = BRIDGE_CLASS.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.with_local_frame(16, |env| -> jni::errors::Result<JObject<'_>> {
        let Ok(j_message) = env.new_string(message) else {
            return Ok(JObject::null());
        };
        let j_path = path.and_then(|value| {
            env.new_string(value.to_string_lossy().as_ref())
                .ok()
                .map(JObject::from)
        });
        let origin_long = origin_native_registration_id.unwrap_or(WATCHER_LEVEL_REGISTRATION_ID);
        let null_object = JObject::null();

        let _ = env.call_static_method(
            bridge_class,
            "onNativeError",
            "(JJJLjava/lang/String;ZLjava/lang/String;)V",
            &[
                JValue::Long(watcher_handle),
                JValue::Long(registration_id),
                JValue::Long(origin_long),
                JValue::Object(&JObject::from(j_message)),
                JValue::Bool(if recoverable { JNI_TRUE } else { JNI_FALSE }),
                JValue::Object(j_path.as_ref().unwrap_or(&null_object)),
            ],
        );
        Ok(JObject::null())
    });
}

fn emit_overflow(watcher_handle: i64) {
    emit_event(
        watcher_handle,
        WATCHER_LEVEL_REGISTRATION_ID,
        None,
        EVENT_KIND_OVERFLOW,
        None,
        None,
        true,
        -1,
    );
}

// ---------------------------------------------------------------------------------------------
// Routing: one shared backend, N registrations
// ---------------------------------------------------------------------------------------------

fn path_matches_root(root: &Path, recursive: bool, path: &Path) -> bool {
    if recursive {
        path == root || path.starts_with(root)
    } else {
        path == root || path.parent() == Some(root)
    }
}

fn match_registration(registration: &RegistrationState, path: &Path) -> Option<MatchedRootKind> {
    if path_matches_root(&registration.original_root, registration.recursive, path) {
        Some(MatchedRootKind::Original)
    } else if path_matches_root(&registration.resolved_root, registration.recursive, path) {
        Some(MatchedRootKind::Resolved)
    } else {
        None
    }
}

fn registration_by_id(registration_id: i64, watcher_handle: i64) -> Option<RegistrationState> {
    WATCHERS.lock().ok().and_then(|watchers| {
        watchers
            .get(&watcher_handle)
            .and_then(|state| state.registrations.get(&registration_id).cloned())
    })
}

/// What the callback thread needs to route one event, read under the lock without cloning paths.
struct Routing {
    /// Ids of the registrations whose root covers the path(s) the event is about.
    targets: Vec<i64>,
    has_registrations: bool,
    moved_supported: bool,
}

fn routing_for(watcher_handle: i64, covers: impl Fn(&RegistrationState) -> bool) -> Option<Routing> {
    let watchers = WATCHERS.lock().ok()?;
    let state = watchers.get(&watcher_handle)?;
    let mut targets: Vec<i64> = state
        .registrations
        .iter()
        .filter(|(_, registration)| covers(registration))
        .map(|(id, _)| *id)
        .collect();
    targets.sort_unstable();
    Some(Routing {
        targets,
        has_registrations: !state.registrations.is_empty(),
        moved_supported: matches!(state.backend_mode, BackendMode::Native)
            && matches!(state.delivery_mode, DeliveryMode::Debounced { .. }),
    })
}

/// Emits `event_kind` for `path` to every registration covering it; `true` when at least one did.
fn emit_to_covering_registrations(
    watcher_handle: i64,
    event_kind: i32,
    path: &Path,
    secondary_path: Option<&Path>,
    needs_rescan: bool,
    is_directory: i32,
) -> bool {
    let Some(routing) = routing_for(watcher_handle, |registration| {
        match_registration(registration, path).is_some()
            || secondary_path.is_some_and(|other| match_registration(registration, other).is_some())
    }) else {
        return false;
    };
    for registration_id in &routing.targets {
        emit_event(
            watcher_handle,
            WATCHER_LEVEL_REGISTRATION_ID,
            Some(*registration_id),
            event_kind,
            Some(path),
            secondary_path,
            needs_rescan,
            is_directory,
        );
    }
    !routing.targets.is_empty()
}

fn handle_debounce_result(watcher_handle: i64, result: DebounceEventResult) {
    match result {
        Ok(events) => {
            for debounced_event in events {
                handle_notify_result(watcher_handle, Ok(debounced_event.event));
            }
        }
        Err(errors) => {
            for error in errors {
                handle_notify_result(watcher_handle, Err(error));
            }
        }
    }
}

fn handle_notify_result(watcher_handle: i64, result: NotifyResult<Event>) {
    match result {
        Ok(event) => {
            let Some(routing) = routing_for(watcher_handle, |_| false) else {
                return;
            };
            if !routing.has_registrations {
                return;
            }
            let first_path = event.paths.first().map(PathBuf::as_path);
            let second_path = event.paths.get(1).map(PathBuf::as_path);
            let needs_rescan = event.need_rescan();

            let delivered = match (classify_event(&event), first_path) {
                (Some(EVENT_KIND_MOVED), Some(from)) => {
                    let Some(to) = second_path else {
                        return;
                    };
                    if routing.moved_supported {
                        emit_to_covering_registrations(
                            watcher_handle,
                            EVENT_KIND_MOVED,
                            from,
                            Some(to),
                            needs_rescan,
                            detect_is_directory(to),
                        )
                    } else {
                        // Raw delivery never pairs renames; inotify's own `Both` duplicates the
                        // `From` / `To` pair it just emitted, so it carries nothing new.
                        false
                    }
                }
                (Some(event_kind), Some(path)) => emit_to_covering_registrations(
                    watcher_handle,
                    event_kind,
                    path,
                    None,
                    needs_rescan,
                    detect_is_directory(path),
                ),
                _ => false,
            };
            if !delivered && needs_rescan {
                emit_overflow(watcher_handle);
            }
        }
        Err(error) => {
            let first_path = error.paths.first().map(PathBuf::as_path);
            let Some(routing) = routing_for(watcher_handle, |registration| {
                first_path.is_some_and(|path| match_registration(registration, path).is_some())
            }) else {
                return;
            };
            if !routing.has_registrations {
                return;
            }
            let message = error.to_string();
            match first_path {
                Some(path) if !routing.targets.is_empty() => {
                    for registration_id in &routing.targets {
                        emit_error(
                            watcher_handle,
                            WATCHER_LEVEL_REGISTRATION_ID,
                            Some(*registration_id),
                            &message,
                            true,
                            Some(path),
                        );
                    }
                }
                // No registration owns it: the shared backend itself is complaining.
                _ => emit_error(watcher_handle, WATCHER_LEVEL_REGISTRATION_ID, None, &message, true, None),
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Native watcher lifecycle
// ---------------------------------------------------------------------------------------------

fn native_watch(watcher: &NativeWatcher, path: &Path, recursive_mode: RecursiveMode) -> NotifyResult<()> {
    match watcher {
        NativeWatcher::Raw(inner) => lock_watcher(inner)?.watch(path, recursive_mode),
        NativeWatcher::Debounced(inner) => lock_watcher(inner)?.watch(path, recursive_mode),
        NativeWatcher::Polling(inner) => lock_watcher(inner)?.watch(path, recursive_mode),
        NativeWatcher::PollingDebounced(inner) => lock_watcher(inner)?.watch(path, recursive_mode),
    }
}

fn native_unwatch(watcher: &NativeWatcher, path: &Path) -> NotifyResult<()> {
    match watcher {
        NativeWatcher::Raw(inner) => lock_watcher(inner)?.unwatch(path),
        NativeWatcher::Debounced(inner) => lock_watcher(inner)?.unwatch(path),
        NativeWatcher::Polling(inner) => lock_watcher(inner)?.unwatch(path),
        NativeWatcher::PollingDebounced(inner) => lock_watcher(inner)?.unwatch(path),
    }
}

fn lock_watcher<T>(watcher: &Mutex<T>) -> NotifyResult<MutexGuard<'_, T>> {
    watcher
        .lock()
        .map_err(|_| notify::Error::generic("failed to lock native watcher"))
}

fn create_native_watcher(
    watcher_handle: i64,
    follow_symlinks: bool,
    backend_mode: BackendMode,
    delivery_mode: DeliveryMode,
) -> Option<NativeWatcher> {
    match backend_mode {
        BackendMode::Native => {
            let config = Config::default().with_follow_symlinks(follow_symlinks);
            match delivery_mode {
                DeliveryMode::Raw => RecommendedWatcher::new(
                    move |result| handle_notify_result(watcher_handle, result),
                    config,
                )
                .ok()
                .map(|watcher| NativeWatcher::Raw(Mutex::new(watcher))),
                DeliveryMode::Debounced { window } => {
                    let known = KnownPaths::default();
                    let cache = SharedFileIdCache(known.clone());
                    with_pending_known_paths(&known, || {
                        new_debouncer_opt::<_, DebouncedBackend, SharedFileIdCache>(
                            window,
                            None,
                            move |result| handle_debounce_result(watcher_handle, result),
                            cache,
                            config,
                        )
                    })
                    .ok()
                    .map(|debouncer| NativeWatcher::Debounced(Mutex::new(debouncer)))
                }
            }
        }
        BackendMode::Polling {
            interval,
            compare_contents,
        } => {
            let config = Config::default()
                .with_follow_symlinks(follow_symlinks)
                .with_poll_interval(interval)
                .with_compare_contents(compare_contents);
            match delivery_mode {
                DeliveryMode::Raw => PollWatcher::new(
                    move |result| handle_notify_result(watcher_handle, result),
                    config,
                )
                .ok()
                .map(|watcher| NativeWatcher::Polling(Mutex::new(watcher))),
                DeliveryMode::Debounced { window } => new_debouncer_opt::<_, PollWatcher, FileIdMap>(
                    window,
                    None,
                    move |result| handle_debounce_result(watcher_handle, result),
                    FileIdMap::new(),
                    config,
                )
                .ok()
                .map(|debouncer| NativeWatcher::PollingDebounced(Mutex::new(debouncer))),
            }
        }
    }
}

struct WatcherSettings {
    mutation: Arc<Mutex<()>>,
    follow_symlinks: bool,
    backend_mode: BackendMode,
    delivery_mode: DeliveryMode,
}

fn watcher_settings(watcher_handle: i64) -> Option<WatcherSettings> {
    let watchers = WATCHERS.lock().ok()?;
    let state = watchers.get(&watcher_handle)?;
    Some(WatcherSettings {
        mutation: Arc::clone(&state.mutation),
        follow_symlinks: state.follow_symlinks,
        backend_mode: state.backend_mode,
        delivery_mode: state.delivery_mode,
    })
}

/// How the backend currently covers `watched_root` through other registrations of this watcher.
struct RootCoverage {
    watched: bool,
    recursive: bool,
}

fn root_coverage(watcher_handle: i64, watched_root: &Path, excluding: Option<i64>) -> Option<RootCoverage> {
    let watchers = WATCHERS.lock().ok()?;
    let state = watchers.get(&watcher_handle)?;
    let mut coverage = RootCoverage {
        watched: false,
        recursive: false,
    };
    for (id, registration) in &state.registrations {
        if Some(*id) == excluding || registration.watched_root != watched_root {
            continue;
        }
        coverage.watched = true;
        coverage.recursive |= registration.recursive;
    }
    Some(coverage)
}

/// Returns the watcher's shared backend, creating it on first use. `None` once the watcher is gone.
fn shared_native_watcher(watcher_handle: i64, settings: &WatcherSettings) -> Option<Arc<NativeWatcher>> {
    if let Some(existing) = WATCHERS
        .lock()
        .ok()?
        .get(&watcher_handle)?
        .native_watcher
        .clone()
    {
        return Some(existing);
    }
    // Created outside the lock: backends spawn threads and the debouncer starts ticking.
    let created = Arc::new(create_native_watcher(
        watcher_handle,
        settings.follow_symlinks,
        settings.backend_mode,
        settings.delivery_mode,
    )?);
    let mut watchers = WATCHERS.lock().ok()?;
    let state = watchers.get_mut(&watcher_handle)?;
    Some(Arc::clone(state.native_watcher.get_or_insert(created)))
}

/// Drops the shared backend once no registration needs it, releasing its inotify instance,
/// FSEvents stream or directory handles. Returned so the caller drops it outside the lock.
fn release_native_watcher_if_unused(watcher_handle: i64) -> Option<Arc<NativeWatcher>> {
    let mut watchers = WATCHERS.lock().ok()?;
    let state = watchers.get_mut(&watcher_handle)?;
    if state.registrations.is_empty() {
        state.native_watcher.take()
    } else {
        None
    }
}

/// The spelling handed to the backend. One backend watch per *real* directory, so two
/// registrations of the same directory under different spellings share it and the Kotlin side
/// projects events back onto each registration's own root.
///
/// - macOS: FSEvents reports canonical paths anyway, and the debouncer keys its file-id cache by
///   the root it was given — a `/var/...` root would never pair a rename reported under
///   `/private/var`.
/// - Linux: inotify identifies a directory by inode, so two spellings share one watch descriptor
///   and notify keeps a single path per descriptor; watching the canonical spelling makes the
///   reported paths the same for every alias instead of whichever alias registered last.
/// - Windows: `canonicalize()` yields `\\?\` verbatim paths that Java's `toRealPath()` never
///   produces, so the registered spelling is kept; `ReadDirectoryChangesW` opens one handle per
///   watch anyway.
fn watched_root_for(original_root: &Path, resolved_root: &Path) -> PathBuf {
    if cfg!(target_os = "windows") {
        let _ = resolved_root;
        original_root.to_path_buf()
    } else {
        resolved_root.to_path_buf()
    }
}

// ---------------------------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeIsSupported(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeCreate(
    mut env: JNIEnv,
    class: JClass,
    follow_symlinks: jboolean,
    backend_mode: jint,
    delivery_mode: jint,
    debounce_window_millis: jlong,
    poll_interval_millis: jlong,
    compare_contents: jboolean,
) -> jlong {
    cache_bridge_class(&mut env, class);

    let backend_mode = match backend_mode {
        BACKEND_MODE_NATIVE => BackendMode::Native,
        BACKEND_MODE_POLLING if poll_interval_millis > 0 => BackendMode::Polling {
            interval: Duration::from_millis(poll_interval_millis as u64),
            compare_contents: compare_contents != JNI_FALSE,
        },
        BACKEND_MODE_POLLING => return 0,
        _ => return 0,
    };
    let delivery_mode = match delivery_mode {
        DELIVERY_MODE_RAW => DeliveryMode::Raw,
        DELIVERY_MODE_DEBOUNCED if debounce_window_millis > 0 => DeliveryMode::Debounced {
            window: Duration::from_millis(debounce_window_millis as u64),
        },
        DELIVERY_MODE_DEBOUNCED => return 0,
        _ => return 0,
    };

    let watcher_handle = NEXT_WATCHER_HANDLE.fetch_add(1, Ordering::Relaxed);

    if let Ok(mut watchers) = WATCHERS.lock() {
        watchers.insert(
            watcher_handle,
            WatcherState {
                registrations: HashMap::new(),
                native_watcher: None,
                mutation: Arc::new(Mutex::new(())),
                closed: Arc::new(AtomicBool::new(false)),
                follow_symlinks: follow_symlinks != JNI_FALSE,
                backend_mode,
                delivery_mode,
            },
        );
        watcher_handle
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    watcher_handle: jlong,
) {
    // Removing the state first stops callbacks from matching anything; the mutation lock then
    // waits for a watch / unwatch in flight before the backend is dropped outside every lock.
    let Some(state) = WATCHERS
        .lock()
        .ok()
        .and_then(|mut watchers| watchers.remove(&watcher_handle))
    else {
        return;
    };
    state.closed.store(true, Ordering::Release);
    let mutation = Arc::clone(&state.mutation);
    let _mutation_guard = mutation.lock();
    drop(state);
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeWatch(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    registration_id: jlong,
    path: JString,
    recursive: jboolean,
    _name: JString,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(path) = env.get_string(&path) else {
        return JNI_FALSE;
    };
    let original_root = PathBuf::from(path.to_string_lossy().into_owned());
    let resolved_root = original_root
        .canonicalize()
        .unwrap_or_else(|_| original_root.clone());
    let watched_root = watched_root_for(&original_root, &resolved_root);
    let recursive = recursive != JNI_FALSE;
    let recursive_mode = if recursive {
        RecursiveMode::Recursive
    } else {
        RecursiveMode::NonRecursive
    };

    let Some(settings) = watcher_settings(watcher_handle) else {
        return JNI_FALSE;
    };
    let mutation = Arc::clone(&settings.mutation);
    let Ok(_mutation_guard) = mutation.lock() else {
        return JNI_FALSE;
    };
    if matches!(settings.backend_mode, BackendMode::Polling { .. }) && std::fs::metadata(&original_root).is_err() {
        return JNI_FALSE;
    }
    let Some(native_watcher) = shared_native_watcher(watcher_handle, &settings) else {
        return JNI_FALSE;
    };
    let Some(coverage) = root_coverage(watcher_handle, &watched_root, None) else {
        return JNI_FALSE;
    };

    // The backend watches a root once per watcher. A recursive registration arriving over a
    // non-recursive one re-watches it: notify's backends do not widen an existing watch in place
    // (ReadDirectoryChangesW would even leak the old handle and report everything twice).
    let backend_result = if !coverage.watched {
        native_watch(&native_watcher, &watched_root, recursive_mode)
    } else if recursive && !coverage.recursive {
        let _ = native_unwatch(&native_watcher, &watched_root);
        native_watch(&native_watcher, &watched_root, recursive_mode)
    } else {
        Ok(())
    };
    if backend_result.is_err() {
        drop(release_native_watcher_if_unused(watcher_handle));
        return JNI_FALSE;
    }

    let registered = WATCHERS
        .lock()
        .ok()
        .and_then(|mut watchers| {
            let state = watchers.get_mut(&watcher_handle)?;
            if state.closed.load(Ordering::Acquire) {
                return None;
            }
            state.registrations.insert(
                registration_id,
                RegistrationState {
                    original_root,
                    resolved_root,
                    watched_root: watched_root.clone(),
                    recursive,
                },
            );
            Some(())
        })
        .is_some();

    if registered {
        JNI_TRUE
    } else {
        if !coverage.watched {
            let _ = native_unwatch(&native_watcher, &watched_root);
        }
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeUnwatch(
    _env: JNIEnv,
    _class: JClass,
    watcher_handle: jlong,
    registration_id: jlong,
) {
    let Some(settings) = watcher_settings(watcher_handle) else {
        return;
    };
    let Ok(_mutation_guard) = settings.mutation.lock() else {
        return;
    };
    let removed = WATCHERS.lock().ok().and_then(|mut watchers| {
        let state = watchers.get_mut(&watcher_handle)?;
        let registration = state.registrations.remove(&registration_id)?;
        let native_watcher = state.native_watcher.clone();
        Some((registration, native_watcher))
    });
    let Some((registration, Some(native_watcher))) = removed else {
        return;
    };
    let Some(coverage) = root_coverage(watcher_handle, &registration.watched_root, None) else {
        return;
    };
    // Dropping the whole backend stops every watch at once; otherwise the root is unwatched only
    // when no other registration still relies on it.
    let released = release_native_watcher_if_unused(watcher_handle);
    if released.is_none() && !coverage.watched {
        let _ = native_unwatch(&native_watcher, &registration.watched_root);
    }
}

#[no_mangle]
// Temporary JNI test probe kept in the production cdylib to exercise native-side
// liveness and path-matching gates from JVM tests. Do not extend this surface
// into runtime API without a separate design decision.
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeDebugEmitPathEvent(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    origin_native_registration_id: jlong,
    event_kind: jint,
    path: JString,
    secondary_path: JString,
    needs_rescan: jboolean,
    is_directory: jint,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(path) = env.get_string(&path) else {
        return JNI_FALSE;
    };
    let first_path = PathBuf::from(path.to_string_lossy().into_owned());
    let second_path = if secondary_path.is_null() {
        None
    } else {
        env.get_string(&secondary_path)
            .ok()
            .map(|value| PathBuf::from(value.to_string_lossy().into_owned()))
    };

    if event_kind == EVENT_KIND_MOVED && second_path.is_none() {
        return JNI_FALSE;
    }

    let Some(registration) = registration_by_id(origin_native_registration_id, watcher_handle) else {
        return JNI_FALSE;
    };
    if match_registration(&registration, &first_path).is_none() {
        return JNI_FALSE;
    }
    emit_event(
        watcher_handle,
        WATCHER_LEVEL_REGISTRATION_ID,
        Some(origin_native_registration_id),
        event_kind,
        Some(first_path.as_path()),
        second_path.as_deref(),
        needs_rescan != JNI_FALSE,
        is_directory,
    );
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeDebugEmitPathError(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    origin_native_registration_id: jlong,
    message: JString,
    recoverable: jboolean,
    path: JString,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(message) = env.get_string(&message) else {
        return JNI_FALSE;
    };
    let Ok(path) = env.get_string(&path) else {
        return JNI_FALSE;
    };
    let first_path = PathBuf::from(path.to_string_lossy().into_owned());

    let Some(registration) = registration_by_id(origin_native_registration_id, watcher_handle) else {
        return JNI_FALSE;
    };
    if match_registration(&registration, &first_path).is_none() {
        return JNI_FALSE;
    }

    emit_error(
        watcher_handle,
        WATCHER_LEVEL_REGISTRATION_ID,
        Some(origin_native_registration_id),
        message.to_string_lossy().as_ref(),
        recoverable != JNI_FALSE,
        Some(first_path.as_path()),
    );
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeDebugEmitPathlessError(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    origin_native_registration_id: jlong,
    message: JString,
    recoverable: jboolean,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(message) = env.get_string(&message) else {
        return JNI_FALSE;
    };

    if registration_by_id(origin_native_registration_id, watcher_handle).is_none() {
        return JNI_FALSE;
    }

    emit_error(
        watcher_handle,
        origin_native_registration_id,
        Some(origin_native_registration_id),
        message.to_string_lossy().as_ref(),
        recoverable != JNI_FALSE,
        None,
    );
    JNI_TRUE
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event_with_paths(kind: EventKind, paths: &[&Path]) -> Event {
        Event {
            kind,
            paths: paths.iter().map(|path| path.to_path_buf()).collect(),
            attrs: Default::default(),
        }
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nucleus-fs-watcher-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn classify_event_maps_paired_rename_to_moved_and_unpaired_halves_to_their_effect() {
        let dir = temp_dir("classify");
        let present = dir.join("present.txt");
        std::fs::write(&present, "x").unwrap();
        let gone = dir.join("gone.txt");

        let rename_kind = |mode| EventKind::Modify(ModifyKind::Name(mode));
        assert_eq!(
            classify_event(&event_with_paths(rename_kind(RenameMode::Both), &[&gone, &present])),
            Some(EVENT_KIND_MOVED)
        );
        assert_eq!(
            classify_event(&event_with_paths(rename_kind(RenameMode::From), &[&gone])),
            Some(EVENT_KIND_REMOVED)
        );
        assert_eq!(
            classify_event(&event_with_paths(rename_kind(RenameMode::To), &[&present])),
            Some(EVENT_KIND_CREATED)
        );
        assert_eq!(
            classify_event(&event_with_paths(rename_kind(RenameMode::Any), &[&gone])),
            Some(EVENT_KIND_REMOVED)
        );
        assert_eq!(
            classify_event(&event_with_paths(rename_kind(RenameMode::Any), &[&present])),
            Some(EVENT_KIND_CREATED)
        );
        // A `Both` that lost its second path degrades like an `Any`.
        assert_eq!(
            classify_event(&event_with_paths(rename_kind(RenameMode::Both), &[&gone])),
            Some(EVENT_KIND_REMOVED)
        );
        assert_eq!(
            classify_event(&event_with_paths(rename_kind(RenameMode::Other), &[&gone])),
            None
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn fsevents_normalizer_drops_history_and_keeps_what_is_true_now() {
        use notify::event::{CreateKind, DataChange, MetadataKind, RemoveKind};

        let dir = temp_dir("normalizer");
        let known_file = dir.join("known.txt");
        std::fs::write(&known_file, "known").unwrap();
        let fresh_file = dir.join("fresh.txt");
        std::fs::write(&fresh_file, "fresh").unwrap();
        let gone = dir.join("gone.txt");

        let known = KnownPaths::default();
        known.lock_store().unwrap().add_path(&known_file, RecursiveMode::NonRecursive);
        let mut normalizer = FsEventsNormalizer::new(known);
        let mut normalize = |kind, path: &Path| normalizer.normalize(event_with_paths(kind, &[path])).is_some();

        // The rename source as FSEvents reports it: Create + Rename + Modify on a gone path.
        assert!(!normalize(EventKind::Create(CreateKind::File), &gone));
        assert!(normalize(EventKind::Modify(ModifyKind::Name(RenameMode::Any)), &gone));
        assert!(!normalize(EventKind::Modify(ModifyKind::Metadata(MetadataKind::Extended)), &gone));
        assert!(!normalize(EventKind::Modify(ModifyKind::Data(DataChange::Content)), &gone));

        // A delete carrying the file's historical Created bit.
        assert!(!normalize(EventKind::Create(CreateKind::File), &gone));
        assert!(normalize(EventKind::Remove(RemoveKind::File), &gone));
        // `Removed | Renamed` on the same gone path: the rename half is history.
        assert!(!normalize(EventKind::Modify(ModifyKind::Name(RenameMode::Any)), &gone));

        // Stale Created on a file the watch already tracks vs a genuinely new file.
        assert!(!normalize(EventKind::Create(CreateKind::File), &known_file));
        assert!(normalize(EventKind::Create(CreateKind::File), &fresh_file));
        // Present paths keep their modifications; a Remove for a present path is history.
        assert!(normalize(EventKind::Modify(ModifyKind::Data(DataChange::Content)), &known_file));
        assert!(!normalize(EventKind::Remove(RemoveKind::File), &known_file));
        // The rename target is present and passes through untouched.
        assert!(normalize(EventKind::Modify(ModifyKind::Name(RenameMode::Any)), &fresh_file));

        let _ = std::fs::remove_dir_all(&dir);
    }
}
