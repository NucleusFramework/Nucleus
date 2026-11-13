# Android widgets internals

`widget-runtime` hosts its Glance-like API on Android app widgets itself, in `androidMain`: no native code and no plugin are involved, so there is no `widget-android` module. The host draws each render as `RemoteViews` directly, without Glance (see *Why not Glance*).

## Using it

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The same declaration as nucleusApplication { widgets { } } on desktop.
        NucleusWidgets.start { widget("Counter") { CounterWidget() } }
    }
}

// Android tells widget kinds apart by their receiver: one empty class per widget.
class CounterWidgetReceiver : NucleusWidgetReceiver("Counter")
```

```xml
<receiver android:name=".CounterWidgetReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider" android:resource="@xml/counter_widget" />
</receiver>
```

Give the `appwidget-provider` XML `android:initialLayout="@layout/nucleus_widget_root"` (the empty widget frame): the launcher inflates it while the app's process starts, and without it logs `Resource ID #0x0` after every install or update.

The declaration belongs in `Application.onCreate`: Android starts the process for a widget broadcast alone, and `Application.onCreate` is the only app code that runs before the receiver. A receiver whose id is not declared logs an error on tag `NucleusWidgets` and draws nothing. `WidgetContextProvider` (merged from the library manifest) captures the application context before that, so `NucleusWidgets.start` needs no `Context`.

`ImageProvider.appResource(path)` reads `assets/<path>`. URLs are downloaded (5 s timeout) and cached; SVG is left out.

## Host

- `WidgetId` = the `appWidgetId`. Size class from the portrait size in the widget's options: width < 220 dp → Small, height < 250 dp → Medium, else Large; `onAppWidgetOptionsChanged` re-renders and emits `Resized`.
- State: `SharedPreferences` `dev.nucleusframework.widget`, `state:<appWidgetId>`; removed with the widget (`onDeleted` → `Unpinned`), like Windows and macOS.
- `pinnedWidgets()` / `updateAll()` from app code: the host records `receiver:<class> → definitionId` each time a receiver runs (a placed widget's receiver always ran once), then asks `AppWidgetManager.getAppWidgetIds` per receiver.
- Every broadcast runs on `WidgetEngine.scope` under `goAsync()`.
- Clicks are broadcast `PendingIntent`s to the widget's own receiver. The key, the `WidgetActionSignatures` signature and the input value go in the intent's **data URI**, not in extras, so each distinct click is its own `PendingIntent` and an older render never fires a newer one's extras. Outdated clicks are dropped with `ActionDropped`, as on macOS.
- Inputs write their value when clicked, as on WidgetKit. Android 12+ uses a real `Switch` / `RadioButton`; before, `RemoteViews` refuse `CompoundButton`s and both become a clickable line with a ☐/☑ or ○/◉ glyph. A collapsed `ChoiceField` is drawn as a list too: there is no drop-down in `RemoteViews`. `TextField` / `NumberField` (Windows-only) are left out.
- `actionOpenUrl` is a `PendingIntent.getActivity(ACTION_VIEW)` and runs no app code.
- State is written with `commit()` on the engine's thread before the launcher is updated, and only when it changed.
- A render that throws, or nests past 10 levels, is replaced by a one-line notice and reported (`Failed` / `HostError`).

## Renderer (`RemoteViewsRenderer`)

`RemoteViews` only inflate layouts and call whitelisted setters: no layout params, no weights at run time. So:

- Containers are `nucleus_{column,row}_{w,m}{w,m}` (wrap / match width × height).
- Leaves (and weighted containers) go into a sizing slot `nucleus_slot_<w><h>[_<weight>]` (`z` = 0 dp + weight) and fill it. These variants are generated files; weights are clamped to 1..4.
- `fillMaxHeight` in a column is weight 1 (like a Compose `Spacer`). `fillMaxWidth` or `weight` in a row is a weighted width.
- Gaps between children are empty `FrameLayout`s sized by their padding.
- The composition's implicit root column is unwrapped, as on macOS, so the widget's root background is the frame's own.

Traps found on the emulator:

- **Reapply.** When the root layout id is unchanged, launchers *reapply* the new `RemoteViews` onto the tree they show, and `addView` appends to the existing children. Every container (and the frame) calls `removeAllViews` first; the frame's background is always set, since the frame itself is never re-created.
- **Recycling (Android 12+).** Children go in with `addStableView`: the launcher reuses a view with the same stable id and layout. A reused view keeps every property the last render set, so the stable id is the child's position plus a key of what it sets only sometimes (background, click, color, line limit): it is reused only when this render sets the same ones again.
- **Nesting.** Android refuses more than 10 nested `addView` levels under the frame (`Too many nested views`, measured: 8 nested columns around a text pass, 9 fail). The renderer counts them and draws a notice instead. Containers take no slot unless weighted, to keep the depth close to the tree's.
- **`TextView.setGravity`** is remotable from Android 12 only: before, a text that fills its parent's width keeps start alignment.
- **Width of matched children.** A `match_parent` child adds no width to a `wrap_content` `LinearLayout`: choice options only match when the choice itself fills its width.

Colors live in `values` / `values-night`, with Material You system colors in `-v31`. From Android 12, a non-default text color is set as a resource (`setColor`), resolved by the launcher, so light / dark switches need no re-render.

## Torture (fuzz, races, process death)

`examples/widget-android-demo` also declares a `Fuzz` widget whose whole tree comes from a seed (`FuzzSpec.generate`): random containers, texts (empty, 1000 chars, RTL, emoji, `\u0000`, a lone surrogate, `&=?#%`), buttons (lambda, callback, URL), images (asset, missing asset, bytes, a 4000×3000 PNG, garbage, SVG, a black-hole and an unresolvable URL), inputs, Windows-only fields and every modifier. `FuzzSpec.expected()` is the oracle: the view classes the launcher must show. Negative seeds are crafted: `-N` nests N columns, `-1000-N` N weighted rows, `-2000` one clickable row and button per tricky label. Drivers in `examples/widget-android-demo/scripts/`, with only the Fuzz widget placed:

- `fuzz.py FIRST COUNT` renders each seed and compares the launcher's view tree (`dumpsys activity top`, Android 12+; `NOTREE=1` before, where the launcher dumps no views) with the oracle, and reads the crash buffer: any launcher crash fails the seed.
- `click.py` / taps on `-2000`: every click must run its action, none may be dropped as outdated.
- `storm.sh COUNT WORKERS TAPS`: concurrent `updateWidgetState` + `update` / `updateAll` from many coroutines, a concurrent `getWidgetState` reader, taps meanwhile. No increment may be lost, and clicks counted == `ActionInvoked`.
- `kill.sh COUNT`: `kill -9` the moment a storm reports done (`adb root` before Android 12's images allow it), then read the state from disk in a fresh process.

What it found, fixed: a non-positive `maxLines` crashed the **launcher** (`StaticLayout.getLineTop(-1)`; `Text` now requires a positive value, as Compose, and the renderer bounds it). An image URL without the INTERNET permission threw a `SecurityException` through `update()` and killed the app (any image failure now leaves the image out, failing sources are retried after 60 s, images load in parallel, a URL is downloaded once). A lone surrogate in a clickable label made every click on it `ActionDropped` (the signature travels as a digest). A widget whose composition throws now shows a notice and reports `Failed` instead of throwing at `update()`'s caller and leaving a stale render. Past 10 levels the update was refused and the last render stayed: a notice now says so. `apply()` lost up to 13 state writes on `kill -9`: the state is committed, before the launcher shows it. Rapid taps lost up to 37 % of clicks on Android 12+ while the launcher re-inflated the tree: children are added with `addStableView` and recycled. `getWidgetState` returned the host's live state: it is a snapshot now.

## Why not Glance

The runtime already composes a `WidgetElement` tree. Glance would mean a second composition into Glance composables, its own session and state model (WorkManager, DataStore) next to `WidgetState` / `WidgetEngine`, and `actionRunCallback<T>()`, which instantiates its callback by reflection. The cost of going direct is the generated sizing layouts above and no responsive (`SizeF`-keyed) layouts.

## E2E

`examples/widget-android-demo` is a Counter (lambdas, `Switch`, `ChoiceField`, assets image, deep link) and a Tasks list (clickable weighted rows, icons, footer pushed down).

```bash
./gradlew :examples:widget-android-demo:installDebug
adb shell am start -n dev.nucleusframework.widgetandroiddemo/.MainActivity --es action pin-counter   # pin-tasks, push, list
adb logcat -s WidgetDemo NucleusWidgets
```

`pin-*` opens the launcher's "Add to home screen" sheet (`requestPinAppWidget`); `push` changes the counter's state from the app and calls `update`. Every `WidgetEvent` is logged. Tap widgets by text: the launcher's `uiautomator dump` exposes the `RemoteViews` content. Removal: drag the widget onto the launcher's *Remove* target with `input motionevent DOWN/MOVE/UP`. The launcher's `Error inflating RemoteViews` on tag `AppWidgetHostView` names any setter a given API level refuses. Verified on API 36 (Pixel launcher) and API 29.
