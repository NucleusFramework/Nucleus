# Tao a11y — macOS port plan (objc/a11y.m → wire format v7)

> Read [`A11Y_PORT_CONTEXT.md`](./A11Y_PORT_CONTEXT.md) first. It is the
> shared briefing for both the macOS and Windows handoff sessions and
> contains the wire format spec.

You're picking up `wip/tao-experiment` on a real macOS host. Linux is
shipping at v7; macOS is silently broken because `objc/a11y.m` is still
parsing v4. Goal: bring it back to working state at v7.

## What you're touching

```
decorated-window-tao/
└── src/main/native/objc/a11y.m   ← all changes here, ~150 LOC delta
```

`build.sh` lives at `src/main/native/macos/build.sh`. It clears the
`NativeLibraryLoader` cache and rebuilds `libnucleus_tao_a11y.dylib` for
both arm64 and x64, then drops them in
`src/main/resources/nucleus/native/{darwin-x64,darwin-aarch64}/`.

You probably also want to read:
- The Linux reference parser:
  `src/main/native/src/a11y_linux.rs` — `parse_snapshot` is the
  authoritative v7 reader.
- The wire-format spec:
  `decorated-window-tao/A11Y_PORT_CONTEXT.md` § "Wire format v7".
- The current macOS parser:
  `objc/a11y.m::apply_snapshot_bytes` (around line 1075).
- Sample apps that exercise the projection:
  `sample-shared/src/.../A11yTab.kt` and `ComplexTab.kt`.

## Step 1 — bump the version constant + header layout

Top of `a11y.m`:

```objc
static const uint16_t kSnapshotVersion = 4;
```

**Change to**:

```objc
static const uint16_t kSnapshotVersion = 7;
#define NUCLEUS_A11Y_FLAG_PARTIAL 0x0001u
```

Update the `// Wire format` comment block (lines ~30-55) to match v7
(see context doc for the full layout).

## Step 2 — extend the header reader

Currently `apply_snapshot_bytes` reads:

```objc
uint16_t version = 0, reserved = 0;
READ_OR_FAIL(&version, 2);
READ_OR_FAIL(&reserved, 2);
if (version != kSnapshotVersion) return NO;
uint32_t nodeCount = 0;
READ_OR_FAIL(&nodeCount, 4);
```

**Replace with**:

```objc
uint16_t version = 0, flags = 0;
READ_OR_FAIL(&version, 2);
READ_OR_FAIL(&flags, 2);
if (version != kSnapshotVersion) return NO;
uint32_t nodeCount = 0;
READ_OR_FAIL(&nodeCount, 4);
uint64_t headerFocusId = 0;
READ_OR_FAIL(&headerFocusId, 8);
uint32_t headerReserved = 0;
READ_OR_FAIL(&headerReserved, 4);
(void)headerReserved;

// Partial updates are Linux-only on this branch. The Kotlin controller
// only emits them via `nativeA11yApplyPartialSnapshot`, which is a
// no-op on macOS — but defensive logging makes future encoder drift
// visible during dev.
if (flags & NUCLEUS_A11Y_FLAG_PARTIAL) {
    NSLog(@"[nucleus.a11y] full apply rejected: buffer carries FLAG_PARTIAL");
    return NO;
}
```

`headerFocusId`: prefer this over the `flags & NucleusA11yFlagFocused`
per-node fallback if it's non-zero. The current parser uses
`newFocusedId` populated from the per-node `flags`. You can either
ignore `headerFocusId` and keep the per-node fallback (still works in
v7), or use it as the canonical source. Recommend the latter — it's
what the Linux side does.

## Step 3 — rename `reserved2` → `extraFlags` + decode bits

In the per-node first pass (around line 1109):

```objc
uint16_t role = 0, flags = 0, actions = 0, reserved2 = 0;
…
READ_OR_FAIL(&reserved2, 2);
```

**Rename**:

```objc
uint16_t role = 0, flags = 0, actions = 0, extraFlags = 0;
…
READ_OR_FAIL(&extraFlags, 2);
```

Then wire the two bits:

```objc
#define NUCLEUS_A11Y_EFLAG_READ_ONLY  0x0001u
#define NUCLEUS_A11Y_EFLAG_INVALID    0x0002u
```

Carry them on `NucleusA11yElement` (add two `BOOL` properties: `readOnly`,
`invalidEntry`) and surface them through NSAccessibility:

| Flag | NSAccessibility attribute |
|---|---|
| `EFLAG_READ_ONLY` | implement `accessibilityIsAttributeSettable:` to return NO for `NSAccessibilityValueAttribute`, and have the role-specific protocols (`NSAccessibilityProtectedContentElement` doesn't apply — use `NSAccessibilityIsEditable`) report read-only |
| `EFLAG_INVALID` | implement `accessibilityInvalid` (returns `BOOL`) — VoiceOver announces "invalid" |

The `NSAccessibilityInvalid` API: macOS 11+ exposes
`-accessibilityInvalid` returning a `NSAccessibilityInvalidValue`-typed
string (`@"true"` / `@"false"` / `@"grammar"` / `@"spelling"`). For now
emit `@"true"` when `EFLAG_INVALID` is set.

The same `extraFlags` slot also needs to be re-read in the second
pass (the children-link pass) — the existing `off2 += 36` skip
arithmetic stays correct because `extraFlags` is u16 (same size as
`reserved2` was). Don't change the offsets.

## Step 4 — append the new per-node trailing fields

After the custom-actions block, the v7 record adds:

```
u16  testTagLen + testTag[testTagLen]    (UTF-8)
u32  childCount + (u64 childId)*
```

In the **first pass** (the one that actually parses everything):

```objc
// after the customCount loop:
uint16_t testTagLen = 0;
READ_OR_FAIL(&testTagLen, 2);
if (offset + testTagLen > len) return NO;
NSString *testTag = [[NSString alloc] initWithBytes:bytes + offset
                                             length:testTagLen
                                           encoding:NSUTF8StringEncoding] ?: @"";
offset += testTagLen;
uint32_t childCount = 0;
READ_OR_FAIL(&childCount, 4);
NSMutableArray<NSNumber *> *childIds = nil;
if (childCount > 0) {
    childIds = [NSMutableArray arrayWithCapacity:childCount];
    for (uint32_t k = 0; k < childCount; k++) {
        uint64_t cid = 0;
        READ_OR_FAIL(&cid, 8);
        [childIds addObject:@(cid)];
    }
}
```

Stash `testTag` on the element (new `@property NSString *testTag`),
expose it as `NSAccessibilityIdentifierAttribute`:

```objc
- (NSString *)accessibilityIdentifier { return self.testTag ?: @""; }
```

For children: the existing v4 code derives `childElements` from
`parentId`. v7 carries them explicitly, but you can keep the
`parentId`-based topology build for full snapshots (it still works) —
just **read the bytes off the stream** so the cursor stays in sync. If
you want to use the explicit list, use it as a fallback when `parentId`
linkage produces an orphan.

In the **second pass** (the one that re-walks to build `childElements`,
around line 1232 with the `off2 += …` skip arithmetic), add the same
read sequence to keep the cursor aligned:

```objc
// after the customCount loop:
uint16_t testTagLen = 0;
memcpy(&testTagLen, bytes + off2, 2); off2 += 2;
off2 += testTagLen;
uint32_t childCount = 0;
memcpy(&childCount, bytes + off2, 4); off2 += 4;
off2 += childCount * 8;
```

## Step 5 — Compose has-no-`enabled`-by-default semantic

The Linux observer change made `TaoA11yFlag.ENABLED` an opt-in: it's
omitted when Compose marks the node `disabled()`. macOS / Windows
already inherit this — but inspect any code path in `a11y.m` that
defaults to "enabled" without checking the flag, and invert it to
"enabled = `(flags & NucleusA11yFlagEnabled) != 0`".

The current parser at `objc/a11y.m::accessibilityEnabled` etc. needs to
honour the new bit (it's `1 << 1`, same as before — semantics unchanged,
just verify).

## Step 6 — clean up the wire-format comment

Replace the v4 comment block at the top of `a11y.m` (lines 30-55) with:

```objc
// ── Wire format v7 (must match TaoA11ySnapshotSerializer on the Kotlin side) ──
//
//   Header (24 bytes):
//     u32 magic    = 0xA110A11A
//     u16 version  = 7
//     u16 flags    (bit 0 = partial; rejected here — partials are Linux-only)
//     u32 nodeCount
//     u64 focusId  (0 = no explicit focus, fall back to root or per-node bit)
//     u32 reserved
//
//   Per node:
//     u64 nodeId
//     u64 parentId       (0 = root → child of TaoView)
//     u16 role           (NucleusA11yRole)
//     u16 flags          (NucleusA11yFlag)
//     u16 actions        (NucleusA11yAction)
//     u16 extraFlags     (bit 0 = READ_ONLY, bit 1 = INVALID)
//     f32 frameX,Y,W,H   (window-local logical points, top-left origin)
//     f32 minValue, maxValue, value
//     u32 selectionStart (UTF-16 code unit, 0 if not a text field)
//     u32 selectionEnd
//     f32 hScrollMax, hScrollValue
//     f32 vScrollMax, vScrollValue
//     u16 labelLen + label
//     u16 valueLen + value
//     u16 customCount
//       per custom action: u16 nameLen + name
//     u16 testTagLen + testTag       (Compose Modifier.testTag → AXIdentifier)
//     u32 childCount + u64 childIds  (explicit topology — partials only)
//
// All multi-byte fields little-endian.
```

## Step 7 — build + test

```sh
cd decorated-window-tao/src/main/native
./macos/build.sh
```

The script clears `~/Library/Caches/nucleus/native/<arch>/` for you.
Run `sample-tao` from the repo root:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :sample-tao:run
```

### Manual smoke test

1. Open **System Settings → Accessibility → VoiceOver** and turn it on.
   Or use **Accessibility Inspector** (`xcode-select --install` then
   `open -a "Accessibility Inspector"`).
2. Click through the **A11y** tab in the demo. Verify:
   - The disabled "Cannot press" button is announced as disabled.
   - The "Bare toggleable" checkbox toggles state when activated.
   - The email field announces as invalid when its content has no `@`.
   - The "Increment" button advances the click counter (visible label
     change should be picked up by VO).
   - Custom actions on "Notification — VO+Cmd+." appear in the
     VO+Cmd+. menu.
3. Click into the **Complex** tab:
   - Add / remove / toggle todo items — counts and state should update
     in Accessibility Inspector live view.
   - Reorder via the up/down arrows — items reflow in the AX tree.
   - Filter input narrows the visible items.
   - Auto-ticker — value increments rapidly; AX value should follow.
   - Expand / collapse groups — body subtree should appear / disappear.
   - Mode radios — basic ↔ advanced ↔ off — different checkboxes show.

### Scripted test (osascript / AXObserver)

The Linux reference uses Python AT-SPI bindings; macOS doesn't ship an
equivalent in the box but you can drive most of the same checks via
`osascript` + System Events:

```applescript
tell application "System Events"
    tell process "Sample Tao"
        click button "A11y" of window 1
        set theBtn to first button of window 1 whose description is "Increment"
        click theBtn
        return value of static text "click counter 1" of window 1
    end tell
end tell
```

For deeper / programmatic checks, write a small Swift CLI that uses
`AXUIElementCopyAttributeValue` against `AXFocusedApplication` — same
shape as the Python AT-SPI scripts in `/tmp/a11y_*.py`.

The expected pass-rates are 34/34 on the basic A11y tab and 32/32 on
the Complex tab. Anything below means a bit / role / topology mismatch
in the parser.

## Common pitfalls (mac-specific)

1. **Notification posting requires the parser to commit `proj.byId =
   next` BEFORE flushing notifications.** The current code already does
   this — don't reorder. VoiceOver re-queries after each notification
   and would otherwise see stale state.
2. **`accessibilityNotifiesWhenDestroyed = YES`** must remain set on
   removed elements before `NSAccessibilityUIElementDestroyedNotification`
   — otherwise VoiceOver caches a dangling pointer.
3. **`AXIdentifier` is read very rarely** by VoiceOver itself but is
   the primary handle for UI Automation / XCUITest. Don't gate it
   behind any visibility filter — empty string is fine, missing is not.
4. **Liquid Glass / NSAccessibility threading**: every AX query lands
   on the AppKit main thread. Don't take cross-thread locks during
   notification posting — the existing code is single-threaded by
   design.
5. **`AXInvalid` predates the modern `accessibilityInvalid`** API;
   support both for backwards compatibility on older macOS releases the
   project still targets (check `decorated-window-tao/build.gradle.kts`
   for `macOsSdkVersion`).

## Out of scope

- Implementing AT-SPI-style partial updates on macOS. Compose-Desktop
  doesn't have a moral equivalent on AppKit; the existing diffing
  parser already does the right thing on every full push.
- Liquid Glass-specific visual a11y (focus rings, etc.).
- `AXSelection` for non-text widgets — already partially wired.

## What "done" looks like

- `kSnapshotVersion = 7` and the parser reads the new fields.
- All 34 checks of the basic A11y harness pass on a real macOS run.
- All 32 checks of the Complex harness pass.
- No NSLog spam other than the intentional `FLAG_PARTIAL rejected`
  warning.
- Accessibility Inspector shows full role coverage, named labels, and
  state transitions on click.
- A commit landed on `wip/tao-experiment` of the form
  `feat(decorated-window-tao): bring macOS a11y parser to wire format v7`.
