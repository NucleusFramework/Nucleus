// Follows the compositor's decoration decision on KDE's server-decoration
// protocol (`org_kde_kwin_server_decoration`, #816).
//
// GTK 3 announces client-side decorations through that protocol and binds the
// reply, but its `mode` handler only reacts to `client` / `server`: a `none`
// answer — KWin on Plasma Mobile, or a "No titlebar and frame" window rule —
// is dropped, and GTK exposes neither the object nor the mode. So the window
// gets a decoration object of its own, created after GTK's on every map, which
// requests the same `client` mode GTK did. KWin keeps the most recently created
// object of a surface as *the* decoration of the window
// (`XdgToplevelWindow::installServerDecoration`): it reads its preference from
// ours, which is GTK's, and sends every later mode change to ours.
//
// Only windows GTK decorates client-side (`csd` style class) get one: for the
// others GTK asked for `server`, and a `client` request on top would take the
// compositor's frame away. Compositors without the global (Mutter) cost one
// registry roundtrip per process and nothing else.
//
// Everything here runs on the GTK main thread: the decoration proxy lives on
// GDK's default queue, so GDK's own dispatch calls `on_mode`.

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{c_char, c_int, c_void, CStr};
use std::ptr;

use glib::translate::ToGlibPtr;
use gtk::prelude::*;
use once_cell::sync::OnceCell;

use crate::events::{dispatch, EVENT_DECORATIONS_SUPPRESSED};

#[repr(C)]
struct WlProxy {
    _opaque: [u8; 0],
}

#[repr(C)]
struct WlEventQueue {
    _opaque: [u8; 0],
}

#[repr(C)]
struct WlMessage {
    name: *const c_char,
    signature: *const c_char,
    types: *const *const WlInterface,
}

#[repr(C)]
struct WlInterface {
    name: *const c_char,
    version: c_int,
    method_count: c_int,
    methods: *const WlMessage,
    event_count: c_int,
    events: *const WlMessage,
}

type MarshalFlags =
    unsafe extern "C" fn(*mut WlProxy, u32, *const WlInterface, u32, u32, ...) -> *mut WlProxy;

/// The libwayland-client entry points GDK already loaded, plus the protocol
/// tables. libwayland is not linked: GDK brings it into the process.
struct Wayland {
    marshal_flags: MarshalFlags,
    add_listener: unsafe extern "C" fn(*mut WlProxy, *const *const c_void, *mut c_void) -> c_int,
    destroy: unsafe extern "C" fn(*mut WlProxy),
    set_queue: unsafe extern "C" fn(*mut WlProxy, *mut WlEventQueue),
    get_version: unsafe extern "C" fn(*mut WlProxy) -> u32,
    create_queue: unsafe extern "C" fn(*mut WlProxy) -> *mut WlEventQueue,
    roundtrip_queue: unsafe extern "C" fn(*mut WlProxy, *mut WlEventQueue) -> c_int,
    queue_destroy: unsafe extern "C" fn(*mut WlEventQueue),
    registry_interface: *const WlInterface,
    manager_interface: *const WlInterface,
    decoration_interface: *const WlInterface,
}

// The tables are immutable once built and the functions are thread-safe; the
// raw pointers only keep the compiler from seeing it.
unsafe impl Send for Wayland {}
unsafe impl Sync for Wayland {}

const WL_MARSHAL_FLAG_DESTROY: u32 = 1;
const WL_DISPLAY_GET_REGISTRY: u32 = 1;
const WL_REGISTRY_BIND: u32 = 0;
const MANAGER_CREATE: u32 = 0;
const DECORATION_RELEASE: u32 = 0;
const DECORATION_REQUEST_MODE: u32 = 1;
const MODE_NONE: u32 = 0;
const MODE_CLIENT: u32 = 1;
const MANAGER_NAME: &CStr = c"org_kde_kwin_server_decoration_manager";

fn wayland() -> Option<&'static Wayland> {
    static WAYLAND: OnceCell<Option<Wayland>> = OnceCell::new();
    WAYLAND.get_or_init(|| unsafe { load_wayland() }).as_ref()
}

unsafe fn load_wayland() -> Option<Wayland> {
    // RTLD_NOLOAD: only ever the copy GDK uses, never a second one.
    let lib = libc::dlopen(
        c"libwayland-client.so.0".as_ptr(),
        libc::RTLD_NOW | libc::RTLD_NOLOAD,
    );
    if lib.is_null() {
        return None;
    }
    let sym = |name: &CStr| {
        let p = libc::dlsym(lib, name.as_ptr());
        (!p.is_null()).then_some(p)
    };
    let registry_interface = sym(c"wl_registry_interface")? as *const WlInterface;
    let surface_interface = sym(c"wl_surface_interface")? as *const WlInterface;

    // Hand-written tables of server-decoration.xml (version 1), the same
    // layout wayland-scanner generates. Built once and kept for the process.
    let leak = |v: Vec<*const WlInterface>| Box::leak(v.into_boxed_slice()).as_ptr();
    let no_types = leak(vec![ptr::null(); 2]);
    let decoration_interface: &'static mut WlInterface = Box::leak(Box::new(WlInterface {
        name: c"org_kde_kwin_server_decoration".as_ptr(),
        version: 1,
        method_count: 2,
        methods: Box::leak(Box::new([
            WlMessage {
                name: c"release".as_ptr(),
                signature: c"".as_ptr(),
                types: no_types,
            },
            WlMessage {
                name: c"request_mode".as_ptr(),
                signature: c"u".as_ptr(),
                types: no_types,
            },
        ]))
        .as_ptr(),
        event_count: 1,
        events: Box::leak(Box::new([WlMessage {
            name: c"mode".as_ptr(),
            signature: c"u".as_ptr(),
            types: no_types,
        }]))
        .as_ptr(),
    }));
    let create_types = leak(vec![
        decoration_interface as *const WlInterface,
        surface_interface,
    ]);
    let manager_interface: &'static WlInterface = Box::leak(Box::new(WlInterface {
        name: MANAGER_NAME.as_ptr(),
        version: 1,
        method_count: 1,
        methods: Box::leak(Box::new([WlMessage {
            name: c"create".as_ptr(),
            signature: c"no".as_ptr(),
            types: create_types,
        }]))
        .as_ptr(),
        event_count: 1,
        events: Box::leak(Box::new([WlMessage {
            name: c"default_mode".as_ptr(),
            signature: c"u".as_ptr(),
            types: no_types,
        }]))
        .as_ptr(),
    }));

    Some(Wayland {
        marshal_flags: std::mem::transmute::<*mut c_void, MarshalFlags>(sym(
            c"wl_proxy_marshal_flags",
        )?),
        add_listener: std::mem::transmute(sym(c"wl_proxy_add_listener")?),
        destroy: std::mem::transmute(sym(c"wl_proxy_destroy")?),
        set_queue: std::mem::transmute(sym(c"wl_proxy_set_queue")?),
        get_version: std::mem::transmute(sym(c"wl_proxy_get_version")?),
        create_queue: std::mem::transmute(sym(c"wl_display_create_queue")?),
        roundtrip_queue: std::mem::transmute(sym(c"wl_display_roundtrip_queue")?),
        queue_destroy: std::mem::transmute(sym(c"wl_event_queue_destroy")?),
        registry_interface,
        manager_interface,
        decoration_interface,
    })
}

thread_local! {
    /// `wl_display*` → its bound manager, or null when the compositor has none.
    static MANAGERS: RefCell<HashMap<usize, usize>> = RefCell::new(HashMap::new());
    /// Window handle → its decoration object and the last state reported.
    static DECORATIONS: RefCell<HashMap<u64, Decoration>> = RefCell::new(HashMap::new());
}

struct Decoration {
    /// Null while the window is unmapped.
    proxy: *mut WlProxy,
    /// What the JVM was last told. Outlives the proxy: a re-map creates a new
    /// object, and its first answer is only reported if it differs.
    suppressed: bool,
}

/// Follows the decoration mode of `gtk_window` from its next map on, reporting
/// changes as `EVENT_DECORATIONS_SUPPRESSED` for `handle`. Popups are not
/// toplevels and have no decoration.
pub(crate) fn watch(handle: u64, gtk_window: &gtk::Window) {
    gtk_window.connect_map(move |window| attach(handle, window));
    gtk_window.connect_unmap(move |_| detach(handle));
    gtk_window.connect_destroy(move |_| {
        detach(handle);
        DECORATIONS.with(|d| d.borrow_mut().remove(&handle));
    });
}

fn attach(handle: u64, window: &gtk::Window) {
    // The map handler runs after GTK's class handler: the surface, the
    // toplevel and GTK's own decoration object already exist.
    if !window.style_context().has_class("csd") {
        return;
    }
    let display = window.display();
    if !display.backend().is_wayland() {
        return;
    }
    let Some(gdk_window) = window.window() else {
        return;
    };
    let Some(wl) = wayland() else { return };
    unsafe {
        let gdk_display_ptr: *mut gtk::gdk::ffi::GdkDisplay = display.to_glib_none().0;
        let gdk_window_ptr: *mut gtk::gdk::ffi::GdkWindow = gdk_window.to_glib_none().0;
        let wl_display =
            gdk_wayland_sys::gdk_wayland_display_get_wl_display(gdk_display_ptr as *mut _)
                as *mut WlProxy;
        let surface = gdk_wayland_sys::gdk_wayland_window_get_wl_surface(gdk_window_ptr as *mut _);
        if wl_display.is_null() || surface.is_null() {
            return;
        }
        let manager = manager_for(wl, wl_display);
        if manager.is_null() {
            return;
        }
        detach(handle);
        let proxy = (wl.marshal_flags)(
            manager,
            MANAGER_CREATE,
            wl.decoration_interface,
            (wl.get_version)(manager),
            0,
            ptr::null_mut::<c_void>(),
            surface,
        );
        if proxy.is_null() {
            return;
        }
        static LISTENER: [unsafe extern "C" fn(*mut c_void, *mut WlProxy, u32); 1] = [on_mode];
        (wl.add_listener)(
            proxy,
            LISTENER.as_ptr() as *const *const c_void,
            handle as usize as *mut c_void,
        );
        (wl.marshal_flags)(
            proxy,
            DECORATION_REQUEST_MODE,
            ptr::null(),
            (wl.get_version)(proxy),
            0,
            MODE_CLIENT,
        );
        DECORATIONS.with(|d| {
            d.borrow_mut()
                .entry(handle)
                .or_insert(Decoration {
                    proxy: ptr::null_mut(),
                    suppressed: false,
                })
                .proxy = proxy
        });
    }
}

fn detach(handle: u64) {
    let proxy = DECORATIONS.with(|d| {
        d.borrow_mut()
            .get_mut(&handle)
            .map(|it| std::mem::replace(&mut it.proxy, ptr::null_mut()))
    });
    let Some(proxy) = proxy.filter(|it| !it.is_null()) else {
        return;
    };
    let Some(wl) = wayland() else { return };
    unsafe {
        (wl.marshal_flags)(
            proxy,
            DECORATION_RELEASE,
            ptr::null(),
            (wl.get_version)(proxy),
            WL_MARSHAL_FLAG_DESTROY,
        );
    }
}

unsafe extern "C" fn on_mode(data: *mut c_void, proxy: *mut WlProxy, mode: u32) {
    let handle = data as usize as u64;
    let changed = DECORATIONS.with(|d| {
        let mut d = d.borrow_mut();
        let Some(decoration) = d.get_mut(&handle).filter(|it| it.proxy == proxy) else {
            return None;
        };
        // `server` is no reason to drop the client's chrome: KWin only sends it
        // while it still resolves our request, or under a rule that forces its
        // own frame, which GTK's CSD keeps drawing under anyway.
        let suppressed = mode == MODE_NONE;
        (decoration.suppressed != suppressed).then(|| {
            decoration.suppressed = suppressed;
            suppressed
        })
    });
    if let Some(suppressed) = changed {
        dispatch(handle, EVENT_DECORATIONS_SUPPRESSED, suppressed as i32, 0);
    }
}

/// Binds the server-decoration manager of `wl_display` once, on a private
/// queue so the roundtrip dispatches none of GDK's events, then moves it to
/// the default queue its decoration objects inherit.
unsafe fn manager_for(wl: &Wayland, wl_display: *mut WlProxy) -> *mut WlProxy {
    if let Some(manager) = MANAGERS.with(|m| m.borrow().get(&(wl_display as usize)).copied()) {
        return manager as *mut WlProxy;
    }
    let manager = bind_manager(wl, wl_display);
    MANAGERS.with(|m| m.borrow_mut().insert(wl_display as usize, manager as usize));
    manager
}

unsafe fn bind_manager(wl: &Wayland, wl_display: *mut WlProxy) -> *mut WlProxy {
    let queue = (wl.create_queue)(wl_display);
    if queue.is_null() {
        return ptr::null_mut();
    }
    let registry = (wl.marshal_flags)(
        wl_display,
        WL_DISPLAY_GET_REGISTRY,
        wl.registry_interface,
        (wl.get_version)(wl_display),
        0,
        ptr::null_mut::<c_void>(),
    );
    if registry.is_null() {
        (wl.queue_destroy)(queue);
        return ptr::null_mut();
    }
    (wl.set_queue)(registry, queue);

    struct Bind<'a> {
        wl: &'a Wayland,
        manager: *mut WlProxy,
    }
    unsafe extern "C" fn global(
        data: *mut c_void,
        registry: *mut WlProxy,
        name: u32,
        interface: *const c_char,
        _version: u32,
    ) {
        let bind = &mut *(data as *mut Bind);
        if bind.manager.is_null() && CStr::from_ptr(interface) == MANAGER_NAME {
            bind.manager = (bind.wl.marshal_flags)(
                registry,
                WL_REGISTRY_BIND,
                bind.wl.manager_interface,
                1,
                0,
                name,
                MANAGER_NAME.as_ptr(),
                1u32,
                ptr::null_mut::<c_void>(),
            );
        }
    }
    unsafe extern "C" fn global_remove(_: *mut c_void, _: *mut WlProxy, _: u32) {}
    let listener: [*const c_void; 2] = [global as *const c_void, global_remove as *const c_void];
    let mut bind = Bind {
        wl,
        manager: ptr::null_mut(),
    };
    (wl.add_listener)(
        registry,
        listener.as_ptr(),
        &mut bind as *mut Bind as *mut c_void,
    );
    let ok = (wl.roundtrip_queue)(wl_display, queue) >= 0;
    // The registry must not outlive `bind` and `listener`, which it points at.
    (wl.destroy)(registry);
    let manager = bind.manager;
    if !manager.is_null() {
        if ok {
            (wl.set_queue)(manager, ptr::null_mut());
        } else {
            (wl.destroy)(manager);
        }
    }
    (wl.queue_destroy)(queue);
    if ok {
        manager
    } else {
        ptr::null_mut()
    }
}
