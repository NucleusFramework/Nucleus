# Migration `tao` → `winit`

Plan de migration big-bang (sans dual-stack) pour le backend windowing du module `decorated-window-tao`.

## Contexte

Le backend actuel s'appuie sur `tao 0.35` qui :
- N'expose pas les gestes macOS (`PinchGesture`, `RotationGesture`, etc.) — bloque la fonctionnalité multitouch.
- Est en mode maintenance (l'écosystème Tauri est revenu sur `winit`).
- Force une dépendance GTK sur Linux dont nous n'utilisons quasiment rien.
- Nous a déjà imposé plusieurs contournements via code natif parallèle (a11y, traffic-lights, drag, deep links, dnd, main-thread dispatch).

`winit 0.30` apporte :
- Support natif des gestures macOS (`WindowEvent::PinchGesture`, `RotationGesture`, `DoubleTapGesture`).
- Support direct Xlib/Wayland sur Linux **sans GTK**.
- Maintenance active et large adoption.
- Compatibilité `raw-window-handle 0.6` via feature `rwh_06` (alignée avec le code existant).

## Stratégie

- **Big-bang sur la branche `wip/winit-migration`** — pas de dual-stack `cfg`-gaté (la duplication d'`event_loop.rs` est plus coûteuse que le risque de migration unifiée).
- **Aucun changement côté Kotlin/JNI/ObjC** pendant les phases 1-5 — la surface publique reste stable.
- **Renommage du module en phase 6** uniquement.

## Impact attendu

| Composant | Effort | Risque |
|---|---|---|
| Event loop Rust (`event_loop.rs`) | Moyen — réécriture mécanique vers `ApplicationHandler` | Moyen |
| macOS `NSView`/`NSWindow` handles | Faible — `raw_window_handle` | Faible |
| **macOS a11y** (`a11y.m`, 1838 lignes) | **30 min** — un swizzle à dynamiser | **Faible** |
| Linux GTK | **Suppression**, pas migration | Faible |
| Linux a11y (D-Bus AT-SPI2) | Aucun changement | Trivial |
| Windows a11y (UIA via DLL sœur) | Aucun changement | Trivial |
| Windows `HWND` | Faible — `raw_window_handle` | Faible |
| Gestures macOS | **Bonus gratuit** | — |

---

## Phase 0 — Préparation

- [ ] Créer la branche `wip/winit-migration` à partir de `wip/tao-experiment`
- [ ] Geler tout nouveau bypass tao pendant la migration
- [ ] Vérifier que `./gradlew preMerge` est vert au point de départ
- [ ] Lecture confirmatoire de `a11y.m` pour valider que le swizzle est la seule friction

---

## Phase 1 — Cargo + scaffolding

**Objectif** : remplacer la dépendance, le code ne compile pas encore.

- [ ] `Cargo.toml` : remplacer
  ```toml
  tao = "0.35"
  ```
  par
  ```toml
  winit = { version = "0.30", features = ["rwh_06"] }
  ```
- [ ] Retirer `gtk = "0.18"` du `Cargo.toml` (Linux)
- [ ] Retirer `gdkx11-sys = "0.18"` du `Cargo.toml` (Linux)
- [ ] Vérifier que `raw-window-handle = "0.6"` reste pinné explicitement
- [ ] `cargo check` — recense toutes les erreurs (~30-50 attendues), guide la phase 2

---

## Phase 2 — Event loop (cœur de la migration)

**Objectif** : `event_loop.rs` compile sur les 3 OS via `winit`.

### 2.1 Restructuration `ApplicationHandler`

- [ ] Créer un struct `NucleusApp` qui encapsule l'état (le contenu actuel du closure)
- [ ] Implémenter `ApplicationHandler<UserEvent> for NucleusApp` :
  - [ ] `resumed(&mut self, event_loop)` → équivalent `StartCause::Init` → dispatch `EVENT_LAUNCHED`
  - [ ] `user_event(&mut self, event_loop, ev)` → traitement des `UserEvent::*`
  - [ ] `window_event(&mut self, event_loop, id, ev)` → match sur `WindowEvent`
  - [ ] `about_to_wait(&mut self, event_loop)` → dispatch `EVENT_MAIN_EVENTS_CLEARED`
- [ ] Remplacer `EventLoop::run(closure)` par `event_loop.run_app(&mut app)`

### 2.2 Mapping des événements

- [ ] `WindowEvent::ReceivedImeText(s)` → `WindowEvent::Ime(Ime::Commit(s))`
- [ ] `Event::MainEventsCleared` → `Event::AboutToWait` (devient `about_to_wait` sur le handler)
- [ ] `Event::RedrawRequested` (top-level) → `WindowEvent::RedrawRequested` (déplacé dans `WindowEvent`)
- [ ] `WindowEvent::ModifiersChanged(state)` : `state` devient `Modifiers`, lecture via `.state().shift_key()` etc.
- [ ] `WindowEvent::KeyboardInput { event, .. }` : champ identique, type `KeyEvent` à vérifier
- [ ] `WindowEvent::Focused`, `CursorMoved`, `CursorLeft`, `MouseInput`, `MouseWheel`, `Resized`, `Moved`, `ScaleFactorChanged`, `CloseRequested`, `Destroyed` : aucun changement
- [ ] `MouseScrollDelta::LineDelta`/`PixelDelta` : aucun changement

### 2.3 API Window

- [ ] `WindowBuilder::new().build(&event_loop)` → `event_loop.create_window(WindowAttributes::default()...)`
- [ ] `Window::set_always_on_top(bool)` → `Window::set_window_level(WindowLevel::AlwaysOnTop|Normal)`
- [ ] `Window::set_focus()` → `Window::focus_window()`
- [ ] `set_visible`, `set_title`, `request_redraw`, `set_maximized`, `set_minimized`, `set_focusable`, `set_min_inner_size`, `set_inner_size`, `set_outer_position`, `set_fullscreen`, `set_window_icon`, `scale_factor`, `inner_size`, `id` : aucun changement

### 2.4 EventLoopProxy

- [ ] `EventLoopProxy<UserEvent>::send_event(...)` : API identique en winit 0.30 — aucun changement

### 2.5 Initialisation Linux any-thread

- [ ] Remplacer `tao::platform::unix::EventLoopBuilderExtUnix::with_any_thread(true)` par :
  ```rust
  #[cfg(all(unix, not(target_os = "macos")))]
  {
      use winit::platform::wayland::EventLoopBuilderExtWayland;
      use winit::platform::x11::EventLoopBuilderExtX11;
      // appeler with_any_thread(true) sur les deux extensions
  }
  ```

### 2.6 Validation phase 2

- [ ] `cargo check` propre sur les 3 OS
- [ ] `cargo build` produit les 6 binaires natifs (3 OS × 2 archs)

---

## Phase 3 — Plateformes spécifiques

### 3.1 macOS — handles

- [ ] `platform/macos/handles.rs` : remplacer `WindowExtMacOS::ns_view()` par `Window::window_handle()` + `RawWindowHandle::AppKit { ns_view }`
- [ ] `platform/macos/ime.rs` : idem
- [ ] `window_jni.rs` : remplacer `WindowExtMacOS::ns_window()` par accès via `RawWindowHandle::AppKit` + `[ns_view window]`

### 3.2 macOS — a11y (swizzle dynamique)

Le code actuel cible `objc_getClass("TaoView")` (ligne 1561 de `a11y.m`). Avec winit la classe ObjC s'appelle différemment.

- [ ] Modifier `nucleus_tao_swizzle_taoview_a11y_once()` pour récupérer la classe dynamiquement à partir du `NSView*` reçu en paramètre :
  ```objc
  Class c = object_getClass(view);
  ```
- [ ] Stocker un `NSMutableSet<NSString*>` des classes déjà swizzlées (pattern déjà présent ligne 1614 pour le focus forwarder)
- [ ] Tester avec **VoiceOver** : navigation, lecture, activation, rotors

### 3.3 macOS — main thread / event loop helpers

- [ ] `platform/macos/main_thread.rs` : adapter à la boucle winit (vérifier `pump_app_events` ou équivalent)
- [ ] Les helpers ObjC (`NucleusTaoMetal.m`, `main_thread_dispatch.m`, `window_drag.m`, `apple_events.m`, `dnd.m`) consomment des `NSView*`/`NSWindow*` — **aucun changement nécessaire**

### 3.4 Linux — suppression GTK

- [ ] `platform/linux/handles.rs` : retirer le passage par `WindowExtUnix::gtk_window()`. Lire directement `Window::window_handle()` qui expose `RawWindowHandle::Xlib`/`Wayland`
- [ ] `platform/linux/cursor.rs` : **supprimer** la branche GDK (`gdk_window_set_device_cursor`). Utiliser `Window::set_cursor(CursorIcon::*)` standard de winit. Vérifier que le bug "curseur reset au mouvement" de tao n'existe pas avec winit (test manuel).
- [ ] Retirer toutes les imports `gtk::*` et `gdkx11_sys::*`
- [ ] `platform/linux/a11y.rs` (AccessKit + AT-SPI2) : **aucun changement**

### 3.5 Windows — handles

- [ ] `platform/windows/handles.rs` : remplacer `WindowExtWindows::hwnd()` par `Window::window_handle()` + `RawWindowHandle::Win32 { hwnd, .. }`
- [ ] La DLL custom de décoration consomme un `HWND` → **aucun changement**
- [ ] `platform/windows/a11y.rs` : **aucun changement**

---

## Phase 4 — Multitouch macOS (besoin originel)

**Objectif** : exposer `pinch` + `smartMagnify` via une API Compose.

### 4.1 Côté Rust

- [ ] Ajouter dans `events.rs` :
  ```rust
  pub(crate) const EVENT_GESTURE_MAGNIFY: jint = 22;
  pub(crate) const GESTURE_FIXED_SCALE: f64 = 1000.0;
  pub(crate) const GESTURE_STATE_BEGAN: jint = 0;
  pub(crate) const GESTURE_STATE_CHANGED: jint = 1;
  pub(crate) const GESTURE_STATE_ENDED: jint = 2;
  pub(crate) const GESTURE_STATE_CANCELLED: jint = 3;
  ```
- [ ] Ajouter le helper `dispatch_gesture(handle, kind, state, scale_fixed, x_fixed, y_fixed)` dans `events.rs`
- [ ] Router `WindowEvent::PinchGesture { delta, phase }` dans `event_loop.rs` → `dispatch_gesture(...)`
- [ ] Router `WindowEvent::DoubleTapGesture` (smartMagnify) → `dispatch_gesture(...)` avec un `kind` distinct

### 4.2 Côté Kotlin

- [ ] `TaoEventCode.kt` : ajouter `GESTURE_MAGNIFY = 22` et l'objet `GestureState`
- [ ] `NativeTaoBridge.kt` : ajouter `EventCallback.onGestureEvent(handle, kind, state, scaleFixed, xFixed, yFixed)`
- [ ] `TaoWindow.kt` : ajouter `fun interface GestureEventListener` + `fun onGestureEvent(listener: GestureEventListener)` + `internal fun dispatchGesture(...)`
- [ ] `TaoApplication.kt` : router `onGestureEvent` vers `window.dispatchGesture(...)`
- [ ] Créer `Modifier.onMagnify { delta, center, state -> }` dans le module Compose côté API publique

### 4.3 GraalVM

- [ ] Mettre à jour `decorated-window-tao/src/main/resources/META-INF/native-image/.../reachability-metadata.json` avec les nouvelles méthodes JNI

### 4.4 Sample

- [ ] Ajouter une démo `Image` zoomable au pinch dans `example/`
- [ ] Test trackpad : pinch in/out, smartMagnify (double-tap 2 doigts)
- [ ] Vérifier que `onPointerScroll` fonctionne toujours (pan trackpad 2 doigts)

---

## Phase 5 — Validation cross-OS

### 5.1 macOS

- [ ] Sample `example` lance et affiche
- [ ] Lifecycle : open / resize / minimize / maximize / close
- [ ] Input : souris, clavier, scroll, IME, touches mortes
- [ ] Décoration custom (traffic lights via `NucleusTaoMetal.m`)
- [ ] Drag-window via barre de titre
- [ ] Drag & drop fichiers
- [ ] Deep links (Apple Events)
- [ ] **AccessKit + VoiceOver** (zone à surveiller post-swizzle)
- [ ] HiDPI : Retina + écran externe non-Retina + bascule entre écrans
- [ ] Pinch-to-zoom fonctionnel
- [ ] CI macOS verte (x64 + aarch64)

### 5.2 Linux

- [ ] Sample lance sous **X11** (Ubuntu/Fedora)
- [ ] Sample lance sous **Wayland** (GNOME / KDE)
- [ ] Curseurs corrects sous X11 et Wayland (text, hand, resize)
- [ ] HiDPI 4K
- [ ] AccessKit + Orca
- [ ] D-Bus features (`notification-linux`, `launcher-linux`) intactes
- [ ] Clavier + IME
- [ ] CI Linux verte (x64 + aarch64)

### 5.3 Windows

- [ ] Sample lance sous Windows 10 et 11
- [ ] Décoration custom (traffic-light style)
- [ ] HiDPI multi-écrans (scales différents)
- [ ] Launcher Windows (badge, jump list)
- [ ] Notifications WinRT
- [ ] UIA / Narrator
- [ ] CI Windows verte (x64 + aarch64)

### 5.4 Cross-OS

- [ ] `./gradlew preMerge` vert
- [ ] `./gradlew packageDistributionForCurrentOS` produit un binaire fonctionnel sur chaque OS
- [ ] `./gradlew packageReleaseDistributionForCurrentOS` (ProGuard) OK
- [ ] GraalVM native-image build OK (`./gradlew nativeCompile` dans `example`)

---

## Phase 6 — Cleanup & renaming

**Objectif** : supprimer toute trace de `tao` dans les noms et la documentation.

### 6.1 Module Gradle

- [ ] Renommer le répertoire `decorated-window-tao` → `decorated-window-winit`
- [ ] Mettre à jour `settings.gradle.kts` (déclaration du module)
- [ ] Mettre à jour les `build.gradle.kts` consommateurs (samples, autres modules)

### 6.2 Lib native

- [ ] Décider : conserver `nucleus_tao` comme nom de lib (stabilité ABI) **ou** renommer en `nucleus_winit`
- [ ] Si renommage : adapter `build.sh`, `build.bat`, `Cargo.toml` (`name = "nucleus_winit"`), tous les `JNIEXPORT` ObjC, `NativeLibraryLoader`
- [ ] Adapter le chemin des artefacts dans `src/main/resources/nucleus/native/...`

### 6.3 Kotlin

- [ ] `TaoApplication` → `WinitApplication`
- [ ] `TaoWindow` → `WinitWindow`
- [ ] `TaoEventCode` → `WinitEventCode`
- [ ] `NativeTaoBridge` → `NativeWinitBridge`
- [ ] `TaoMainDispatcher` → `WinitMainDispatcher`
- [ ] Adapter le package : `io.github.kdroidfilter.nucleus.window.tao` → `.winit`
- [ ] Adapter tous les samples + modules consommateurs

### 6.4 Symboles natifs

- [ ] Renommer `nucleus_tao_*` → `nucleus_winit_*` dans tous les `.m`, `.c`, `.cpp`, `.rs`
- [ ] Synchroniser les `extern "C" fn` Rust et les déclarations C
- [ ] Mettre à jour les noms dans `reachability-metadata.json`

### 6.5 CI

- [ ] `build-natives.yaml` : noms d'artefacts (si renommage natif)
- [ ] `pre-merge.yaml`, `publish-maven.yaml`, `publish-plugin.yaml`, `test-packaging.yaml`, `test-graalvm.yaml`, `release-graalvm.yaml` : tableaux EXPECTED + chemins de download

### 6.6 Documentation

- [ ] Mettre à jour `CLAUDE.md` (références au backend tao)
- [ ] Mettre à jour le `README.md` du module
- [ ] Archiver `MIGRATION.md` dans `docs/` (ou supprimer)
- [ ] Ajouter une note de release notable (les utilisateurs doivent renommer leurs imports)

---

## Risques résiduels & rollback

| Risque | Mitigation |
|---|---|
| `WindowEvent::Focused` subtilement différent (winit corrige certains bugs de focus stealing) | Test manuel sur les 3 OS |
| `set_outer_position` muet sur Wayland (limitation Wayland, déjà le cas avec tao) | Comportement identique, aucun changement |
| Bug surprise dans winit 0.30 sur un OS | `git revert` la branche entière, fallback sur tao via `wip/tao-experiment` |
| Cours du `swizzle a11y` rate sur la classe winit | Test VoiceOver dès le début de phase 5.1 ; le swizzle dynamique sur `object_getClass(view)` rend le code robuste à toute renommée |
| `pump_app_events` macOS comportement différent | Vérifier le flow main-thread dispatcher dès phase 3.3 |

---

## Bonus collatéraux

- ✅ Pinch-to-zoom natif sans code custom
- ✅ Suppression de la dette GTK sur Linux (-2 dépendances système)
- ✅ `WindowEvent::Touch` moderne disponible pour usage tactile futur
- ✅ Meilleure intégration IME sur Wayland (text-input v3)
- ✅ Maintenance active : winit suit les évolutions OS (Apple Silicon, Wayland, Windows 11)
- ✅ Support potentiel futur de l'intégration AccessKit native de winit (cleanup post-migration optionnel)

---

## Ordre d'exécution recommandé

1. Phase 0 : 30 min
2. Phase 1 + 2 : 1-2 jours (cœur de la migration)
3. Phase 3 : 1 jour (parallélisable par OS)
4. Phase 4 : 0.5 jour (gestures macOS)
5. Phase 5 : 1 jour (validation manuelle sur 3 OS)
6. Phase 6 : 0.5 jour (renaming mécanique)

**Total estimé : 4-5 jours de travail**, exécutables en une seule branche, mergeable en un seul PR.
