# Compose 1.13 bump: E2E verification plan

Tracking issue: [#671](https://github.com/NucleusFramework/Nucleus/issues/671).
Branch: `claude/compose-1.13` (base `nucleus-3.0`).

This plan is for agents verifying the bump on **real desktops**: Linux (X11 and
Wayland) and Windows, plus macOS when a Mac is available. Unit tests and
compilation were already checked on Linux (see "Already verified"). What is
left can only be seen with a real window, a real pointer, a real package.

Nucleus supports Compose **1.13 only** from this branch on. There is no 1.12
compatibility to keep.

## Ground rules

1. **One report per OS/session**, in the format at the end. Every check gets
   `PASS`, `FAIL`, `BLOCKED` (could not run, say why) or `N/A`. Never mark a
   check `PASS` without the evidence the check asks for.
2. **Evidence is mandatory**: command line, exit code, the relevant log lines,
   and a screenshot or pixel probe where the check is visual. Screenshots go in
   `build/e2e-1.13/<os>/` (not committed); name them `<check-id>.png`.
3. **A/B against 1.12 when a result looks wrong.** Check out `origin/nucleus-3.0`
   (still on Compose 1.12.1) in a separate worktree and run the same check.
   A failure that also happens on 1.12 is not a 1.13 regression: report it as
   `FAIL (pre-existing)` with the 1.12 evidence, and do not fix it on this branch.
4. **Do not fix in passing.** Report first. A fix goes in its own commit with
   the check id in the message (`fix(tao): … (1.13 E2E W-07)`).
5. Run on a **clean build** of this branch: `./gradlew clean` once, then the
   natives for your OS (`buildNativeLinux` / `buildNativeWindows`, or the CI
   `natives-*` artifacts).
6. JDK: Gradle on JDK 25 (`JAVA_HOME` set to a full JDK 25). Kotlin 2.4.20 is
   already in the catalog (required since Compose 1.13.0-alpha02).

## What changed (the reason for each check)

| Id | Change in Compose 1.13 / this branch | Where in Nucleus | Risk if wrong |
|---|---|---|---|
| B0 | Graphics/text backends must be registered (`registerSkikoComposeImplementation`) | `ensureSkikoComposeBackend()`: `TaoSceneBundle` factories, `TaoApplicationCompose`, `PainterToRgba` | Every window throws before its first frame; icons fail at startup |
| B1 | `LocalGraphicsResourceCache` defaults through `LocalOwner` | `ProvideApplicationRootLocals` (`TaoApplicationCompose.kt`) | `rememberVectorPainter` / XML `painterResource` as window or tray icon crashes |
| B2 | Internal names are group-qualified (`$org_jetbrains_compose_ui_ui`) | `TaoLayerTreeAccess.java`, plugin `LayerDamageTransform` | Partial redraw (#755) silently off; the transform now logs a warning |
| B3 | Skiko natives ship in one `skiko-awt-runtime-all` jar (6 OS/arch) | plugin `AbstractJPackageTask` (`isSkikoForCurrentOS`, `unpackSkikoForCurrentOS`) | +45 MB per package, foreign binaries to sign, Skiko not pre-extracted |
| B4 | v2 window API: `CenteredInScreenBounds`, `AlignedToScreenAvailableBounds` | `tao/v2/WindowProviders.kt` (old names removed) | Window placement off |
| B5 | Material3 1.5: `FilledTonalToggleButton`, `onValueChange` required on state sliders | Lab only | Lab sliders stuck |
| C1 | `PlatformContext` owns `clipboard` / `uriHandler` | `TaoPlatformContextBase` overrides | Copy/paste or link opening wrong in popups/dialogs; XAWT started by `Desktop.browse` |
| C2 | `isTrackpadPanHoverFixEnabled = true` (synthetic Enter/Exit before Pan) | `TaoSceneScrollRouter` | Hover flicker or stuck hover during trackpad scroll |
| C3 | `isTriggerMoveEventsWhenLocationHasNotChangedEnabled = true`, drag detector fix (beta02) | `DockSplitter`, `TransferDrag`, `CrossWindowDrag` | A click on a draggable area starts a drag |
| C4 | `Popup` content is `alpha(0)` with zero bounds until the parent is placed | native popup layers (`TaoPopupSceneLayer*`) | Popup first frame empty, mis-sized or flashing |
| C5 | Compose `Paint` defaults match Skia (round joins now render) | KDE title-bar icons, `SpellcheckUnderline` | Visual change only, expected |
| C6 | New `Modifier.border` implementation | Jewel, M3 outlined components | Border artifacts, damage tracking misses |
| C7 | Text layout leak fixed in beta02 (was a beta01 regression) | text-heavy scenes | Stutter growing over time |
| J1 | Jewel 0.41 → 0.42.0-263.7307.200 (built on Compose 1.12.1), IntelliJ icons 263.7307.200. `copyWithSize` removed (Lab now has a local `withSize`) | `decorated-window-jewel`, `jewel-demo`, Lab design system | Binary mismatch at runtime (`NoSuchMethodError`, `AbstractMethodError`); Lab type scale off |
| N1 | Navigation3 1.1.1 → 1.2.0, hot reload 1.2.0 → 1.3.0-beta02 | `compose-demo`, `TaoHotReloadBridgeImpl` | Demo navigation crash; hot reload bridge mismatch |
| G1 | Skiko 0.156.1, dead GraalVM metadata removed | plugin `nucleus/graalvm/**` | Native image missing a Skia class |

## Environments

### Linux

Run every Linux check on **two sessions** unless the check says otherwise:

- **L-X11**: Xvfb + openbox, exactly as CI (`.github/workflows/pre-merge.yaml`,
  job `tao-headful`). Pixel oracles must run here: XWayland returns stale
  frames for GL windows.
  ```bash
  export DISPLAY=:99
  Xvfb :99 -screen 0 1600x1200x24 & openbox &
  ```
- **L-WL**: a real Wayland session (GNOME/Mutter preferred; KWin second).
  Before any Wayland run, make sure nothing AWT is initialised before Tao
  opens GTK, or GDK silently falls back to X11: check
  `GDK_BACKEND` is not set to `x11` in the process and that the log shows a
  Wayland display. Robot cannot inject input on Wayland; use the portal
  pointer driver the headful suite already has, or `uinput` scripts.

Record for each session: distro, kernel, desktop and version, GPU + driver
(NVIDIA vs Mesa matters, see #444), display scale.

### Windows

- **W-REAL**: a real Windows 11 desktop session (not a service, not RDP
  minimised). 125 % or 150 % scaling is preferred over 100 %, it catches more.
- **W-CI**: the `tao-headful` job on `windows-latest` (Mesa software GL). Run it
  by pushing the branch; read logs with ranged requests if the download is
  blocked.

Record: Windows build, GPU, scaling, ANGLE backend in the log.

### macOS (optional, when available)

Same checks as Linux where they apply, plus trackpad (C2) on a real trackpad
and `smokeStandalonePanelMac`.

## Checks

Ids are `<area>-<nn>`. "All OS" means L-X11, L-WL, W-REAL.

### Stage 0: build and automated suites (all OS)

| Id | Do | Pass when |
|---|---|---|
| S-01 | `./gradlew :decorated-window-tao:test :nucleus-application:test :decorated-window-jewel:test` | Exit 0; record test counts (Linux baseline: 642 / 87 / 4) |
| S-02 | `./gradlew -p plugin-build :plugin:test` | Exit 0; `LayerDamageTransformTest` 4/4 (it patches the real 1.13 `ui-desktop` jar) |
| S-03 | `./gradlew :decorated-window-tao:apiCheck` and `detekt` | Exit 0 |
| S-04 | `./gradlew :decorated-window-tao:taoHeadfulTest` (full suite) | Exit code 0 (= zero failed cases). Compare the failing set against the same run on `nucleus-3.0` (1.12) when non-zero; only new failures count |
| S-05 | `./gradlew preMerge` (Linux only, long) | Exit 0 |
| S-06 | In a sample app, pin `org.jetbrains.compose.ui:ui-desktop` with `strictly("1.12.1")` and run `createDistributable` | Build fails with `Nucleus requires Compose Multiplatform 1.13 or newer…`. Apply `org.jetbrains.compose` 1.12.x instead: the build only warns about the Hot Reload agent. When a 1.14 prerelease exists, resolve it: the build passes with one "has not been tested" warning per runtime classpath. Revert afterwards |

On Wayland, S-04 runs the cases that support Wayland; note which were skipped.

### Stage 1: startup and backend registration (B0, B1)

| Id | Do | Pass when |
|---|---|---|
| R-01 | `./gradlew :examples:nucleus-demo:run` | Window paints within 5 s; log has no `Registered implementation is null`, `No Compose UI graphics implementation`, `No Compose UI text implementation` |
| R-02 | Same with `tao-demo`, `jewel-demo`, `compose-demo`, `cmp-demo` (`:run` each) | Same as R-01 for each; `compose-demo` navigates between at least two destinations (N1) |
| R-03 | Minimal app (`examples/tao-demo` patched locally, not committed): `DecoratedWindow(icon = rememberVectorPainter(Icons.Default.Home))` **and** a tray icon from an XML vector `painterResource`, at application scope | Starts; window and tray show the icon; no `LocalOwner not present`. Screenshot of the taskbar/tray |
| R-04 | Same app, set the window icon **before** the first window opens (icon computed in `nucleusApplication { }` top level) | Icon appears (exercises `PainterToRgba` before any scene) |
| R-05 | Notifications with a vector icon (Lab `notifications` probe) | Notification shows with its icon |

### Stage 2: rendering and partial redraw (B2, C5, C6, C7)

| Id | Do | Pass when |
|---|---|---|
| D-01 | Build an app with `nucleusOptimization { partialRedraw = true }` (Lab app) and run with Gradle `--info` | No `Nucleus partialRedraw: … does not have the Compose layer methods` warning; the patched jars `*-nucleus-damage.jar` are on the runtime classpath |
| D-02 | In that app, a small animation (spinner) next to static content; enable the damage overlay/log the Lab provides | Damage rects cover the spinner only, not the full window. Compare the frame cost with partialRedraw off |
| D-03 | Lab rendering conformance probe (`:examples:lab:app:run`, Rendering → Conformance) | No visual diff against the 1.12 build except C5 (round stroke joins). Screenshot pairs 1.12 / 1.13 |
| D-04 | Linux KDE title-bar style (or the Lab chrome gallery with KDE icons) | Round joins on the icon strokes now visible; nothing else changed |
| D-05 | Jewel + M3 outlined components (Lab theming gallery: outlined buttons, text fields, cards, rounded and cut corners, gradient border if any) | Borders crisp, corners correct, no 1 px gaps, no stale border after hover/resize |
| D-06 | Text soak: Lab text-heavy probe or a `LazyColumn` of 5 000 `Text` rows, scroll for 5 min continuously | Frame time at minute 5 within 20 % of minute 1 (C7). Record both |
| D-07 | LCD text: `jewel-demo` on Windows and Linux | Subpixel AA still on where it was on 1.12 (`LcdTextDefaultTransform` still applies) |

### Stage 3: input (C2, C3)

| Id | Do | Pass when |
|---|---|---|
| I-01 | Dock: plain click on a dock tab, on a satellite title area, on the splitter (`DockLayout` fixture / Lab workspace) | Each click selects/focuses, **never** starts a drag or moves the splitter. 20 clicks each |
| I-02 | Same targets, press-move-release by 30 px | Drag starts, follows the pointer, drops where expected |
| I-03 | `TransferDrag` / cross-window drag of a tab to another window and back | Works both ways; a click on the tab after the round trip is still a click |
| I-04 | Headful filter: `-Dnucleus.tao.headful.filter="DockLayout|TabWorkspaceMouse|TabWorkspacePointer|Satellite"` | Zero failures |
| I-05 | Hover during scroll: list with hover highlight, scroll with wheel (all OS) and touchpad (L-WL real touchpad or uinput; Windows precision touchpad) | Highlight follows the item under the pointer; no stuck highlight after the scroll ends; no flicker on every scroll event |
| I-06 | Same inside a native popup (dropdown with a long list) | Same as I-05 |
| I-07 | Headful filter `Scroll|Trackpad|DirectManipulation` | Zero new failures vs 1.12 |
| I-08 | Text input: IME (Linux IBus/Fcitx, Windows Microsoft Pinyin or Japanese), text selection by mouse, double-click word selection | Same behaviour as 1.12 |
| I-09 | Keyboard focus traversal (Tab / Shift+Tab) across a form and into a popup | Same as 1.12 |

### Stage 4: popups, dialogs, windows (C4, B4)

| Id | Do | Pass when |
|---|---|---|
| P-01 | Open each popup kind 10 times: `DropdownMenu`, M3 `ExposedDropdownMenu`, tooltip, context menu (right-click), Jewel popup | First frame shows content at the right place and size: record the first two frames (screen recording or rapid screenshots). No empty/transparent first frame, no frame at 0×0 or at the window origin |
| P-02 | Headful filter `NativePopup|PopupScale|UnpaintedFlash|TearOffFlash` | Zero new failures |
| P-03 | Popup on a second monitor with a different scale (if available) | Correct size and position |
| P-04 | Modal dialog + non-resizable dialog (`ModalDialog`, `NonResizableDialogSize` filters) | Zero new failures; on L-WL note the known CSD shrink (pre-existing) |
| P-05 | v2 API: `WindowPositionProvider.CenteredInScreenBounds` and `AlignedToScreenAvailableBounds(Alignment.BottomEnd)` (`WindowApiV2` headful filter, plus Lab Window → State probe) | Centered on the full screen bounds / bottom-end inside the work area (taskbar not covered). Screenshot with the taskbar visible |
| P-06 | Dialog default position (`DialogState` with no provider) | Centered in the work area, as on 1.12 |

### Stage 5: clipboard, links (C1)

| Id | Do | Pass when |
|---|---|---|
| C-01 | Copy from a `TextField` in the main window, paste in another app; and back | Works both ways |
| C-02 | Same from a `TextField` **inside a dialog and inside a popup** | Works both ways (new: the context now carries the Tao clipboard) |
| C-03 | L-WL only: copy from Nucleus, paste into a native Wayland app (gedit/kate) | Works (GTK clipboard path, #582) |
| C-04 | Click a link (`LinkAnnotation` / `LocalUriHandler.current.openUri`) in the main window, a dialog and a popup | Browser opens; on Linux the log shows no XAWT/`Desktop.browse` init, the app does not freeze |
| C-05 | Headful filter `Clipboard` | Zero failures |

### Stage 6: packaging (B3)

Run on Linux x64 and Windows x64 (arm64 when hardware is available).

| Id | Do | Pass when |
|---|---|---|
| K-01 | `./gradlew :examples:lab:app:createDistributable` | `build/compose/tmp/skiko/` (or the app `lib/`) holds only the current OS/arch Skiko lib (`libskiko-linux-x64.so` / `skiko-windows-x64.dll` + `icudtl.dat`) |
| K-02 | `unzip -l` the shipped `skiko-awt-runtime-all-*.jar` inside the app folder | No `.so`/`.dylib`/`.dll` entries left in it |
| K-03 | Total app folder size vs the 1.12 build | Within ±5 MB of 1.12 (not +45 MB) |
| K-04 | Run the distributable (`runDistributable`) | Starts, paints; Skiko loads from the pre-extracted lib (`-Dskiko.library.path` in the launcher `.cfg`) |
| K-05 | `packageReleaseDistributionForCurrentOS` (ProGuard) and run the installed package | Installs, starts, paints. Linux: the installed `.deb`/`.rpm` under `/opt` is the one running (check the path in `ps`), not a stale one |
| K-06 | Uber jar (`packageUberJarForCurrentOS`), `unzip -l` | Only the current OS Skiko lib inside |
| K-07 | Windows: check the signed/packaged MSI/EXE contains no `.so`/`.dylib` | None |

### Stage 7: GraalVM native image (G1, B0)

| Id | Do | Pass when |
|---|---|---|
| G-01 | `./gradlew :examples:tao-native-test:runGraalvmNative` | Builds and runs; no `ClassNotFound`/`NoSuchMethod` for `org.jetbrains.skia.*` |
| G-02 | `./gradlew :examples:cmp-demo:packageGraalvmNativeDistributionForCurrentOS` (a demo with `graalvm { isEnabled = true }`), install/run the app | Starts, paints, text renders (backend registration works under native image), window icon shows |
| G-03 | `CI` job `test-graalvm.yaml` on the branch | Green on Linux and Windows |

### Stage 8: Jewel 0.42 (J1)

| Id | Do | Pass when |
|---|---|---|
| J-01 | `./gradlew :examples:jewel-demo:run`, open every page of the demo | No exception in the log; icons (IntelliJ icons 263) render |
| J-02 | Jewel decorated window: title bar buttons, theme switch light/dark, Jewel popups and menus | Same as on `nucleus-3.0` |
| J-05 | Lab with the Jewel design system: compare title / small / mono text sizes and line heights with the 1.12 build (screenshot pair) | Identical metrics (the local `withSize` replaces Jewel's removed `copyWithSize`) |
| J-03 | Jewel markdown (Lab or demo page with markdown, tables, alerts, images) | Renders |

### Stage 9: hot reload (N1)

| Id | Do | Pass when |
|---|---|---|
| H-01 | `./gradlew :examples:tao-demo:hotRun` (hot reload is auto-applied by the Compose plugin; confirm the task name with `./gradlew :examples:tao-demo:tasks --all \| grep -i hot`), change a `Text` string and save | The running window updates without restart; no `NoSuchMethodError` from `TaoHotReloadBridgeImpl` |

## Already verified (Linux x64, before handing over)

Done by the agent that made the bump, on Ubuntu (GNOME session, but builds and
the headful run under Xvfb + openbox like CI). Do not redo these unless a later
check points at them.

- Every module, example and the Lab compile on 1.13.0-beta02 (`compileKotlin compileTestKotlin`).
- `:decorated-window-tao:test` 642 tests, `:nucleus-application:test` 87,
  `:decorated-window-jewel:test` 4: all green.
- `ApplicationScopeVectorPainterTest` (B1) fails with `CompositionLocal LocalOwner not present`
  when the `providesDefault null` line is removed, passes with it. Without
  `ensureSkikoComposeBackend()` it fails with `No Compose UI graphics implementation is registered`
  (B0 at application scope).
- Plugin `:plugin:test` all green, including `LayerDamageTransformTest` (patches the real
  1.13 `ui-desktop` / `ui-graphics-desktop`) and `LcdTextDefaultTransformTest` (the original
  default now comes from the registered text backend; the test registers a stub).
- `apiCheck`, `detekt`, `ktlintCheck` on the touched modules.
- Jewel 0.42.0-263.7307.200: its 10 jars reference none of the 1.13-changed internals
  (`DragAndDropTransferAction`, `PlatformContext`, `ComposeUiFlags`, Skia `FontMetrics`,
  `skiko.redrawer`, old `$ui` mangled names).
- Headful, full `taoHeadfulTest` under Xvfb + openbox (X11 renderer): **292 run, 149 skipped,
  1 failed**. The failure, `dialog appearance — native popup layer matches the in-scene layer`
  (native first visible 154 ms vs 101 ms, tolerance 50), passes 3/3 when run alone. A/B on the
  1.12 baseline (same base commit): the native layer trails by 13–15 ms on 1.12 and by
  18–28 ms on 1.13, both well inside tolerance. Treated as load jitter. Agents: keep an eye on
  it in P-01 / P-02 (C4 popup first frame); a consistent gap above ~30 ms would be a real
  regression.

Not verified: anything on Windows or macOS, Wayland, packaging (Stage 6), GraalVM
(Stage 7), hot reload (Stage 9), every visual check.

## Report format

```markdown
## <OS> / <session> — <date> — <agent>
Commit: <sha>   Machine: <distro/build, GPU+driver, scale>

| Id | Result | Evidence |
|---|---|---|
| S-01 | PASS | 642 / 87 tests, exit 0 |
| P-01 | FAIL | build/e2e-1.13/linux-wl/P-01.png — first frame empty for DropdownMenu, 1.12 OK (A/B) |
...

### Failures
For each FAIL: steps, expected, actual, logs, 1.12 A/B result, suspected cause.
```

Post the report as a comment on #671 (only when the user asked for it), or
append it to this file under "Reports".

## Reports
