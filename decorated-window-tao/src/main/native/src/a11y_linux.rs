// Linux accessibility provider for the Tao backend.
//
// Architecture mirrors the macOS / Windows pipeline:
//   - The JVM owns the source-of-truth Compose semantic tree and pushes
//     compact byte-array snapshots through `nativeA11yApplySnapshot` whenever
//     the SemanticsOwnerListener fires.
//   - Each snapshot is decoded into an `accesskit::TreeUpdate` and handed
//     to an `accesskit_unix::Adapter`, which projects it to AT-SPI2 over
//     D-Bus (zbus). Orca, accerciser and other ATs see the tree like any
//     other native AT-SPI2 application.
//   - Accessibility actions (Click, Focus, Set value, Custom action…) come
//     back from AccessKit on a zbus dispatch thread; we forward them to the
//     JVM via the existing `dispatchA11yActionByNsView` upcalls. The Kotlin
//     registry is keyed by an opaque "view handle" that on Linux is the
//     X11 Window XID — same role as NSView on macOS or HWND on Windows.
//
// The wire format is the v4 layout produced by `TaoA11ySnapshotSerializer.kt`.
// Field-by-field documentation lives there; here we re-read the layout in
// lockstep.
//
// IMPORTANT: AT-SPI's `org.a11y.Status.IsEnabled` flag must be true for any
// of this to be observable. accesskit_unix's `run_event_loop` only constructs
// its AT-SPI bus connection (and registers per-node interfaces) when an AT
// has flipped that property. Orca, accerciser, GNOME Settings → Accessibility
// all set it. Headless tests can force it via:
//   `busctl --user set-property org.a11y.Bus /org/a11y/bus org.a11y.Status \
//      IsEnabled b true`
// Without it, `Message::RegisterInterfaces` payloads are silently dropped on
// the executor thread (`accesskit_unix-0.17.2/src/context.rs:220`).

use std::collections::HashMap;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};

use accesskit::{
    Action, ActionData, ActionHandler, ActionRequest, ActivationHandler, CustomAction,
    DeactivationHandler, Live, Node, NodeId, Rect, Role, Toggled, Tree, TreeUpdate,
};
use accesskit_unix::Adapter;
use jni::objects::{JByteArray, JClass, JValue};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::JAVA_VM;

// ── Wire format (kept in sync with TaoA11ySnapshotSerializer.kt) ───────────

const MAGIC: u32 = 0xA110_A11A;
const VERSION: u16 = 4;

// Role codes — match TaoA11yRole.code in TaoAccessibility.kt.
const ROLE_UNKNOWN: u16 = 0;
const ROLE_GROUP: u16 = 1;
const ROLE_BUTTON: u16 = 2;
const ROLE_STATIC_TEXT: u16 = 3;
const ROLE_CHECKBOX: u16 = 4;
const ROLE_RADIO_BUTTON: u16 = 5;
const ROLE_SWITCH: u16 = 6;
const ROLE_TEXT_FIELD: u16 = 7;
const ROLE_TEXT_AREA: u16 = 8;
const ROLE_SLIDER: u16 = 9;
const ROLE_PROGRESS: u16 = 10;
const ROLE_IMAGE: u16 = 11;
const ROLE_SCROLL_AREA: u16 = 12;
const ROLE_HEADING: u16 = 13;
const ROLE_TAB: u16 = 14;
const ROLE_POPUP_MENU: u16 = 15;
const ROLE_TABLE: u16 = 16;
const ROLE_OUTLINE: u16 = 17;
const ROLE_ROW: u16 = 18;
const ROLE_CELL: u16 = 19;

// Flag bits — match TaoA11yFlag in TaoAccessibility.kt. Some are unused on
// the Linux side because AccessKit derives them from role / explicit state
// (e.g. F_IS_ELEMENT, F_ENABLED, F_HEADING, F_MULTILINE), but kept here as
// authoritative documentation of the wire format.
#[allow(dead_code)] const F_IS_ELEMENT: u16 = 1 << 0;
#[allow(dead_code)] const F_ENABLED: u16 = 1 << 1;
const F_FOCUSED: u16 = 1 << 2;
const F_SELECTED: u16 = 1 << 3;
const F_CHECKED: u16 = 1 << 4;
const F_MIXED: u16 = 1 << 5;
#[allow(dead_code)] const F_HEADING: u16 = 1 << 6;
const F_PASSWORD: u16 = 1 << 7;
#[allow(dead_code)] const F_MULTILINE: u16 = 1 << 8;
const F_MODAL: u16 = 1 << 9;
const F_LIVE_POLITE: u16 = 1 << 10;
const F_LIVE_ASSERTIVE: u16 = 1 << 11;
const F_MULTI_SELECTABLE: u16 = 1 << 12;
const F_EXPANDED_TRUE: u16 = 1 << 13;
const F_EXPANDED_FALSE: u16 = 1 << 14;
const F_HIDDEN: u16 = 1 << 15;

// Action bits — match TaoA11yAction.
const A_CLICK: u16 = 1 << 0;
const A_INCREMENT: u16 = 1 << 1;
const A_DECREMENT: u16 = 1 << 2;
const A_SET_TEXT: u16 = 1 << 3;
const A_REQUEST_FOCUS: u16 = 1 << 4;
const A_SCROLL_UP: u16 = 1 << 5;
const A_SCROLL_DOWN: u16 = 1 << 6;
const A_SCROLL_LEFT: u16 = 1 << 7;
const A_SCROLL_RIGHT: u16 = 1 << 8;
const A_DISMISS: u16 = 1 << 9;

// ── State management ──────────────────────────────────────────────────────

struct WindowState {
    /// Last full snapshot. Returned to AccessKit on `request_initial_tree`.
    last_tree: Option<TreeUpdate>,
    /// NodeId → metadata used to interpret AccessKit-side action requests.
    /// Custom-action dispatch uses the index inside `custom_action_count` to
    /// look up the Kotlin-side handler position.
    nodes: HashMap<NodeId, NodeMeta>,
    /// X11 Window XID — opaque "view handle" passed back to Kotlin on every
    /// upcall. Mirrors NSView on macOS / HWND on Windows.
    handle: i64,
    /// Tracks whether at least one AT has connected to the bus. Set on the
    /// first activation request; we use it only as a hint — AccessKit's
    /// `update_if_active` is the actual gate.
    has_been_activated: bool,
}

struct NodeMeta {
    /// Bitmask of TaoA11yAction bits supported by this node.
    actions: u16,
    /// Number of custom actions declared on the node. Used to bounds-check
    /// `ActionData::CustomAction(idx)` before forwarding to Kotlin.
    custom_action_count: usize,
}

struct WindowEntry {
    /// Adapter is the live AT-SPI projection. Drop-on-detach is intentional —
    /// the destructor unregisters the application from the bus.
    adapter: Adapter,
    state: Arc<Mutex<WindowState>>,
}

static WINDOWS: Lazy<Mutex<HashMap<i64, Box<WindowEntry>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

// Global flags consumed by the Kotlin observer. AccessKit's adapter takes
// care of suppressing work when no AT is connected, so we keep these simple
// and let the JVM push at the natural Compose recomposition cadence.
static AT_ACTIVE: AtomicBool = AtomicBool::new(false);
static RESYNC: AtomicBool = AtomicBool::new(false);

// ── Decoder ───────────────────────────────────────────────────────────────

struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }
    fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }
    fn read_u16(&mut self) -> Option<u16> {
        if self.remaining() < 2 {
            return None;
        }
        let v = u16::from_le_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
        self.pos += 2;
        Some(v)
    }
    fn read_u32(&mut self) -> Option<u32> {
        if self.remaining() < 4 {
            return None;
        }
        let v = u32::from_le_bytes([
            self.buf[self.pos],
            self.buf[self.pos + 1],
            self.buf[self.pos + 2],
            self.buf[self.pos + 3],
        ]);
        self.pos += 4;
        Some(v)
    }
    fn read_i32(&mut self) -> Option<i32> {
        self.read_u32().map(|v| v as i32)
    }
    fn read_i64(&mut self) -> Option<i64> {
        if self.remaining() < 8 {
            return None;
        }
        let mut bytes = [0u8; 8];
        bytes.copy_from_slice(&self.buf[self.pos..self.pos + 8]);
        self.pos += 8;
        Some(i64::from_le_bytes(bytes))
    }
    fn read_f32(&mut self) -> Option<f32> {
        self.read_u32().map(f32::from_bits)
    }
    fn read_str(&mut self) -> Option<String> {
        let len = self.read_u16()? as usize;
        if self.remaining() < len {
            return None;
        }
        let s = String::from_utf8_lossy(&self.buf[self.pos..self.pos + len]).into_owned();
        self.pos += len;
        Some(s)
    }
}

fn role_from_code(code: u16, flags: u16) -> Role {
    let is_password = flags & F_PASSWORD != 0;
    match code {
        ROLE_GROUP => Role::Group,
        ROLE_BUTTON => Role::Button,
        ROLE_STATIC_TEXT => Role::Label,
        ROLE_CHECKBOX => Role::CheckBox,
        ROLE_RADIO_BUTTON => Role::RadioButton,
        ROLE_SWITCH => Role::Switch,
        ROLE_TEXT_FIELD => {
            if is_password {
                Role::PasswordInput
            } else {
                Role::TextInput
            }
        }
        ROLE_TEXT_AREA => Role::MultilineTextInput,
        ROLE_SLIDER => Role::Slider,
        ROLE_PROGRESS => Role::ProgressIndicator,
        ROLE_IMAGE => Role::Image,
        ROLE_SCROLL_AREA => Role::ScrollView,
        ROLE_HEADING => Role::Heading,
        ROLE_TAB => Role::Tab,
        ROLE_POPUP_MENU => Role::MenuListPopup,
        ROLE_TABLE => Role::Table,
        ROLE_OUTLINE => Role::Tree,
        ROLE_ROW => Role::Row,
        ROLE_CELL => Role::Cell,
        ROLE_UNKNOWN | _ => Role::Unknown,
    }
}

/// Parse one snapshot and produce both an AccessKit `TreeUpdate` and the
/// per-node metadata used to interpret incoming action requests.
fn parse_snapshot(buf: &[u8]) -> Option<(TreeUpdate, HashMap<NodeId, NodeMeta>, NodeId)> {
    let mut c = Cursor::new(buf);
    if c.read_u32()? != MAGIC {
        return None;
    }
    if c.read_u16()? != VERSION {
        return None;
    }
    let _reserved = c.read_u16()?;
    let count = c.read_u32()? as usize;

    // Parent → child accumulator. We populate it on the first pass and
    // commit `set_children(...)` on the second.
    let mut child_lists: HashMap<NodeId, Vec<NodeId>> = HashMap::with_capacity(count);
    let mut nodes: Vec<(NodeId, Node)> = Vec::with_capacity(count);
    let mut metas: HashMap<NodeId, NodeMeta> = HashMap::with_capacity(count);
    let mut all_ids: Vec<NodeId> = Vec::with_capacity(count);

    let mut root_id: Option<NodeId> = None;
    let mut focus_id: Option<NodeId> = None;

    for _ in 0..count {
        let id_raw = c.read_i64()?;
        let parent_raw = c.read_i64()?;
        let role_code = c.read_u16()?;
        let flags = c.read_u16()?;
        let actions = c.read_u16()?;
        let _reserved2 = c.read_u16()?;
        let frame_x = c.read_f32()? as f64;
        let frame_y = c.read_f32()? as f64;
        let frame_w = c.read_f32()? as f64;
        let frame_h = c.read_f32()? as f64;
        let v_min = c.read_f32()? as f64;
        let v_max = c.read_f32()? as f64;
        let v_now = c.read_f32()? as f64;
        let _sel_start = c.read_i32()?;
        let _sel_end = c.read_i32()?;
        let h_scroll_max = c.read_f32()? as f64;
        let h_scroll_value = c.read_f32()? as f64;
        let v_scroll_max = c.read_f32()? as f64;
        let v_scroll_value = c.read_f32()? as f64;
        let label = c.read_str()?;
        let value_str = c.read_str()?;
        let custom_count = c.read_u16()? as usize;
        let mut custom_labels: Vec<String> = Vec::with_capacity(custom_count);
        for _ in 0..custom_count {
            custom_labels.push(c.read_str()?);
        }

        let node_id = NodeId(id_raw as u64);
        all_ids.push(node_id);
        let role = role_from_code(role_code, flags);
        let mut node = Node::new(role);

        // Window-relative bounds in logical pixels. The adapter combines
        // these with the outer/inner geometry pushed via
        // `set_root_window_bounds` to produce screen-space coordinates.
        if frame_w > 0.0 && frame_h > 0.0 {
            node.set_bounds(Rect {
                x0: frame_x,
                y0: frame_y,
                x1: frame_x + frame_w,
                y1: frame_y + frame_h,
            });
        }

        if !label.is_empty() {
            node.set_label(label.clone());
        }

        // Password fields: never expose cleartext on the bus, regardless of
        // what Compose passes through. AccessKit's PasswordInput role tells
        // ATs to suppress speech of the value, but we additionally mask.
        let is_password = flags & F_PASSWORD != 0;
        if !value_str.is_empty() {
            if is_password {
                node.set_value("•".repeat(value_str.chars().count()));
            } else {
                node.set_value(value_str);
            }
        }

        // Numeric value range — sliders, progress bars, scroll bars.
        if v_max > v_min {
            node.set_min_numeric_value(v_min);
            node.set_max_numeric_value(v_max);
            node.set_numeric_value(v_now);
            // For sliders, advertise SetValue so AT-SPI's
            // `Value.SetCurrentValue` reaches our action handler.
            if matches!(role, Role::Slider) && actions & A_INCREMENT != 0 {
                node.add_action(Action::SetValue);
            }
        }

        // Heading level. Compose's Heading semantic doesn't carry a level,
        // so we default to 2 (the most common UI heading depth) when the
        // role is Heading. This is what ATs read via the `level:N` attribute
        // in `Accessible.GetAttributes()`.
        if matches!(role, Role::Heading) {
            node.set_level(2);
        }

        // Scroll axes. AccessKit requires both min and max to be set for
        // each axis; min is implicitly 0 in our wire format.
        if h_scroll_max > 0.0 {
            node.set_scroll_x_min(0.0);
            node.set_scroll_x_max(h_scroll_max);
            node.set_scroll_x(h_scroll_value);
        }
        if v_scroll_max > 0.0 {
            node.set_scroll_y_min(0.0);
            node.set_scroll_y_max(v_scroll_max);
            node.set_scroll_y(v_scroll_value);
        }

        // Tri-state checkbox / switch / radio.
        let is_toggleable = matches!(
            role,
            Role::CheckBox | Role::Switch | Role::RadioButton
        );
        if is_toggleable {
            let toggled = if flags & F_MIXED != 0 {
                Toggled::Mixed
            } else if flags & F_CHECKED != 0 {
                Toggled::True
            } else {
                Toggled::False
            };
            node.set_toggled(toggled);
        }

        if flags & F_LIVE_ASSERTIVE != 0 {
            node.set_live(Live::Assertive);
        } else if flags & F_LIVE_POLITE != 0 {
            node.set_live(Live::Polite);
        }
        if flags & F_MODAL != 0 {
            node.set_modal();
            // Compose's `IsDialog` semantic doesn't carry a Compose-side role,
            // so the encoder defaults to TaoA11yRole.Group → Role::Group.
            // Override here so AT-SPI exposes role="dialog" instead of
            // "filler" — Orca's structural-navigation expects this.
            if matches!(role, Role::Group) {
                node.set_role(Role::Dialog);
            }
        }
        if flags & F_HIDDEN != 0 {
            node.set_hidden();
        }
        if flags & F_SELECTED != 0 {
            node.set_selected(true);
        }
        if flags & F_MULTI_SELECTABLE != 0 {
            node.set_multiselectable();
        }
        if flags & F_EXPANDED_TRUE != 0 {
            node.set_expanded(true);
        } else if flags & F_EXPANDED_FALSE != 0 {
            node.set_expanded(false);
        }

        // Track focus. The wire format flags the focused node directly.
        if flags & F_FOCUSED != 0 {
            focus_id = Some(node_id);
        }

        // Standard actions. AccessKit consumers (i.e. AT-SPI Action.GetActions)
        // only see actions explicitly added; missing ones become unsupported.
        if actions & A_CLICK != 0 {
            node.add_action(Action::Click);
        }
        if actions & A_REQUEST_FOCUS != 0 {
            node.add_action(Action::Focus);
        }
        if actions & A_INCREMENT != 0 {
            node.add_action(Action::Increment);
        }
        if actions & A_DECREMENT != 0 {
            node.add_action(Action::Decrement);
        }
        if actions & A_SET_TEXT != 0 {
            // ReplaceSelectedText is the closest match in AccessKit 0.21:
            // ActionData::Value(Box<str>) carries the new text. Orca's
            // edit-text path uses this for `setAccessibilityValue:` upcalls.
            node.add_action(Action::ReplaceSelectedText);
        }
        if actions & (A_SCROLL_UP | A_SCROLL_DOWN | A_SCROLL_LEFT | A_SCROLL_RIGHT) != 0 {
            node.add_action(Action::ScrollIntoView);
            node.add_action(Action::SetScrollOffset);
            node.add_action(Action::ScrollUp);
            node.add_action(Action::ScrollDown);
            node.add_action(Action::ScrollLeft);
            node.add_action(Action::ScrollRight);
        }
        let _ = A_DISMISS; // No AT-SPI equivalent — silently ignored.

        // Expand / collapse — Compose tags these via the corresponding
        // semantic actions, which we already mapped to bits above; here we
        // expose them as AccessKit actions so AT-SPI can invoke them.
        if flags & F_EXPANDED_FALSE != 0 {
            node.add_action(Action::Expand);
        }
        if flags & F_EXPANDED_TRUE != 0 {
            node.add_action(Action::Collapse);
        }

        // Custom actions: AccessKit `CustomAction { id, description }`. The
        // id is a per-node 0-based index that we round-trip back to the JVM
        // verbatim — Kotlin uses the same ordering on the controller's
        // `customActions` list.
        if !custom_labels.is_empty() {
            let custom: Vec<CustomAction> = custom_labels
                .iter()
                .enumerate()
                .map(|(i, label)| CustomAction {
                    id: i as i32,
                    description: label.clone().into(),
                })
                .collect();
            node.set_custom_actions(custom);
            node.add_action(Action::CustomAction);
        }

        // Decide root vs child. The Kotlin walker emits the SemanticsOwner
        // root with `parentId = 0`, so the *first* node in the snapshot is
        // by construction the tree root — but we don't depend on order;
        // instead we treat any node whose declared parent is 0 / -1 / self
        // as a root candidate. The first such node wins.
        let is_root_candidate = parent_raw == 0 || parent_raw == -1 || parent_raw == id_raw;
        if is_root_candidate {
            if root_id.is_none() {
                root_id = Some(node_id);
            } else {
                // Multiple roots can happen when several SemanticsOwner are
                // active (popups). Re-parent extras to the established root
                // so AT-SPI sees a single tree.
                child_lists
                    .entry(root_id.unwrap())
                    .or_default()
                    .push(node_id);
            }
        } else {
            child_lists
                .entry(NodeId(parent_raw as u64))
                .or_default()
                .push(node_id);
        }

        metas.insert(
            node_id,
            NodeMeta {
                actions,
                custom_action_count: custom_count,
            },
        );

        nodes.push((node_id, node));
    }

    let root_id = root_id?;

    // Second pass: install children + force `Role::Window` on the tree root.
    // accesskit_atspi_common only emits the AT-SPI `window_created` event for
    // roots whose role is exactly `Role::Window` (see
    // accesskit_atspi_common-0.14.2/src/adapter.rs:65). Without this override,
    // Orca / accerciser see only the AccessKit application stub, not our
    // actual UI tree.
    let mut nodes_with_children: Vec<(NodeId, Node)> = Vec::with_capacity(nodes.len());
    for (id, mut node) in nodes {
        if id == root_id {
            node.set_role(Role::Window);
        }
        if let Some(kids) = child_lists.remove(&id) {
            node.set_children(kids);
        }
        nodes_with_children.push((id, node));
    }

    let focus = focus_id.unwrap_or(root_id);
    let update = TreeUpdate {
        nodes: nodes_with_children,
        tree: Some(Tree::new(root_id)),
        focus,
    };
    Some((update, metas, root_id))
}

// ── AccessKit handlers ────────────────────────────────────────────────────

/// Invoked the first time an AT connects, then again whenever the platform
/// adapter wants a full re-broadcast (typically when the AT reconnects).
struct ActivationProxy {
    state: Arc<Mutex<WindowState>>,
}

impl ActivationHandler for ActivationProxy {
    fn request_initial_tree(&mut self) -> Option<TreeUpdate> {
        AT_ACTIVE.store(true, Ordering::Relaxed);
        RESYNC.store(true, Ordering::Relaxed);
        let mut st = self.state.lock().ok()?;
        st.has_been_activated = true;
        // Clone the cached tree if we have one. A `None` here is fine —
        // AccessKit will retry; the Kotlin observer's `pendingForcedPush`
        // ensures it pushes a fresh snapshot on the next event-loop tick.
        st.last_tree.clone()
    }
}

struct DeactivationProxy {
    state: Arc<Mutex<WindowState>>,
}

impl DeactivationHandler for DeactivationProxy {
    fn deactivate_accessibility(&mut self) {
        AT_ACTIVE.store(false, Ordering::Relaxed);
        if let Ok(mut st) = self.state.lock() {
            // Drop the cached tree — when the AT comes back, `request_initial_tree`
            // should produce a fresh snapshot, not a stale one.
            st.last_tree = None;
        }
    }
}

struct ActionProxy {
    state: Arc<Mutex<WindowState>>,
}

impl ActionHandler for ActionProxy {
    fn do_action(&mut self, request: ActionRequest) {
        let (handle, meta_actions, custom_count) = {
            let st = match self.state.lock() {
                Ok(s) => s,
                Err(_) => return,
            };
            let Some(meta) = st.nodes.get(&request.target) else {
                return;
            };
            (st.handle, meta.actions, meta.custom_action_count)
        };
        forward_action_to_jvm(handle, request, meta_actions, custom_count);
    }
}

fn forward_action_to_jvm(
    handle: i64,
    request: ActionRequest,
    meta_actions: u16,
    custom_count: usize,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    let Ok(mut env) = jvm.attach_current_thread() else { return };
    let class_name = "io/github/kdroidfilter/nucleus/window/tao/NativeTaoBridge";
    let node_id = request.target.0 as i64;

    // Map back to the bitmask the Kotlin controller already understands.
    // Compose-side scroll handling expects a SCROLL_UP/DOWN/LEFT/RIGHT bit
    // chosen by the AT direction; AccessKit's `SetScrollOffset` carries an
    // explicit point we forward via `dispatchA11yScrollBy`.
    let action_bit: i32 = match request.action {
        Action::Click => A_CLICK as i32,
        Action::Focus => A_REQUEST_FOCUS as i32,
        Action::Blur => 0,
        Action::Increment => A_INCREMENT as i32,
        Action::Decrement => A_DECREMENT as i32,
        Action::ScrollUp => A_SCROLL_UP as i32,
        Action::ScrollDown => A_SCROLL_DOWN as i32,
        Action::ScrollLeft => A_SCROLL_LEFT as i32,
        Action::ScrollRight => A_SCROLL_RIGHT as i32,
        Action::Expand => A_CLICK as i32, // routed through the click handler
        Action::Collapse => A_CLICK as i32,
        _ => 0,
    };

    let class = match env.find_class(class_name) {
        Ok(c) => c,
        Err(_) => return,
    };

    match request.action {
        Action::ReplaceSelectedText => {
            if meta_actions & A_SET_TEXT == 0 {
                return;
            }
            let text = match request.data {
                Some(ActionData::Value(s)) => s.into_string(),
                _ => return,
            };
            let Ok(jstr) = env.new_string(&text) else { return };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetText",
                "(JJLjava/lang/String;)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Object(&jstr.into()),
                ],
            );
        }
        Action::SetValue => {
            // Slider / progress value setter. AT-SPI's
            // `Value.SetCurrentValue(d)` dispatches this with NumericValue.
            // Compose's `SemanticsActions.SetProgress` expects the absolute
            // value in the slider's own range (clamped on the JVM side).
            let value = match request.data {
                Some(ActionData::NumericValue(v)) => v,
                _ => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetValue",
                "(JJD)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Double(value),
                ],
            );
        }
        Action::SetScrollOffset | Action::ScrollToPoint => {
            // Both carry a target point; forward as an absolute scroll
            // delta. The Kotlin observer handles `onScrollBy(dx, dy)` by
            // passing through to `SemanticsActions.ScrollBy`.
            let (dx, dy) = match request.data {
                Some(ActionData::SetScrollOffset(p)) => (p.x as f32, p.y as f32),
                Some(ActionData::ScrollToPoint(p)) => (p.x as f32, p.y as f32),
                _ => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11yScrollBy",
                "(JJFF)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Float(dx),
                    JValue::Float(dy),
                ],
            );
        }
        Action::SetTextSelection => {
            let sel = match request.data {
                Some(ActionData::SetTextSelection(s)) => s,
                _ => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetSelection",
                "(JJII)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Int(sel.anchor.character_index as i32),
                    JValue::Int(sel.focus.character_index as i32),
                ],
            );
        }
        Action::CustomAction => {
            let idx = match request.data {
                Some(ActionData::CustomAction(i)) => i,
                _ => return,
            };
            if idx < 0 || (idx as usize) >= custom_count {
                return;
            }
            let _ = env.call_static_method(
                class,
                "dispatchA11yCustomAction",
                "(JJI)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Int(idx),
                ],
            );
        }
        _ => {
            if action_bit == 0 {
                return;
            }
            let _ = env.call_static_method(
                class,
                "dispatchA11yActionByNsView",
                "(JJI)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Int(action_bit),
                ],
            );
        }
    }
}

// ── JNI entry points ──────────────────────────────────────────────────────
//
// Names mirror the macOS/Windows JNI exports declared on
// `NativeTaoBridge.kt`. The JVM caches the X11 Window XID at attach time and
// uses it as the opaque `nsView` handle on every subsequent call, so we
// don't have to lock back into the Tao `WINDOWS` map from the AT-SPI side.

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yAttach(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    if map.contains_key(&handle) {
        return;
    }
    let state = Arc::new(Mutex::new(WindowState {
        last_tree: None,
        nodes: HashMap::new(),
        handle,
        has_been_activated: false,
    }));
    let activation = ActivationProxy {
        state: Arc::clone(&state),
    };
    let action = ActionProxy {
        state: Arc::clone(&state),
    };
    let deactivation = DeactivationProxy {
        state: Arc::clone(&state),
    };
    let adapter = Adapter::new(activation, action, deactivation);
    map.insert(
        handle,
        Box::new(WindowEntry {
            adapter,
            state,
        }),
    );
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yDetach(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let _ = map.remove(&handle);
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yApplySnapshot(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bytes: JByteArray,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let len = match env.get_array_length(&bytes) {
        Ok(n) if n > 0 => n as usize,
        _ => return JNI_FALSE,
    };
    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_err() {
        return JNI_FALSE;
    }
    // Reinterpret as &[u8] — JNI gives us i8 (Java byte is signed).
    let bytes_u8: &[u8] = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const u8, len) };

    let parsed = match parse_snapshot(bytes_u8) {
        Some(p) => p,
        None => return JNI_FALSE,
    };
    let (update, new_nodes, _root) = parsed;

    let map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let Some(entry) = map.get(&handle) else {
        return JNI_FALSE;
    };
    // Stash the latest tree before pushing — the activation handler may
    // race with us on a separate thread and clone whatever is current.
    {
        let mut st = match entry.state.lock() {
            Ok(s) => s,
            Err(_) => return JNI_FALSE,
        };
        st.last_tree = Some(update.clone());
        st.nodes = new_nodes;
    }
    // SAFETY: `update_if_active` requires `&mut Adapter`. The adapter is
    // owned exclusively by us (the WINDOWS map is the sole owner), but we
    // hold it through a `Box<WindowEntry>` behind a `Mutex<HashMap<...>>`
    // — the outer lock is enough to serialize.
    //
    // We need a `&mut Adapter`, so we have to bypass the immutable
    // `HashMap::get` view. Workaround: drop the immutable borrow and
    // re-acquire mutably.
    drop(map);
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return JNI_FALSE;
    };
    entry.adapter.update_if_active(|| update);
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // AccessKit's `update_if_active` is the real gate; we just hint the
    // Kotlin observer to keep pushing. Returning true unconditionally is
    // the same heuristic as the Windows path.
    if AT_ACTIVE.load(Ordering::Relaxed) {
        JNI_TRUE
    } else {
        // Keep the Kotlin "pendingForcedPush" path alive so the *first*
        // snapshot lands even before any AT connects, populating
        // `state.last_tree` for the next `request_initial_tree`.
        JNI_TRUE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yConsumeResync(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if RESYNC.swap(false, Ordering::Relaxed) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yNotePushed(
    _env: JNIEnv,
    _class: JClass,
) {
    // No bookkeeping needed — `AT_ACTIVE` and `RESYNC` are managed inside
    // the activation/deactivation handlers. Implemented for parity with
    // the macOS export so the Kotlin controller's call doesn't fail with
    // UnsatisfiedLinkError.
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yIsVoiceOverRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // No screen-reader-detect API on Linux; report false. ATs that talk
    // AT-SPI are detected through `AT_ACTIVE` instead, which the activation
    // handler flips on first connect.
    JNI_FALSE
}

#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11yPostFocusChanged(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    node_id: jlong,
) {
    if handle == 0 {
        return;
    }
    // Build a focus-only TreeUpdate. AccessKit needs a `tree` field set on
    // the *first* update only — incremental focus changes can omit it. We
    // include the cached `Tree::new(root)` to cover the case where this
    // call runs before any snapshot has been pushed.
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return;
    };
    let cached_tree = {
        let st = match entry.state.lock() {
            Ok(s) => s,
            Err(_) => return,
        };
        st.last_tree.as_ref().and_then(|u| u.tree.clone())
    };
    let target = NodeId(node_id as u64);
    entry.adapter.update_if_active(|| TreeUpdate {
        nodes: Vec::new(),
        tree: cached_tree,
        focus: target,
    });
}

/// Optional: push outer/inner window geometry so AT-SPI screen-coordinate
/// queries (e.g. flat review) align with the actual on-screen position.
/// Called from Kotlin on `onResized` and `onWindowReady`. Coordinates are
/// in physical pixels relative to the X root window — i.e. raw XWayland
/// values; AccessKit applies them as-is to its Component.GetExtents
/// implementation.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11ySetRootBounds(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    outer_x: jlong,
    outer_y: jlong,
    outer_w: jlong,
    outer_h: jlong,
    inner_x: jlong,
    inner_y: jlong,
    inner_w: jlong,
    inner_h: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return;
    };
    let outer = Rect {
        x0: outer_x as f64,
        y0: outer_y as f64,
        x1: (outer_x + outer_w) as f64,
        y1: (outer_y + outer_h) as f64,
    };
    let inner = Rect {
        x0: inner_x as f64,
        y0: inner_y as f64,
        x1: (inner_x + inner_w) as f64,
        y1: (inner_y + inner_h) as f64,
    };
    entry.adapter.set_root_window_bounds(outer, inner);
}

/// Updates the window-focus state inside AccessKit. Called from Kotlin's
/// `onFocusChanged` so AT-SPI's `STATE_ACTIVE` flag on the toplevel matches
/// the actual X focus.
#[no_mangle]
pub extern "system" fn Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoBridge_nativeA11ySetWindowFocus(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    focused: jboolean,
) {
    if handle == 0 {
        return;
    }
    let mut map = match WINDOWS.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(entry) = map.get_mut(&handle) else {
        return;
    };
    entry.adapter.update_window_focus_state(focused != JNI_FALSE);
}
