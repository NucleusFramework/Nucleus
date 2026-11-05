# Windows widgets internals

`widget-runtime` - Glance-like widgets (`NucleusWidget`, `provideContent`, `Column` / `Row` / `Text` / `Button` / `Image` / `Spacer`, inputs bound to state keys, `WidgetModifier`, typed widget state, lambda / `ActionCallback` / `actionOpenUrl` actions, customization, web content). Kotlin Multiplatform, `jvm` only for now: nothing in `commonMain` is Windows-specific, so a macOS / iOS host (WidgetKit) can render the same tree.
`widget-windows` - the 1:1 binding of `Microsoft.Windows.Widgets.Providers` (Windows App SDK, raw WRL, `widgets_abi.h` transcribed from the winmd) and `WindowsWidgetHost`, the host of the Glance-like API on the Windows 11 widgets board.

## Using it

```kotlin
fun main(args: Array<String>) = nucleusApplication(args) {
    widgets { widget("Counter") { CounterWidget() } }
    if (!isWidgetHostOnly()) DecoratedWindow(onCloseRequest = ::exitApplication) { … }
}
```

```kotlin
windows {
    widgets {
        widget("Counter") {
            displayName = "Counter"
            icon.set(file("widgets/icon.png"))
            preview.set(file("widgets/preview.png")) // 300 x 304, the medium widget
            customizable = true
        }
    }
}
```

Widgets need package identity, so they ship with the AppX / MSIX format only. In NSIS, MSI, portable and ZIP builds they are inert, with no error: the plugin stages nothing, `WindowsWidgetHost.isSupported` is false (no `WidgetManager` without identity), `widgets {}` is a no-op and `isWidgetHostOnly()` is false. A package with external location (sparse package) could add identity to those installers; it is deliberately not implemented.

The plugin writes the whole AppX side: the `com.microsoft.windows.widgets` extension, the COM server (`com:ExeServer … -RegisterProcessAsComServer`), the Windows App SDK `PackageDependency` (`windowsAppSdkVersion`, 1.8 by default) and the `com` / `uap3` namespaces in a widget-enabled manifest template (`nucleus/appx/AppxManifest-widgets.xml`). The CLSID is a UUID v3 of the AppX identity, baked into `nucleus-app.properties` (`NucleusApp.windowsWidgetsClsid`). Without a `widget(…)` the AppX config is byte-identical (`ElectronBuilderAppXWidgetsConfigTest`).

## Rendering model

- Each render composes the content **once**, synchronously, with a `Recomposer` that never runs, into an immutable `WidgetElement` tree; the composition is then disposed. A widget is a snapshot, not a live UI: `remember` does not survive, the widget state does.
- Actions get **positional keys** (`a0`, `a1`, …). An action is executed by composing the widget again from the state the host sent with it, then running the action registered under the key: the key resolves even after a process restart, and a lambda changes the state the host persists next.
- Inputs use the state key's storage name (`s:city`) as their Adaptive Card id. The host sends their values in the `Action.Execute` data; the engine writes them into the state before the action runs.
- Customization is a reserved state key (`b:nucleus.customizing`), so it survives a restart: `OnCustomizationRequested` sets it, `actionFinishCustomization()` clears it.
- The Windows host renders the tree as an Adaptive Card 1.5 template with the values inline (data `{}`), stores the state in the widget's custom state, and serializes every render on one coroutine (`WidgetEngine.scope`).
- `ImageProvider.appResource(path)` becomes the installed file's `file:///` URI, which the board loads. Checked on the board: `ms-appx:///` resolves against the host's own package and `ms-appx://<family name>/` is not loaded. Bytes become a data URI (logged above 64 KB: re-sent with every update).
- `WebContent(url)` becomes the card's `metadata.webUrl`; the rest of the body is the fallback (Windows documents web widgets for the European Economic Area; this board showed them in Israel too). A URL matching the widget's `webRequestFilter` never reaches the network when `onWebRequest` answers it: the Lab's `LabWeb` serves its whole page that way and echoes the page's `postMessage` back with `sendWidgetMessage`.
- `ImageSize.Icon` is pinned at 20 px (`width` / `height`): Adaptive Cards' smallest named size is a 40 px avatar. A `Row` takes its parent `Column`'s horizontal alignment (`ColumnSet.horizontalAlignment`).
- Layout on the board: its header already shows the app icon and the widget name, so a card should not repeat them. `LabCounter` and `LabTasks` follow the board's own widgets (value or status first, actions along the bottom, links as accent text rather than buttons); the picker preview is a static PNG, so compare it with a screenshot of the real card.
- An `ActionCallback` that calls `updateWidgetState` on its own widget edits the state the action runs on (`WidgetEngine.acting`), whatever the host: the widget is rendered with it right after.
- `WidgetBackground.Accent` makes the host draw the card's buttons with little contrast: prefer `Emphasis` behind buttons.

## Process lifetime

- A click on an action that runs app code with the app closed makes the widgets board start it through COM. `isWidgetHostOnly()` keeps that process windowless until a single-instance restore (the user starts the app, or a deep link), and exits it once no widget has been visible for `idleExit` (30 s).
- The COM launch is read from `GetCommandLineW`: `ProcessHandle` exposes no arguments on Windows.
- The provider is registered on the first composition of `nucleusApplication`, well within the time COM waits for the class object.

## Silent failures (and what each was)

The widgets board reports almost nothing; each of these looked like "nothing happens":

- **Pinning does nothing, no COM launch.** The host's WinRT metadata marshaler reads `IInspectable::GetRuntimeClassName` and drops a provider whose name is in no `.winmd`. The provider reports `Microsoft.Windows.Widgets.Providers.IWidgetProvider`, as C++/WinRT does.
- **The card loads forever, `UpdateWidget` fails with `0x800706BA`.** The `WidgetManager` proxy dies with `WidgetService` (Store update, crash, restart). Calls failing with an RPC disconnection drop the cached proxy and retry once.
- **The picker shows no icon or preview, or pinning fails.** The host reads `Icon` / `Screenshot` paths as plain files, not through `resources.pri`: the plugin copies them unqualified into `app\widgets\<id>\`, never pointing at the `.scale-*` AppX assets.
- **`makeappx` rejects the manifest.** `PublicFolder` is required below MinVersion 10.0.18307 (any existing root folder: `assets`).

## Debugging

- `NUCLEUS_WIDGET_TRACE=<file>`: the native bridge appends every class-object request and provider `QueryInterface` to the file. A host that creates the object, queries `IWidgetProvider` and stops is the runtime class name failure above.
- `WidgetManager.deleteWidget` (the probe's Unpin) removes the widget from `WidgetService` and calls the provider's `DeleteWidget`, but an open board keeps drawing the card until it is reopened. Nothing on the provider side refreshes it.
- `pinnedWidgets()` leaves out widgets the host already deleted: `WidgetService` still lists a widget for a moment after its `DeleteWidget` callback, so a list read on the `Unpinned` event (the Lab probe does) brought it back.
- `NucleusWidgets.events`: pins, activations, actions, renders (with their time), host errors (`onErrorInfoReported`, e.g. an invalid template) and failures.
- Activate the provider like the host: `[Activator]::CreateInstance([Type]::GetTypeFromCLSID([Guid]'<clsid>'))` in PowerShell (the `QueryInterface` of `IWidgetProvider` then fails on the PowerShell side, which lacks the metadata; the trace shows the provider side). `HKLM\SOFTWARE\Classes\PackagedCom\ClassIndex\{clsid}` proves the package registered the class.
- After reinstalling, the picker may keep old metadata: restart `WidgetBoard.exe` / `WidgetService.exe` (they restart on demand).

## Local AppX builds

- `./gradlew :examples:lab:app:runAppX` installs the Lab AppX (signed with `packaging/KDroidFilter.pfx`, `CN=D541E802-…`, the Store identity) and runs the manifest's executable.
- electron-builder 26.17 downloads a Windows Kits bundle whose tools may fail to spawn behind a filtering proxy (`spawn UNKNOWN`). Point it at the installed SDK: `ELECTRON_BUILDER_WINDOWS_KITS_PATH=C:\Program Files (x86)\Windows Kits\10\bin\<version>\x64`.
- The Lab probe `shell.windows-widgets` covers pinning, actions, app-side updates, resizing, cold start, persistence, deep links, customization, resource images and the web widget fallback.
