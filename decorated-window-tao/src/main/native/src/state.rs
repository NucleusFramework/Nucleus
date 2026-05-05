// Process-wide state shared by the JNI surface and the Tao event loop.
//
// All globals live here so the rest of the crate can be split into modules
// without circular imports between `events`, `event_loop` and `window_jni`.

use std::collections::HashMap;
use std::sync::Mutex;

use jni::objects::GlobalRef;
use jni::JavaVM;
use once_cell::sync::OnceCell;

use tao::event_loop::EventLoopProxy;
use tao::window::Window;

use crate::events::UserEvent;

pub(crate) static JAVA_VM: OnceCell<JavaVM> = OnceCell::new();

// Held in a Mutex (not OnceCell) so we can drop the GlobalRef after the Tao
// event loop exits — invokes DeleteGlobalRef and unpins the Kotlin callback.
pub(crate) static EVENT_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);

pub(crate) static EVENT_LOOP_PROXY: Mutex<Option<EventLoopProxy<UserEvent>>> = Mutex::new(None);

pub(crate) static WINDOWS: Mutex<Option<HashMap<u64, Window>>> = Mutex::new(None);

// Tracked across `WindowEvent::ModifiersChanged`. AWT-style modifier state
// (which Compose `KeyEvent` consumes) carries Shift/Ctrl/Alt/Meta booleans on
// every event, so we need to remember the latest snapshot. Stored as already-
// packed AWT-equivalent bitmask matching `TaoModifierMask` on the JVM side.
pub(crate) static CURRENT_MODIFIERS: Mutex<i32> = Mutex::new(0);

pub(crate) fn set_event_loop_proxy(proxy: EventLoopProxy<UserEvent>) {
    if let Ok(mut guard) = EVENT_LOOP_PROXY.lock() {
        *guard = Some(proxy);
    }
}

pub(crate) fn clear_event_loop_proxy() {
    if let Ok(mut guard) = EVENT_LOOP_PROXY.lock() {
        guard.take();
    }
}

pub(crate) fn send_user_event(event: UserEvent) {
    let Ok(guard) = EVENT_LOOP_PROXY.lock() else {
        return;
    };
    let Some(proxy) = guard.as_ref() else { return };
    let _ = proxy.send_event(event);
}
