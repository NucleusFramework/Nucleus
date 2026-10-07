# Nucleus Lab

The single test bench for every Nucleus runtime module. Each screen is a **probe**. A probe
answers a single question: *does this work here, on this OS, in this package format?* It
compares what was asked with what the OS actually did, and keeps a record you can paste into an
issue.

```bash
./gradlew :examples:lab:app:run                                   # the Lab
./gradlew :examples:lab:app:run -Dlab.probe=shell.badge?count=5   # open a probe, with parameters
LAB_PROBE=input.a11y ./gradlew :examples:lab:app:run              # same, from the environment
```

A packaged Lab registers the `nucleus-lab://` scheme, so
`nucleus-lab://probe/<id>?key=value` opens a probe from a browser, a terminal (`open` /
`xdg-open` / `start`) or another app. The *Copy link* button on any probe gives you its link.

## What every probe looks like

| Area | Owner | Content |
|---|---|---|
| Header | shell | Title, the question the probe answers, modules under test, *Copy link* / *Copy report* / *Reset* |
| Capabilities | probe | Whether each capability is available here, and **why not** when it isn't |
| Controls | probe | Typed inputs and actions |
| Observed | probe | What the OS / the API reports back, never just what was sent |
| Checks | shell | Manual pass / fail / skip with notes, stored per environment fingerprint (OS/arch/display server/package format) |
| Timeline | shell | Every intent, event and effect, timestamped, tagged with its thread; anything delivered off the UI thread is flagged |

*Copy report* produces Markdown containing the environment, the check sheet and the timeline
excerpt.

## The shell

The main window is a `DockLayout` (Nucleus' satellite workspace, dogfooded): its content is the
selected probe, and the **probe list**, the **checks** and the **timeline** are `Satellite`s of
one `SatelliteWorkspace`. By default they are docked: probes on the left (full height), checks
on the right, timeline at the bottom. Each one can float as its own Lab window (*Float* in its
header, or drag the header out) above the main window, and dock back (*Dock*, or drag it to an
edge of the main window until a zone lights up). The splitters resize the docks. The title-bar
buttons show and hide each pane, and so do the pane's *Hide* button and a floating pane's close
button. On native Wayland, the strip beside a floating pane's window controls (✥) moves the
window, and the header chip drags the pane into a dock.

The layout (placement, size, open state of each pane, dock extents) is saved to
`shell-layout.json` in the Lab's data directory half a second after it changes and on exit, and
restored at startup. A missing or unreadable file means the default layout; delete it to reset.
`ShellState.openPanes` (`ShellIntent.TogglePane` / `SetPaneOpen`) owns which panes are open.
`ShellWorkspace` holds the workspace and keeps the two in step. The Cmd/Ctrl+K shortcut is
handled by the main window only.

## Architecture

The Lab uses **MVVM + MVI** with **Metro** for compile-time DI (no reflection).

```
examples/lab/
  core/          Probe contract, MviViewModel, Timeline, Environment, CheckStore, LabCommands,
                 SessionHost, Fixture + FixtureLauncher
  designsystem/  LabTheme (Jewel underneath), ProbeLayout, Readout, Actions, ChoiceRow, …
  probes/<domain>/  one module per domain, contributing probes to the graph
  app/           LabGraph (@DependencyGraph), Main, shell, built-in probes, packaging
```

The flow inside a probe:

```
UI ──Intent──▶ ViewModel.handle() ──▶ Gateway (Nucleus API) ──callback──▶ Event ──▶ Reducer ──▶ State ──▶ UI
                                                                     └──▶ Effect (one-shot)
```

- **Contract**: `State`, `Intent` and `Event` types, plus an `object …Reducer : Reducer<S, E>`. The
  reducer is pure and unit-tested without any OS.
- **Gateway**: an interface over the Nucleus module, with a
  `@ContributesBinding(AppScope::class) @Inject` implementation. OS callbacks go through
  `stampedCallbackFlow { }` / `.stamped()` so the timeline records the thread they *arrived* on.
- **ViewModel**:
  `@ViewModelKey @ContributesIntoMap(AppScope::class, binding = binding<ViewModel>()) @Inject`,
  extending `MviViewModel`. The `binding` argument is mandatory: Metro binds a contribution to its
  direct supertype, which is `MviViewModel<…>` here, not `ViewModel`.
- **Probe**: `@ContributesIntoSet(AppScope::class) @Inject`, holding a `ProbeDescriptor` and
  `Content()`. Content gets its ViewModel from `metroViewModel<…>()` and draws it with `ProbeLayout`.

The ViewModels of a probe live in a `ViewModelStore` of their own. It is kept while you browse
other probes and cleared by *Reset*.

## Windows, sessions and fixtures

- A probe that opens windows (tab workspace, overlay, widget, chrome sandbox) goes through
  `sessions(host).open(…)` in its ViewModel. Sessions are composed at the application root, so
  switching probes does not close the window under test.
- Code that must own its process goes in a `Fixture`. That covers code that exits on failure, runs
  Swing on the raw Tao loop, or needs JVM flags the Lab must not run with. The *Fixtures* probe
  relaunches this same JVM and classpath with `-Dlab.fixture=<id>` plus the chosen variant's
  flags. It then follows the child's output and exit code.

## Adding a probe

1. Pick the domain module under `probes/`. Add the Nucleus module dependency to its
   `build.gradle.kts`.
2. Write the four files, following `probes/system/appearance` (the reference).
3. Write checks that describe what must be **seen**, not how to trigger it.
4. Run `./gradlew :examples:lab:app:compileKotlin`. The Metro graph is only assembled there.

The **Coverage** probe lists every runtime module that has no probe yet.

## One style, one way to do things

The Lab is written with **Jewel** (IntelliJ's Int UI, standalone theme), light or dark,
following the OS unless the title bar's theme button (or `-Dlab.theme=light|dark|system`) says
otherwise. Jewel stays inside `designsystem`: probes import only `designsystem` (and `core`),
never Material nor Jewel. These rules hold everywhere except inside a **specimen** (the thing
under test: a Compose sample, a NativeView, the `a11y-surface` goldens, a window whose chrome is
the subject); Material or Jewel imports are allowed there and nowhere else.

**UI** comes only from `designsystem`:
- **Tokens:** `LabTheme.colors` (`background`, `panel`, `raised`, `border`, `text`,
  `textMuted`, `accent`, `selection`, `ok` / `warning` / `error`, `code`, `specimen`,
  `target`, `overlay`, …) and `LabTheme.typography` (`title`, `heading`, `label`, `body`,
  `small`, `mono`). Never `MaterialTheme` nor `JewelTheme`.
- **Primitives:** `Text`, `Icon` with `LabIcons` (IntelliJ's own icons; `LabIcon.of(vector)`
  for a probe's own), `Divider`, `Tooltip`, `Link`, `ScrollableColumn`, `LabLazyColumn`.
- **Layout and spacing:** `ProbeLayout` (with `wide` for specimens), `Section`, `SubSection`
  and `LabDimens` / `LabShapes` / `LabSurfaces`. Never a raw dp for padding or corners, nor a
  hex colour for chrome.
- **Text:** `Readout` (`indent` for nested lines), `Hint` for sentences (`SubHeading` only
  names a group), `EmptyState`, `CodeBlock`, `EventLog` (`CallRecord` / `Delivery` →
  `toLogEntry()`), `Meter`, `Counters`, `MonoTable`, `LiveChart` and `Sparkline`.
- **Controls:** `PrimaryAction` / `SecondaryAction` / `TertiaryAction`, `SwitchRow`,
  `CheckboxRow`, `RadioRow`, `ChoiceRow` / `ChoiceDropdown`, `Dropdown`, `ToggleChip` /
  `ToggleChipsRow`, `TextField` / `TextArea` / `TextFieldRow` (`minLines`), `NumberFieldRow`,
  `SliderRow` and `SelectableRow`.
- **Areas and overlays:** `TargetArea` for places the tester acts on, `SpecimenFrame` around
  content under test, `OverlayPill` over native content, `ColorSwatch`.
- **Windows:** `LabSessionWindow` for every probe window, or `LabDecoratedWindow` /
  `LabWindowFrame` / `LabTitleBar` / `LabWindowAppearance` / `LabTitle` / `TitleBarButton` /
  `LabPane` / `LabPaneHeader` when the window type is imposed (`TabWindows`, `DecoratedWindow` with
  `DockLayout`, satellites). `LabTheme` already provides the Jewel window and title bar
  styling to every window below it.

**Logic** comes from `core`:
- **Sessions:** `sessions(host)` → `ProbeSessions` (`open` / `close` / `isOpen` / `onEnded` /
  `observe`). Never `SessionHost` directly, and never close in `onCleared`: reset closes
  through the shell.
- **ViewModel helpers:** `io { }`, `poll(…)`, `onParams(commands) { p -> p.int("count") }`.
- **OS callbacks:** `Stamped` + `map` + `dispatch(stamped)`; the base class already judges
  the thread.
- **Records and history:** `Delivery`, `CallRecord`, `CallOutcome`, then `append` /
  `pushFront` / `replaceWhere` with `DEFAULT_HISTORY`.
- **Formatting:** `format.*` (`formatTime`, `formatBytes`, `formatDurationMillis`,
  `formatClock`, `percent`, `fmt`, `argbHex`, `hex`, `Throwable.summary`), all in
  `Locale.ROOT`.
- **Timing:** `time.monotonicMillis` and `timedMillis`.
- **Processes:** `process.JvmCommand` (`dev`, `self`, `fixture`, `fixtureArguments`) for any
  relaunch of the Lab, `runStreaming` for a child process, `runTool` for a short-lived OS tool.
- **Files:** `LabPaths.scratch` / `scratchFile` / `extractResource`, and `LabLog` for a log
  another process writes.
- **Native calls:** `Availability.catching { }`, which also catches linkage errors, so a
  broken native library never takes a probe down.
