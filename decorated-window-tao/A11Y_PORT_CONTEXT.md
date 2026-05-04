# Tao a11y — branch context (shared between macOS / Windows ports)

This file is the **handoff briefing** for two follow-up sessions running on
real macOS and Windows hosts. It captures what was done on the Linux branch
`wip/tao-experiment` so the same wire format and semantics can be brought to
parity in `objc/a11y.m` (mac) and `windows/nucleus_tao_a11y.c` (win).

Read this first. Then pick up the per-platform plan
(`A11Y_PORT_MACOS.md` or `A11Y_PORT_WINDOWS.md`) in the same directory.

## TL;DR — current state

```
JVM encoder (TaoA11ySnapshotSerializer.kt)  →  wire format v7
            ├── Linux  (a11y_linux.rs)              ← at v7  ✅ shipping
            ├── macOS  (objc/a11y.m)                ← at v4  ❌ broken
            └── Windows (windows/nucleus_tao_a11y.c) ← at v4  ❌ broken
```

Because the JVM encoder bumped from v4 → v6 → v7 over the course of this
branch (Linux feature work) but the macOS / Windows parsers were not
touched, both platforms now silently reject every snapshot. **No
accessibility data reaches VoiceOver or UIA on `wip/tao-experiment`.**

The Linux side is feature-complete and validated end-to-end. The mac/win
ports just need to absorb the wire-format additions; the encoder, observer,
and Kotlin controller are already cross-platform.

## Branch summary (commits to read, newest first)

| Commit | Subject |
|---|---|
| `f0ed55c1` | bump Compose Multiplatform → 1.11.0-beta03 (skiko 0.144 PathBuilder migration) |
| `502df89b` | mirror StaticText label into AccessKit value (Linux name fix) |
| `d07012ba` | sample-tao Complex tab — stress harness for the per-node diff |
| `15a390d0` | true per-node a11y diff on Linux (wire v7) |
| `0bf22822` | elide identical a11y snapshots on Linux |
| `f2c0d058` | clean up Linux a11y issues (gating, race, semantics, testTag) |
| `366d6d33` | parity v6 a11y mac/Win/Linux (initial big push) |

`A11Y_ISSUES.md` tracks the per-item status of every audit finding.

## Architecture (recap)

```
SemanticsOwnerListener
        ↓
TaoSemanticsObserver (Kotlin, shared)
        ↓ TaoA11yNode list (with children)
TaoA11ySnapshotSerializer (Kotlin, shared)        ← writes v7 wire format
        ↓ ByteArray
nativeA11yApplySnapshot   /   nativeA11yApplyPartialSnapshot (Linux only)
        ↓                                ↓
        └─→ Rust / ObjC / C parser  ──→  AccessKit / NSAccessibility / UIA
                                          ↓
                                     AT-SPI / VoiceOver / Narrator
```

- **Observer**: walks Compose `SemanticsNode` tree, computes role / flags /
  actions / label / value / scroll / selection / customActions / testTag /
  children. Cross-platform.
- **Serializer**: writes the wire format. Cross-platform. Currently emits
  v7. **Same encoder feeds all three native parsers.**
- **Native parser**: reads the wire format and projects onto the OS
  accessibility tree. Per-platform.
- **Action dispatch**: runs in reverse — native AX action arrives, JNI
  upcalls into `NativeTaoBridge.dispatchA11y*`, which routes through
  `TaoAccessibilityRegistry` to the controller.

## Wire format v7 (authoritative spec)

All fields little-endian.

### Header (24 bytes)

| Offset | Type | Field      | Meaning                                    |
|--------|------|------------|--------------------------------------------|
| 0      | u32  | magic      | `0xA110A11A`                               |
| 4      | u16  | version    | `7`                                        |
| 6      | u16  | flags      | bit 0 = partial update; bits 1..15 reserved |
| 8      | u32  | nodeCount  | number of node records that follow          |
| 12     | u64  | focusId    | id of the focused node (0 = unset / use root) |
| 20     | u32  | reserved   | must be 0                                   |

`flags & 0x1` == 1 means this buffer is a **partial update** — only the
nodes that changed since the previous push are present. Linux uses this
to drive AccessKit's incremental TreeUpdate. macOS / Windows can either
implement partials too (recommended for parity with Compose-Desktop's
existing diff machinery) or **reject partial buffers and keep doing full
re-projections** — the JVM controller falls back to a full push the next
time it encounters a forced or resync state.

> **Pragmatic recommendation for macOS / Windows**: in the first port,
> reject any buffer with `FLAG_PARTIAL` set. The JVM-side controller
> only emits partials via `nativeA11yApplyPartialSnapshot`, which is
> already a no-op stub on those platforms. The `nativeA11yApplySnapshot`
> entry point only ever receives full snapshots, so this is safe in
> practice — but defensive logging keeps you safe against future encoder
> changes.

### Per-node record (variable length)

```
u64  nodeId
u64  parentId         (0 / -1 / self → root candidate)
u16  role             (TaoA11yRole.code)
u16  flags            (TaoA11yFlag.* bitmask)
u16  actions          (TaoA11yAction.* bitmask)
u16  extraFlags       ★ NEW (was `reserved2` in v4)
                      bit 0 = READ_ONLY  (BasicTextField readOnly = true)
                      bit 1 = INVALID    (SemanticsProperties.Error)
f32  frameX, frameY, frameW, frameH        (window-local logical points)
f32  minValue, maxValue, numericValue
u32  selectionStart, selectionEnd          (UTF-16 code units)
f32  hScrollMax, hScrollValue, vScrollMax, vScrollValue
u16  labelLen + label[labelLen]            (UTF-8, ≤ 65535 bytes — clamped at codepoint boundary)
u16  valueLen + value[valueLen]            (UTF-8, ≤ 65535 bytes — same clamp)
u16  customCount
  per custom action:
    u16 nameLen + name[nameLen]            (UTF-8)
u16  testTagLen + testTag[testTagLen]      ★ NEW v5+ (UTF-8)
u32  childCount                            ★ NEW v7
  per child:
    u64 childId                            ★ NEW v7
```

## What changed vs the v4 parser you already have

| Layer | v4 → v7 change |
|---|---|
| Header | `reserved` u16 → `flags` u16 |
| Header | + new fields: `focusId` u64, `reserved` u32 |
| Per-node | `reserved2` u16 → `extraFlags` u16 (semantic change) |
| Per-node | + trailing `testTag` length-prefixed string |
| Per-node | + trailing children list (`u32 count + u64[] ids`) |
| Topology | v4 derived children from `parentId`. v7 carries children **explicitly**. Either reading approach works for full snapshots; partials require the explicit list. |

Magic stays `0xA110A11A`. Per-node fixed prefix sizes are unchanged
through the `scroll` axes; differences are all about new fields after
that.

## Wire-format constants (Kotlin source of truth)

These are defined in
`src/main/kotlin/io/github/kdroidfilter/nucleus/window/tao/TaoAccessibility.kt`:

```kotlin
object TaoA11yRole(val code: Int) {
  Unknown=0, Group=1, Button=2, StaticText=3, Checkbox=4, RadioButton=5,
  Switch=6, TextField=7, TextArea=8, Slider=9, Progress=10, Image=11,
  ScrollArea=12, Heading=13, Tab=14, PopupMenu=15, Table=16, Outline=17,
  Row=18, Cell=19, SpinButton=20, TabPanel=21, Tooltip=22,
}

object TaoA11yFlag {
  IS_ELEMENT          = 1 shl 0
  ENABLED             = 1 shl 1
  FOCUSED             = 1 shl 2
  SELECTED            = 1 shl 3
  CHECKED             = 1 shl 4
  MIXED               = 1 shl 5
  HEADING             = 1 shl 6
  PASSWORD            = 1 shl 7
  MULTILINE           = 1 shl 8
  MODAL               = 1 shl 9
  LIVE_REGION_POLITE  = 1 shl 10
  LIVE_REGION_ASSERTIVE = 1 shl 11
  MULTI_SELECTABLE    = 1 shl 12
  EXPANDED_TRUE       = 1 shl 13
  EXPANDED_FALSE      = 1 shl 14
  HIDDEN              = 1 shl 15  // reserved; observer never emits
}

object TaoA11yExtraFlag {
  READ_ONLY = 1 shl 0
  INVALID   = 1 shl 1
}

object TaoA11yAction {
  CLICK         = 1 shl 0
  INCREMENT     = 1 shl 1
  DECREMENT     = 1 shl 2
  SET_TEXT      = 1 shl 3
  REQUEST_FOCUS = 1 shl 4
  SCROLL_UP     = 1 shl 5
  SCROLL_DOWN   = 1 shl 6
  SCROLL_LEFT   = 1 shl 7
  SCROLL_RIGHT  = 1 shl 8
  DISMISS       = 1 shl 9
}
```

Match these constants byte-for-byte in your platform parser. Any drift
shows up as silently wrong roles/states.

## Already-merged shared improvements (no porting needed)

These are JVM-side, so macOS / Windows already inherit them once the
parser groks v7:

- **Observer multi-owner reparenting** — `Dialog`/popup
  `SemanticsOwner`s are grafted under the main window's root. Each
  retains its own `IsDialog` flag, so AT clients still see the modal.
- **Toggleable without explicit Role** — promoted to Checkbox before the
  onClick → Button branch.
- **`SemanticsProperties.Error`** — encoded as `extraFlags` bit 1
  (`INVALID`). The platform parser must surface it as the right native
  state (mac: `AXInvalid`, win: `IsRequired*` / `aria-invalid`).
- **TestTag** — encoded per-node. Map to `AXIdentifier` (mac) /
  `AutomationId` (win). Must round-trip as the AT-SPI Accessible.GetAccessibleId on Linux.
- **`disabled-btn` does not get `enabled` / `sensitive`** —
  `TaoA11yFlag.ENABLED` is omitted by the observer when Compose marks
  the node `disabled()`. Mac/Win must invert their existing logic if
  they previously assumed enabled by default.
- **Static-text `name` source** — for nodes with role
  `TaoA11yRole.StaticText`, the wire-format `label` carries the visible
  text. AT-SPI uses `value` for `Role::Label`'s `name`; this is
  Linux-specific and macOS / Windows can keep using their own native
  name slot (`AXTitle` / `Name` property).

## How to verify your port (any platform)

The following test scripts live in `/tmp/` on the Linux dev host. Reproduce
them for your platform — they're written against AT-SPI but the
expected behaviours translate one-to-one:

- **Basic A11y tab** (`A11yTab.kt` in `sample-shared/`) — 34 checks
  covering: app name, role coverage, disabled state, bare-toggleable,
  `Error` flag, slider value, text input, custom actions, modal dialog,
  live region, click counter, tree integrity.
- **Complex stress** (`ComplexTab.kt` in `sample-shared/`) — 32 checks
  covering dynamic todo list (add/remove/reorder/toggle/filter),
  high-frequency partials (auto-ticker), expand/collapse, conditional
  form, rapid topology churn (~22 sequential ops). All mutations should
  reach the AT layer.

The Linux reference implementation passes both suites at 34/34 + 32/32.

Per-platform test recipes are inside the respective port plan
(`A11Y_PORT_MACOS.md`, `A11Y_PORT_WINDOWS.md`).

## Files to read on the Linux branch (cross-platform reference)

```
decorated-window-tao/
├── A11Y_ISSUES.md                       ← per-item status of every fix
├── A11Y_PORT_CONTEXT.md                 ← this file
├── A11Y_PORT_MACOS.md                   ← mac plan
├── A11Y_PORT_WINDOWS.md                 ← win plan
├── src/main/kotlin/.../TaoAccessibility.kt        ← wire encoder + controller
├── src/main/kotlin/.../TaoSemanticsObserver.kt    ← observer (cross-platform logic)
├── src/main/kotlin/.../NativeTaoBridge.kt         ← JNI surface
├── src/main/native/src/a11y_linux.rs              ← Linux Rust parser (REFERENCE IMPLEMENTATION)
├── src/main/native/objc/a11y.m                    ← macOS parser (TO PORT)
└── src/main/native/windows/nucleus_tao_a11y.c     ← Windows parser (TO PORT)
```

The Rust parser at `src/main/native/src/a11y_linux.rs::parse_snapshot` is
the authoritative reader for v7. Read it once, then mirror the same
field order / sizes in your platform parser.

## Pitfalls

1. **The encoder is shared.** Don't add platform-conditional encoding —
   that's what got us here. Any wire-format change must be applied to
   all three parsers in lockstep, or it doesn't ship.
2. **Magic + version are checked first.** Bump the version constant in
   your parser when you start (`SNAPSHOT_VERSION = 7`).
3. **`extraFlags` lives at the offset where v4 had `reserved2`.** No
   layout shift — only semantic. Don't read the byte twice.
4. **TestTag and children are at the END of the per-node record.** They
   come after custom actions. If your existing parser walks the byte
   stream sequentially (e.g. mac's `READ_OR_FAIL` macro), you can append
   the new reads without touching the middle.
5. **AccessKit (Linux) treats `Role::Label` specially**: `name` comes
   from the value slot, not the label slot. macOS and Windows do not
   share that quirk — your existing label/title source stays correct.
   Don't copy the Linux `set_value(label)` mirroring logic verbatim.
6. **Partial updates must currently be no-ops on macOS / Windows** —
   reject them in `nativeA11yApplySnapshot` if `FLAG_PARTIAL` is set,
   and keep the existing `nativeA11yApplyPartialSnapshot` no-op stub.
   The JVM falls back to full snapshots when partials are rejected.
