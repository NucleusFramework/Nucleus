// Shared wire-format decoder: Kotlin TaoA11ySnapshotSerializer (v7) → AccessKit TreeUpdate.
// Used by Linux (accesskit_unix) and Windows (accesskit_windows) projectors.

use std::collections::HashMap;

use accesskit::{
    Action, CustomAction, Live, Node, NodeId, Rect, Role, TextPosition, TextSelection, Toggled,
    Tree, TreeId, TreeUpdate,
};

// ── Wire format (kept in sync with TaoA11ySnapshotSerializer.kt) ───────────

const MAGIC: u32 = 0xA110_A11A;
const VERSION: u16 = 7;
// Header `flags` field: bit 0 = partial update.
const FLAG_PARTIAL: u16 = 0x0001;

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
const ROLE_SPIN_BUTTON: u16 = 20;
const ROLE_TAB_PANEL: u16 = 21;
const ROLE_TOOLTIP: u16 = 22;

// Flag bits — match TaoA11yFlag in TaoAccessibility.kt. Some are unused on
// the Linux side because AccessKit derives them from role / explicit state
// (e.g. F_IS_ELEMENT, F_ENABLED, F_HEADING, F_MULTILINE), but kept here as
// authoritative documentation of the wire format.
#[allow(dead_code)] const F_IS_ELEMENT: u16 = 1 << 0;
const F_ENABLED: u16 = 1 << 1;
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

// Extra flags (carried in what was `reserved2` before wire format v6).
const EF_READ_ONLY: u16 = 1 << 0;
const EF_INVALID: u16 = 1 << 1;

// Action bits — match TaoA11yAction.
pub const A_CLICK: u16 = 1 << 0;
pub const A_INCREMENT: u16 = 1 << 1;
pub const A_DECREMENT: u16 = 1 << 2;
pub const A_SET_TEXT: u16 = 1 << 3;
pub const A_REQUEST_FOCUS: u16 = 1 << 4;
pub const A_SCROLL_UP: u16 = 1 << 5;
pub const A_SCROLL_DOWN: u16 = 1 << 6;
pub const A_SCROLL_LEFT: u16 = 1 << 7;
pub const A_SCROLL_RIGHT: u16 = 1 << 8;
#[allow(dead_code)]
pub const A_DISMISS: u16 = 1 << 9;

pub struct NodeMeta {
    /// Bitmask of TaoA11yAction bits supported by this node.
    pub actions: u16,
    /// Number of custom actions declared on the node. Used to bounds-check
    /// `ActionData::CustomAction(idx)` before forwarding to Kotlin.
    pub custom_action_count: usize,
}

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
        ROLE_SPIN_BUTTON => Role::SpinButton,
        ROLE_TAB_PANEL => Role::TabPanel,
        ROLE_TOOLTIP => Role::Tooltip,
        ROLE_UNKNOWN | _ => Role::Unknown,
    }
}

/// Result of decoding one wire-format v7 buffer.
pub struct ParsedSnapshot {
    pub update: TreeUpdate,
    pub metas: HashMap<NodeId, NodeMeta>,
    pub root_id: Option<NodeId>,
    pub is_partial: bool,
}

/// Parse one snapshot and produce both an AccessKit `TreeUpdate` and the
/// per-node metadata used to interpret incoming action requests.
pub fn parse_snapshot(buf: &[u8]) -> Option<ParsedSnapshot> {
    let mut c = Cursor::new(buf);
    let magic = c.read_u32()?;
    if magic != MAGIC {
        eprintln!(
            "[a11y] snapshot rejected: magic {:#010x} != expected {:#010x}",
            magic, MAGIC
        );
        return None;
    }
    let version = c.read_u16()?;
    if version != VERSION {
        // Version skew is almost always JAR/.so build skew during dev — log
        // loudly so it's visible without having to attach a debugger.
        eprintln!(
            "[a11y] snapshot rejected: wire format v{} (Rust expects v{}). \
             JAR / .so version skew — rebuild both sides.",
            version, VERSION
        );
        return None;
    }
    let flags = c.read_u16()?;
    let is_partial = flags & FLAG_PARTIAL != 0;
    let count = c.read_u32()? as usize;
    let header_focus_raw = c.read_i64()?;
    let _reserved = c.read_u32()?;

    let mut nodes: Vec<(NodeId, Node)> = Vec::with_capacity(count);
    let mut metas: HashMap<NodeId, NodeMeta> = HashMap::with_capacity(count);

    let mut root_id: Option<NodeId> = None;
    let mut focus_id: Option<NodeId> = if header_focus_raw > 0 {
        Some(NodeId(header_focus_raw as u64))
    } else {
        None
    };

    for _ in 0..count {
        let id_raw = c.read_i64()?;
        let parent_raw = c.read_i64()?;
        let role_code = c.read_u16()?;
        let flags = c.read_u16()?;
        let actions = c.read_u16()?;
        // Wire format v6+ uses what was `reserved2` for an extra-flags u16.
        // Bit 0 = read-only (Compose `BasicTextField(readOnly = true)`).
        let extra_flags = c.read_u16()?;
        let frame_x = c.read_f32()? as f64;
        let frame_y = c.read_f32()? as f64;
        let frame_w = c.read_f32()? as f64;
        let frame_h = c.read_f32()? as f64;
        let v_min = c.read_f32()? as f64;
        let v_max = c.read_f32()? as f64;
        let v_now = c.read_f32()? as f64;
        let sel_start = c.read_i32()?;
        let sel_end = c.read_i32()?;
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
        // testTag (Compose `Modifier.testTag`) — wire format v5+. Routed to
        // AccessKit's `set_author_id`, which AT-SPI surfaces as
        // Accessible.GetAccessibleId(). This matches `AXIdentifier` (macOS)
        // and `AutomationId` (Windows UIA), so test runners and screen
        // readers can identify widgets symbolically.
        let test_tag = c.read_str()?;

        let node_id = NodeId(id_raw as u64);
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
        // AccessKit's `Role::Label` (our ROLE_STATIC_TEXT mapping) routes
        // AT-SPI `Accessible.name` through `value()` instead of `label()`
        // — see `accesskit_consumer::Node::label_comes_from_value` which
        // returns `true` for `Role::Label`. Without mirroring the label
        // into the value slot, `BasicText` content reaches the bus as an
        // empty `name`, leaving Orca / Accerciser to announce the role
        // ("label") with no accompanying text. Mirror only when no
        // separate value is already carried (text inputs / progress
        // surfaces own the value field).

        if !test_tag.is_empty() {
            node.set_author_id(test_tag);
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
        } else if role_code == ROLE_STATIC_TEXT && !label.is_empty() {
            // See the comment above `node.set_label`: `Role::Label` reads
            // its accessible name from the value slot, so for plain
            // static-text nodes we mirror the label into the value when
            // no other value is carried (e.g. live regions whose
            // StateDescription already populated `value_str`).
            node.set_value(label.clone());
        }

        // Compose's `SemanticsProperties.TextSelectionRange` (start, end) maps
        // to AccessKit's TextSelection. Setting it on the input node itself
        // (rather than on a child TextRun, which Compose doesn't produce)
        // lets the vendored adapter diff `raw_text_selection()` between
        // snapshots and emit `object:text-caret-moved` events for AT-SPI.
        if matches!(role, Role::TextInput | Role::MultilineTextInput | Role::PasswordInput)
            && (sel_start >= 0 || sel_end >= 0)
        {
            let pos_start = sel_start.max(0) as usize;
            let pos_end = sel_end.max(0) as usize;
            node.set_text_selection(TextSelection {
                anchor: TextPosition {
                    node: node_id,
                    character_index: pos_start,
                },
                focus: TextPosition {
                    node: node_id,
                    character_index: pos_end,
                },
            });
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
        // F_ENABLED is set by the Kotlin observer when the Compose semantic
        // does NOT contain `Disabled`. Inverting here so AccessKit (and
        // therefore AT-SPI's STATE_ENABLED / STATE_SENSITIVE) correctly
        // reflects Compose's `Modifier.semantics { disabled() }` and the
        // default `enabled = false` parameter on Material widgets.
        if flags & F_ENABLED == 0 {
            node.set_disabled();
        }
        // Read-only text inputs (BasicTextField with readOnly = true). The
        // observer detects this from the absence of SemanticsActions.SetText.
        // AccessKit's `is_read_only_supported` returns true for text-input
        // roles, so the vendored fork's `state()` will then insert
        // State::ReadOnly.
        if extra_flags & EF_READ_ONLY != 0 {
            node.set_read_only();
        }
        // Compose `SemanticsProperties.Error` — invalid form-field value.
        // AT-SPI exposes this as `STATE_INVALID_ENTRY`; AccessKit's
        // `set_invalid` produces the matching state.
        if extra_flags & EF_INVALID != 0 {
            node.set_invalid(accesskit::Invalid::True);
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

        // Children list (wire format v7+): explicit ids of direct children.
        // For full snapshots this lets the parser skip the previous
        // parent_id-based child accumulation pass; for partial snapshots
        // it's the only correct topology source (the full tree isn't
        // present in the buffer).
        let child_count = c.read_u32()? as usize;
        let mut children: Vec<NodeId> = Vec::with_capacity(child_count);
        for _ in 0..child_count {
            children.push(NodeId(c.read_i64()? as u64));
        }
        if !children.is_empty() {
            node.set_children(children);
        }

        // Root candidate: parent = 0, -1 or self. The first wins; extras
        // (e.g. popups in additional SemanticsOwners) are tolerated but
        // not re-parented here — Compose's observer only enables one
        // SemanticsOwner at a time on the Tao path, so multi-root
        // snapshots in practice mean the encoder is misbehaving.
        let is_root_candidate = parent_raw == 0 || parent_raw == -1 || parent_raw == id_raw;
        if is_root_candidate && root_id.is_none() {
            root_id = Some(node_id);
        }

        // Per-node focused bit (kept for backwards diagnosis); the header
        // `focusId` is authoritative when set.
        if focus_id.is_none() && (flags & F_FOCUSED) != 0 {
            focus_id = Some(node_id);
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

    // Force `Role::Window` on the tree root. accesskit_atspi_common only
    // emits the AT-SPI `window_created` event for roots whose role is
    // exactly `Role::Window` (see
    // accesskit_atspi_common-0.14.2/src/adapter.rs:65). Without this
    // override, Orca / accerciser see only the AccessKit application stub,
    // not our actual UI tree. Skipped in partial mode — the root is
    // already in AccessKit's cache from the seeding full push.
    if !is_partial {
        if let Some(rid) = root_id {
            for (id, node) in nodes.iter_mut() {
                if *id == rid {
                    node.set_role(Role::Window);
                    break;
                }
            }
        }
    }

    // Build the TreeUpdate.
    //  - Full: include `Tree::new(root)` (initialises AccessKit's tree
    //    metadata + toolkit name).
    //  - Partial: omit `tree` so AccessKit treats the update as
    //    incremental and keeps its existing root + tree metadata.
    let tree = if is_partial {
        None
    } else {
        let mut t = Tree::new(root_id?);
        t.toolkit_name = Some("Compose Multiplatform".to_string());
        Some(t)
    };
    // Focus: header takes precedence; fall back to F_FOCUSED bit; finally
    // fall back to the root for full snapshots (a Tree without a focused
    // node is invalid). Partial updates may legitimately not carry the
    // focused node; AccessKit then keeps the previous focus assignment if
    // we reuse it.
    let focus = focus_id
        .or(root_id)
        .unwrap_or(NodeId(0));
    // Single-tree embedder: everything lives in the root tree. AccessKit's
    // multi-tree support (0.23+) only matters for grafted subtrees, which the
    // Compose bridge doesn't produce.
    let update = TreeUpdate {
        nodes,
        tree,
        tree_id: TreeId::ROOT,
        focus,
    };
    Some(ParsedSnapshot {
        update,
        metas,
        root_id,
        is_partial,
    })
}
