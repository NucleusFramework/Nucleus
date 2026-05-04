# Tao a11y — Windows port plan (windows/nucleus_tao_a11y.c → wire format v7)

> Read [`A11Y_PORT_CONTEXT.md`](./A11Y_PORT_CONTEXT.md) first. It is the
> shared briefing for both the macOS and Windows handoff sessions and
> contains the wire format spec.

You're picking up `wip/tao-experiment` on a real Windows host. Linux is
shipping at v7; Windows is silently broken because
`windows/nucleus_tao_a11y.c` is still parsing v4. Goal: bring it back
to working state at v7 with proper UIA mapping for the new fields.

## What you're touching

```
decorated-window-tao/
└── src/main/native/windows/nucleus_tao_a11y.c   ← all changes here, ~200 LOC delta
```

`build.bat` lives at `src/main/native/windows/build.bat`. It builds
`nucleus_tao_a11y.dll` for both x64 and aarch64 with MSVC cl.exe and
drops the binaries in
`src/main/resources/nucleus/native/{win32-x64,win32-aarch64}/`. It also
clears the `NativeLibraryLoader` cache at
`%LOCALAPPDATA%\nucleus\native\<arch>\` so the freshly-built DLL loads
on the next JVM start.

You probably also want to read:
- The Linux reference parser (Rust):
  `src/main/native/src/a11y_linux.rs::parse_snapshot` — authoritative
  v7 reader.
- The wire-format spec: `A11Y_PORT_CONTEXT.md` § "Wire format v7".
- The current Windows parser: `apply_snapshot_bytes` in
  `nucleus_tao_a11y.c` (around line 595).
- Sample apps: `sample-shared/src/.../A11yTab.kt` and `ComplexTab.kt`.

## Step 1 — bump the version constant

Top of `nucleus_tao_a11y.c`:

```c
#define SNAPSHOT_MAGIC   0xA110A11Au
#define SNAPSHOT_VERSION 4
```

**Change**:

```c
#define SNAPSHOT_MAGIC   0xA110A11Au
#define SNAPSHOT_VERSION 7
#define SNAPSHOT_FLAG_PARTIAL 0x0001u
```

## Step 2 — extend the header reader

Currently `apply_snapshot_bytes` (around line 595):

```c
uint16_t version = 0, reserved = 0;
memcpy(&version, bytes + offset, 2); offset += 2;
memcpy(&reserved, bytes + offset, 2); offset += 2;
if (version != SNAPSHOT_VERSION) return FALSE;
uint32_t nodeCount = 0;
memcpy(&nodeCount, bytes + offset, 4); offset += 4;
```

**Replace with**:

```c
uint16_t version = 0, headerFlags = 0;
memcpy(&version, bytes + offset, 2); offset += 2;
memcpy(&headerFlags, bytes + offset, 2); offset += 2;
if (version != SNAPSHOT_VERSION) return FALSE;
uint32_t nodeCount = 0;
memcpy(&nodeCount, bytes + offset, 4); offset += 4;
uint64_t headerFocusId = 0;
memcpy(&headerFocusId, bytes + offset, 8); offset += 8;
uint32_t headerReserved = 0;
memcpy(&headerReserved, bytes + offset, 4); offset += 4;
(void)headerReserved;

/* Partial updates are Linux-only on this branch. The Kotlin controller
 * only emits them via nativeA11yApplyPartialSnapshot, which is a no-op
 * on Windows — but reject defensively so encoder drift surfaces. */
if (headerFlags & SNAPSHOT_FLAG_PARTIAL) {
    return FALSE;
}
```

`headerFocusId`: prefer over the per-node `flags & FLAG_FOCUSED`
fallback when non-zero. The current parser scans `flags` per-node —
keep that as fallback.

## Step 3 — rename `reserved2` → `extraFlags` + decode bits

Around line 643:

```c
uint16_t role = 0, flags = 0, actions = 0, reserved2 = 0;
…
memcpy(&reserved2, bytes + offset, 2); offset += 2;
```

**Rename**:

```c
uint16_t role = 0, flags = 0, actions = 0, extraFlags = 0;
…
memcpy(&extraFlags, bytes + offset, 2); offset += 2;
```

Add definitions near the top:

```c
#define EXTRA_FLAG_READ_ONLY  0x0001u
#define EXTRA_FLAG_INVALID    0x0002u
```

Carry `extraFlags` (or just the two bools `readOnly`, `invalidEntry`)
on the per-element struct (look for the existing `Element` struct
declaration in the file — adjacent to where `flags` is stored).

## Step 4 — surface the new flags through UIA

UIA exposes equivalent properties for both new bits:

| Wire flag | UIA property | Property ID |
|---|---|---|
| `EXTRA_FLAG_READ_ONLY` | `IsReadOnlyAttribute` (Value pattern) | uses the existing `UIA_ValuePatternId` `IsReadOnly` getter |
| `EXTRA_FLAG_INVALID`   | `IsDataValidForFormPropertyId` (returns `false` when invalid) and `AriaPropertiesAttribute` `invalid:true` | `30070` (`UIA_IsDataValidForFormPropertyId`) |

Concretely:

1. **READ_ONLY** — find your Value pattern implementation
   (`IValueProvider::get_IsReadOnly`). Currently it likely returns
   `VARIANT_FALSE` unconditionally. Change it to return `VARIANT_TRUE`
   when `extraFlags & EXTRA_FLAG_READ_ONLY` is set on the element.

2. **INVALID** — implement `IRawElementProviderSimple::GetPropertyValue`
   for `UIA_IsDataValidForFormPropertyId`:

   ```c
   #define UIA_IsDataValidForFormPropertyId 30070
   #define UIA_AriaPropertiesPropertyId     30102
   ```

   ```c
   case UIA_IsDataValidForFormPropertyId: {
       VariantInit(pRetVal);
       pRetVal->vt = VT_BOOL;
       pRetVal->boolVal = (el->extraFlags & EXTRA_FLAG_INVALID)
                          ? VARIANT_FALSE : VARIANT_TRUE;
       return S_OK;
   }
   case UIA_AriaPropertiesPropertyId: {
       if (el->extraFlags & EXTRA_FLAG_INVALID) {
           VariantInit(pRetVal);
           pRetVal->vt = VT_BSTR;
           pRetVal->bstrVal = SysAllocString(L"invalid=true");
           return S_OK;
       }
       /* fall through to existing default */
       break;
   }
   ```

   Narrator picks up `IsDataValidForForm = FALSE` and announces
   "invalid".

## Step 5 — append the new per-node trailing fields

After the custom-actions block (around line 680-700), v7 adds:

```
u16  testTagLen + testTag[testTagLen]    (UTF-8)
u32  childCount + (u64 childId)*
```

Read them after the customs loop:

```c
/* testTag (Compose Modifier.testTag) — wire format v5+ */
uint16_t testTagLen = 0;
memcpy(&testTagLen, bytes + offset, 2); offset += 2;
if (offset + testTagLen > len) {
    /* cleanup customActions, label, valueStr, return failure */
    ...
}
wchar_t *testTag = utf8_to_utf16_alloc(bytes + offset, testTagLen);
offset += testTagLen;

/* Children list — wire format v7+ */
uint32_t childCount = 0;
memcpy(&childCount, bytes + offset, 4); offset += 4;
uint64_t *childIds = NULL;
if (childCount > 0) {
    childIds = (uint64_t *)xalloc(sizeof(uint64_t) * childCount);
    for (uint32_t k = 0; k < childCount; k++) {
        memcpy(&childIds[k], bytes + offset, 8); offset += 8;
    }
}
```

Stash `testTag` on the element struct and surface it as the UIA
`AutomationId`:

```c
case UIA_AutomationIdPropertyId: {
    if (el->testTag && el->testTag[0]) {
        VariantInit(pRetVal);
        pRetVal->vt = VT_BSTR;
        pRetVal->bstrVal = SysAllocString(el->testTag);
        return S_OK;
    }
    break;
}
```

For `childIds`: the existing v4 parser builds the parent → child
linkage from `parentId`. v7 carries it explicitly, but you can keep
using `parentId`-based linkage for full snapshots — just **read the
bytes off the stream so the cursor stays in sync**. If you decide to
switch to the explicit list (recommended for clarity), do so in the
fragment-tree navigation (`Navigate(NavigateDirection)`) implementation.

If your code has a second pass that re-scans the buffer (some
implementations do, similar to mac's `off2` skip arithmetic), update
that pass too with the same trailing-field skip:

```c
/* second pass — match the byte advance */
uint16_t testTagLen2 = 0;
memcpy(&testTagLen2, bytes + off2, 2); off2 += 2;
off2 += testTagLen2;
uint32_t childCount2 = 0;
memcpy(&childCount2, bytes + off2, 4); off2 += 4;
off2 += childCount2 * 8;
```

## Step 6 — verify the disabled-state semantics

The Linux observer change made `TaoA11yFlag.ENABLED` an opt-in: it's
omitted by Compose when the node is `disabled()`. Make sure your UIA
`IsEnabled` getter checks `(flags & FLAG_ENABLED) != 0` rather than
defaulting to true.

```c
case UIA_IsEnabledPropertyId: {
    VariantInit(pRetVal);
    pRetVal->vt = VT_BOOL;
    pRetVal->boolVal = (el->flags & FLAG_ENABLED) ? VARIANT_TRUE : VARIANT_FALSE;
    return S_OK;
}
```

## Step 7 — update the wire-format comment

The comment block at the top of the file (around line 1-30) likely
documents v4. Replace with the v7 spec — copy from the Linux
`a11y_linux.rs` header or from `A11Y_PORT_CONTEXT.md`.

## Step 8 — build + test

From a Developer Command Prompt for VS:

```cmd
cd decorated-window-tao\src\main\native
.\windows\build.bat
```

The script clears `%LOCALAPPDATA%\nucleus\native\win32-x64` (and
`win32-aarch64`) for you. Run `sample-tao` from the repo root:

```cmd
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot
.\gradlew :sample-tao:run
```

### Manual smoke test

1. Start **Narrator** (`Ctrl+Win+Enter`). Or grab **Accessibility
   Insights for Windows** (free MS tool), or **Inspect.exe** from the
   Windows SDK.
2. Drive through **A11y** tab — same checklist as the macOS plan:
   disabled button, bare toggleable, email validation, custom actions,
   modal dialog, slider, live region, click counter.
3. Drive through **Complex** tab — todo list mutations,
   reorder, filter, ticker, expand/collapse, conditional form, stress
   churn.

### Scripted test (UIA)

The cleanest scripted check is a small C# console app using the
`System.Windows.Automation` namespace:

```csharp
using System.Windows.Automation;
var app = AutomationElement.RootElement.FindFirst(
    TreeScope.Children,
    new PropertyCondition(AutomationElement.NameProperty, "Sample Tao"));
var byId = app.FindFirst(
    TreeScope.Descendants,
    new PropertyCondition(AutomationElement.AutomationIdProperty, "bare-toggle"));
// Assert ControlType == CheckBox, IsChecked false, then click via TogglePattern.
```

Equivalent PowerShell (fewer dependencies):

```powershell
Add-Type -AssemblyName UIAutomationClient
$app = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
    'Children',
    (New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::NameProperty, 'Sample Tao')))
```

Replicate the assertions from `/tmp/a11y_full_regression.py` and
`/tmp/a11y_complex_test.py` (check those scripts on the Linux dev host
for the canonical assertion set).

The expected pass-rates are 34/34 on the basic A11y tab and 32/32 on
the Complex tab.

## Common pitfalls (Windows-specific)

1. **UIA threading**: `IRawElementProvider*` callbacks land on
   arbitrary threads. The current parser commits the new state under a
   critical section before raising events — do not reorder or you'll
   see torn reads from Narrator.
2. **`UiaRaiseAutomationEvent` payload retention**: when you call
   `UiaRaiseAutomationPropertyChangedEvent` for `Name` or `Value`,
   Narrator may still hold a reference to the previous BSTR. Don't
   `SysFreeString` the old name until the next snapshot commits.
3. **HWND vs `nativeViewHandle`**: the JNI surface uses the historical
   `nsView` parameter name even though on Windows it's the HWND. Don't
   rename — the Kotlin side passes the HWND through unchanged.
4. **`UIA_AutomationIdPropertyId` and `UIA_NamePropertyId` are
   independent** — UIA Verify will flag a missing AutomationId on
   automation-relevant elements. Expose `testTag` even when empty if
   you have a non-empty string available.
5. **AriaProperties string format**: UIA parses semi-colon-separated
   `key=value` pairs. `invalid=true;readonly=true` is a valid combined
   string. Build it dynamically when multiple ARIA hints apply.
6. **AccessibilityInsights "Tab Stops"** test will fail if any
   keyboard-focusable element exposes `IsKeyboardFocusable=TRUE` but
   no rectangle. The new `extraFlags` plumbing must not zero out the
   bounding rect.

## Out of scope

- High-contrast color reporting (UIA `UIA_FillColorPropertyId` etc.).
- IME composing-range exposure on UIA Text pattern (mentioned in
  `A11Y_ISSUES.md` § "IME composing range not exposed" — same gap
  exists on Linux's `a11y.m:850` stub for marked text).
- Implementing partial updates on Windows. The full-tree projection
  from Compose-Desktop's existing diffing infrastructure is sufficient
  for UIA's diff machinery.

## What "done" looks like

- `SNAPSHOT_VERSION = 7` and the parser reads the new fields without
  cursor drift.
- All 34 checks of the basic A11y harness pass on real Windows.
- All 32 checks of the Complex harness pass.
- Inspect.exe shows full role coverage with named labels, AutomationId
  populated from `Modifier.testTag`, IsEnabled correctly false on the
  disabled button, IsDataValidForForm false on the invalid email
  field.
- A commit landed on `wip/tao-experiment` of the form
  `feat(decorated-window-tao): bring Windows a11y parser to wire format v7`.
