# tray internals

`tray` - system tray icon + native menu (`Tray(…)` composables, the `TrayMenuBuilder` / composable `ComposableTrayMenuScope` DSL) and `TrayApp` (an icon whose click toggles a popup anchored to it). Imported from NucleusFramework/ComposeNativeTray; one module, package `dev.nucleusframework.tray`, natives `nucleus_tray`.

## Shared design

- `NativeTray` drains one **conflated** queue on one worker: changes reach the backend in order and a burst collapses to its newest state. `dispose()` is final (a lock + `disposed` flag): no queued or in-flight change can recreate the icon.
- Composable icons are rendered to files (backends take paths) through `IconFileCache`: content-addressed (identical renders share a file) and capped at 64 files (`-Dnucleus.tray.iconCacheSize`). Each render used to be a new temp file kept until JVM exit.
- A change carrying the `iconKey` of the icon already shown skips rendering, so a menu-only change is immediate.
- Callbacks are stable wrappers over the **latest** lambdas (`rememberUpdatedState`): `primaryAction` / `onMenuOpened` captured values never go stale, and a fresh lambda per recomposition no longer re-applies the tray. A composable-menu click resolves its handler in the latest recording by item path, since the native menu is only rebuilt when the structure changes.
- `jni_utf8.h` (shared by every bridge) encodes real UTF-8 and **drops** U+0000 (it used to truncate, losing e.g. the shortcut hint after it). `src/main/native/shared/**` is an input of every `buildNative*` task.

## Windows

- One tray = one daemon thread (`NucleusTray-<id>`) that owns the hidden window (`NucleusTrayWindow`, top-level so it still receives `TaskbarCreated`), the HMENU and the HICON. The context hangs off `GWLP_USERDATA`: no process-wide list, no lock, so an open popup menu (`TrackPopupMenu` runs a modal loop) on one tray never stalls another. Callbacks are JNI global refs stored on their tray / menu item, never in a shared list (disposing a tray used to drop every other tray's menu callbacks).
- The loop sleeps in `MsgWaitForMultipleObjectsEx`; other threads publish the wanted state and post `WM_TRAY_WAKE` (no 50 ms polling).
- `tray_wake(cancel)` (dispose) sets `closing` from any thread: no menu opens any more, the pump stops, and an open menu gets `EndMenu`. Without it a backlog of queued clicks reopened a modal menu after dispose and pinned the thread forever.
- Menu labels are shown literally: `&` is doubled (it was eaten as a mnemonic), dividers are a flag (a `-` label used to become a separator), empty labels are kept. Command ids index a per-rebuild table; every bitmap (submenus included) is freed on rebuild (8 GDI objects leaked per rebuild before).
- Checkable items are patched by an identity token, not by label (two items sharing a label used to toggle together).
- A bad icon path keeps the current icon; `TaskbarCreated` (explorer restart, DPI change) reloads the icon at the current `SM_CXSMICON` and re-adds it. A shutdown hook removes icons when the JVM exits without disposing.

## E2E (Windows)

`./gradlew :tray:test` (headful: real notification area, no mouse). Black-box through Win32 from the test JVM (JNA, tests only): the hidden window gets the very messages the shell sends (`WM_APP+1` with `WM_LBUTTONUP` / `WM_RBUTTONUP`, `WM_COMMAND`, `TaskbarCreated`), the popup is read back from the `#32768` window with `MN_GETHMENU`, icons are checked with `Shell_NotifyIconGetRect`, leaks with `GetGuiResources`.

- `WindowsTrayTortureTest`: seeded menu fuzzer (hostile labels, nesting, 0-80 items; compares the popup item by item), multi-tray isolation, frozen-tray, throwing callbacks, explorer restart, GDI leak, lifecycle churn across threads, dispose racing init, rapid-update convergence, garbage icons.
- `WindowsTrayLifecycleTortureTest`: JVM exit with a live tray (child JVM), dispose with the menu open, click storm during rebuilds and disposals, 30 trays, icon-file growth, evicted icon file, create/dispose x100 leak check.
- `WindowsComposeTrayTortureTest`: the composables in a real composition (`ImageComposeScene`, two render passes per frame: a composable menu re-records into its scope's snapshot list and `Tray` recomposes on the next pass): labels follow state, stale-closure clicks and `onMenuOpened`, add/remove from composition.
- `WindowsTrayAppE2ETest`: each scenario runs `TrayAppProbeMain` in its own JVM (real `nucleusApplication`, Tao loop, standalone popup) and reports `CHECK` lines: click toggles, `visibleOnStart`, seeded click storm and concurrent `show`/`hide`/`toggle` (state must match the popup on screen), resize while visible, menu next to the popup, removal from composition. Geometry oracle: the popup's physical rect has the state's dp size at the window DPI, sits inside the icon monitor's work area, against the taskbar edge (default 10 px offset), centered on the icon unless clamped. `TrayApp` hands the tray a stable primary action, so a show/hide no longer rebuilds the native tray.

- `TrayAppFuzzMain` (same harness): `fuzz` / `multi` run N seeded rounds of random actions on one or two `TrayApp`s (tray clicks, `show`/`hide`/`toggle`, 3-thread bursts, sizes from 1x1 to 5000 dp, offsets ±600, icon / tooltip / menu changes, `dismissMode`, a ticking content, outside clicks, removal and reinsertion), then check: state matches the screen, a popup that fits stays in the work area (a larger one keeps its top-left corner on screen), anchored to its own icon without offsets, GDI/USER counts and tray threads bounded. In `multi` each app owns distinct sizes so a popup on screen always says whose it is. `dismiss`: AUTO hides on an outside click, a click within 200 ms of the show is ignored, the icon re-click while open closes and stays closed, MANUAL ignores outside clicks. Outside clicks are delivered by posting `WM_APP + 0x51` (what Tao's `WH_MOUSE_LL` hook posts) to the popup, so the real mouse never moves.
- Fixed by the fuzz: a show blocked or hidden before it consumed its pre-computed position left it behind, and a later show after a resize reused the old size's position (a 900 dp popup opened at y = 0, half off the work area); the pending position is now keyed by size and offsets. The corner fallback (no click recorded yet, e.g. right after reinsertion) did not clamp, so a popup wider than the screen lost its left half; every placement now goes through one clamp. A resize or offset change landing while a show was still resolving its position (~400 ms on Windows) was dropped: the re-anchor effect is now also keyed on the popup coming up. With no click recorded, the position is read from the shell on demand instead of guessing a corner, and the Windows corner comes from the taskbar edge (`ABM_GETTASKBARPOS`, RTL-aware): `TrayNotifyWnd` no longer locates the notification area on Windows 11, which made the guess bottom-left.

- `realmouse` (same harness): real `SendInput` clicks through Tao's `WH_MOUSE_LL` hook, aimed at a topmost window the child JVM owns (never another app), cursor restored: a click elsewhere hides (AUTO), a click inside keeps, MANUAL ignores.
- `WindowsPopupPlacementTest`: `windowsPopupOrigin` (the pure Windows placement) for every scale from 100 to 350 % and every taskbar edge, LTR and RTL, with the anchor computed like `tray_get_notification_icons_position`: in the work area, never over the icon, against the taskbar, centered unless clamped; plus 20 000 seeded screens / sizes / offsets that must stay in the work area (or keep the top-left of an oversized popup).

Not covered on a real screen: HiDPI and top/side taskbars (the dev machine is 100 %, bottom taskbar); the placement math is covered by the pure test, the dp-to-pixel conversion of the popup is Tao's.

Seeds are printed (`[torture] … seed=`); rerun with `-PtraySeed=<seed>`, scale with `-PtrayIterations=<n>`. Dismiss popups with `WM_CANCELMODE` only: `#32768` windows are reused, so a stray Esc closes the next popup. `Shell_NotifyIconGetRect` fails for a dead window, so it cannot tell a removed icon from a visual ghost.

## macOS / Linux

Not yet covered by the torture suite (ported as-is from ComposeNativeTray).
