# Handoff — migration `tao` → `winit`

Document de reprise pour une nouvelle session Claude (potentiellement sur une autre machine). Lis ce fichier en premier, puis `MIGRATION.md` pour le plan complet.

## TL;DR

Migration big-bang du backend windowing du module `decorated-window-tao` de `tao 0.35` vers `winit 0.30`. **macOS et Windows sont migrés et committés**, **Linux reste à faire**, le pinch-to-zoom natif (motivation initiale) est planifié en Phase 4.

PR ouverte : **https://github.com/kdroidFilter/Nucleus/pull/223** (base `wip/tao-experiment`).

## État du repo

- **Branche courante** : `wip/winit-migration` (poussée sur origin)
- **Branche de base** : `wip/tao-experiment`
- **Branche main** : `main` (pas la cible directe — la PR cible `wip/tao-experiment`)
- **Repo** : https://github.com/kdroidFilter/Nucleus (a été renommé depuis `ComposeDeskKit`)

### Commits sur la branche

```
b10dc0c8  ci: build and verify decorated-window-tao natives on macOS and Windows
48d866a6  refactor(decorated-window-tao): migrate Windows backend from tao to winit
098da980  refactor(decorated-window-tao): migrate macOS backend from tao to winit
```

## Contexte projet (rappel)

**ComposeDeskKit (Nucleus)** — Toolkit Gradle pour applications JVM desktop sur macOS, Windows, Linux. Le module `decorated-window-tao` fournit un backend de fenêtrage alternatif basé jusqu'à présent sur la crate Rust `tao`, avec interop JNI vers Kotlin/Compose.

**Pourquoi migrer vers winit ?**
1. `tao 0.35` ne livre pas de gestes macOS natifs (`PinchGesture`, `RotationGesture`, `DoubleTapGesture`) — bloquant pour le besoin originel multitouch / pinch-to-zoom
2. `tao` est en mode maintenance (Tauri est revenu sur `winit`)
3. `tao` traîne une dépendance GTK sur Linux dont nous n'utilisons quasiment rien
4. ~7 contournements ObjC parallèles déjà en place (a11y, traffic-lights, drag, deep links, dnd, main-thread dispatch, NucleusTaoMetal) — le coût marginal d'un nouveau bypass dépasse celui d'un changement de crate

`winit 0.30` apporte les gestes nativement, supprime GTK Linux, et a une maintenance active. `raw-window-handle 0.6` reste compatible (feature `rwh_06`).

## Ce qui est fait ✅

### Phase 0 — Préparation
- Branche `wip/winit-migration` créée
- `MIGRATION.md` rédigé (plan en 6 phases avec checkboxes)

### Phase 1 — Cargo
- `Cargo.toml` : `tao = "0.35"` → `winit = { version = "0.30", features = ["rwh_06"] }`
- `gtk = "0.18"` et `gdkx11-sys = "0.18"` retirés
- `glib = "0.18"` **conservé temporairement** pour le code GDK legacy dans `linux/{handles,cursor}.rs` (sera retiré en 3.4)
- `Cargo.lock` mis à jour

### Phase 2 — macOS
Tous fichiers Rust dans `decorated-window-tao/src/main/native/src/` :
- `state.rs`, `events.rs`, `keymap.rs`, `cursor.rs` — imports `tao::*` → `winit::*`
- `event_loop.rs` — réécrit autour de `ApplicationHandler<UserEvent>` (le gros morceau)
- `platform/macos/handles.rs` — nouvelle fonction `ns_view_pointer(window: &Window) -> i64` qui extrait le NSView via `raw_window_handle::AppKit`
- `platform/macos/ime.rs`, `platform/macos/text_overlay.rs` — utilisent `ns_view_pointer`
- `window_jni.rs` — `nativeDragWindow` utilise `ns_view_pointer` + nouveau helper ObjC
- ObjC : `macos/window_drag.m` — ajout de `nucleus_tao_ns_view_to_ns_window(int64) -> int64` car winit ne livre que NSView (pas NSWindow) via `RawWindowHandle::AppKit`
- `platform/macos/ffi.rs` — déclaration de la nouvelle fonction extern

### Phase 3.5 — Windows
- `platform/windows/handles.rs` — `WindowExtWindows::hwnd()` → `RawWindowHandle::Win32` ; nouvelle fn `hwnd_pointer(window: &Window) -> i64`
- `platform/windows/a11y.rs` — **aucun changement** (UIA via DLL sœur, indépendant)

### CI
- `.github/workflows/build-natives.yaml` :
  - Job `macos` : install Rust targets, run `decorated-window-tao/src/main/native/macos/build.sh`, verify 6 dylibs (3 × 2 archs), upload `decorated-window-tao-macos`
  - Job `windows` : install Rust MSVC targets, run `decorated-window-tao/src/main/native/windows/build.bat`, verify 10 DLLs (5 × 2 archs), upload `decorated-window-tao-windows`
  - Job `linux` : **pas touché** (Linux pas migré)
- `.github/workflows/pre-merge.yaml` : download des artifacts + 16 paths EXPECTED ajoutés au verify

## Ce qui reste ❌

### Phase 3.4 — Linux (le prochain gros morceau)

Fichiers à toucher :
- `decorated-window-tao/src/main/native/src/platform/linux/handles.rs` — supprimer le passage par `WindowExtUnix::gtk_window()`. Lire directement `Window::window_handle()` qui expose `RawWindowHandle::Xlib` / `Wayland` (winit le fait sans GTK)
- `decorated-window-tao/src/main/native/src/platform/linux/cursor.rs` — **supprimer** la branche GDK (`gdk_window_set_device_cursor`), utiliser `Window::set_cursor()` standard de winit. Vérifier que le bug "curseur reset au mouvement" de tao n'existe pas avec winit
- `decorated-window-tao/src/main/native/src/platform/linux/a11y.rs` — **aucun changement** (AccessKit + AT-SPI2 via D-Bus, indépendant)
- `decorated-window-tao/src/main/native/Cargo.toml` — retirer `glib = "0.18"`
- `decorated-window-tao/src/main/native/src/event_loop.rs` — déjà adapté pour winit (`EventLoopBuilderExtX11` + `EventLoopBuilderExtWayland`), mais la branche `#[cfg(target_os = "linux")]` qui appelle `gtk_window().realize()` a déjà été retirée
- `.github/workflows/build-natives.yaml` — ajouter le job `linux` pour `decorated-window-tao`
- `.github/workflows/pre-merge.yaml` — ajouter les 2 paths Linux dans EXPECTED (libnucleus_tao.so x64 + aarch64)
- `decorated-window-tao/src/main/native/linux/build.sh` — vérifier qu'il fonctionne toujours après suppression GTK

Critères de réussite :
- `cargo build` clean sur Linux
- Sample lance sous X11 + Wayland
- Curseurs corrects, HiDPI OK, AccessKit + Orca OK

### Phase 4 — Multitouch macOS (besoin originel)

`winit::event::WindowEvent::PinchGesture { delta, phase }` est désormais directement disponible. À router via :
- `events.rs` — ajouter `EVENT_GESTURE_MAGNIFY = 22`, `GESTURE_FIXED_SCALE = 1000.0`, `GESTURE_STATE_*`
- `event_loop.rs` — `WindowEvent::PinchGesture` et `WindowEvent::DoubleTapGesture` (smartMagnify)
- Côté Kotlin : `TaoEventCode.GESTURE_MAGNIFY`, `EventCallback.onGestureEvent`, `TaoWindow.onGestureEvent`, `Modifier.onMagnify { ... }`
- Sample : `Image` zoomable

### Phase 5 — Validation cross-OS

À faire principalement après Phase 3.4 et 4. Checklist détaillée dans `MIGRATION.md`.

### Phase 6 — Renommage

`decorated-window-tao` → `decorated-window-winit`. Touche le module Gradle, les noms Kotlin (`TaoApplication`, `TaoWindow`, etc.), les symboles natifs (`nucleus_tao_*` → `nucleus_winit_*`), les workflows CI. À faire en tout dernier, gros chantier mécanique.

## Décisions techniques importantes

### 1. Fix du SIGABRT à la fermeture (macOS)

**Problème** : `winit 0.30` leak un `CFRunLoopObserver` après que `run_app` retourne. La boucle AWT toujours active fire l'observer une fois de plus, où il essaie d'`upgrade()` un `Weak<PanicInfo>` déjà droppé → panique → SIGABRT (exit 134).

**Solution** dans `event_loop.rs::user_event` pour `UserEvent::Exit` :
```rust
#[cfg(target_os = "macos")]
std::process::exit(0);
#[cfg(not(target_os = "macos"))]
event_loop.exit();
```

C'est sale mais nécessaire jusqu'à un fix upstream de winit. Justification dans le commentaire du code.

### 2. NSView → NSWindow via helper ObjC

`raw_window_handle 0.6` n'expose que `ns_view: NonNull<c_void>` dans `AppKitWindowHandle` — pas de `ns_window`. Le helper ObjC `nucleus_tao_ns_view_to_ns_window` dans `window_drag.m` fait `[(__bridge NSView*)view window]`. Déclaré dans `platform/macos/ffi.rs`.

### 3. Renommages d'API winit vs tao

| tao | winit 0.30 |
|---|---|
| `WindowEvent::ReceivedImeText(s)` | `WindowEvent::Ime(Ime::Commit(s))` |
| `Event::MainEventsCleared` | méthode `about_to_wait` du handler |
| `Event::RedrawRequested(window_id)` (top-level) | `WindowEvent::RedrawRequested` (no payload) |
| `set_always_on_top(bool)` | `set_window_level(WindowLevel::AlwaysOnTop \| Normal)` |
| `set_focus()` | `focus_window()` |
| `set_inner_size(s)` | `request_inner_size(s)` (retourne `Option<PhysicalSize>`) |
| `WindowEvent::ModifiersChanged(state)` | reçoit `Modifiers`, lecture via `.state()` |
| `KeyboardInput.physical_key: KeyCode` | `PhysicalKey`, extraire via `PhysicalKey::Code(code)` |
| `CursorIcon::Hand` | `CursorIcon::Pointer` (W3C) |
| `KeyCode::Plus`, `KeyCode::NumpadStar` | n'existent pas (retirés) |

### 4. `UserEvent::SetFocusable` est devenu un no-op

`winit 0.30` n'expose pas `set_focusable` cross-platform. Si un besoin réel apparaît, brancher via `WindowExtMacOS::set_can_become_key_window` côté Mac et équivalents par OS.

### 5. Variable d'env Linux

L'ancienne `NUCLEUS_TAO_LINUX_RENDERER=x11` est traduite vers `WINIT_UNIX_BACKEND=x11` à l'init du loop pour préserver la compat ascendante.

## Gotchas rencontrées pendant la migration

1. **`KeyCode::Plus` mange le reste du match** : sans le `Plus` dans `winit::keyboard::KeyCode`, le pattern `Plus =>` devient un binding catch-all et toutes les arms suivantes sont unreachable. Compilation passe mais 70+ warnings. **À traiter en supprimant `Plus` du match si jamais une autre variante manque** (peu probable mais possible).

2. **`raw_window_handle` doit s'importer depuis `winit::raw_window_handle`** sur macOS et Windows. La crate `raw-window-handle` directe n'est exposée que pour la cible Linux dans le Cargo.toml.

3. **Linux any_thread** : winit a séparé en deux extension traits. Les deux doivent être appelées avec syntax fully-qualified pour éviter l'ambiguité :
   ```rust
   EventLoopBuilderExtX11::with_any_thread(&mut builder, true);
   EventLoopBuilderExtWayland::with_any_thread(&mut builder, true);
   ```

4. **Le swizzle a11y dans `a11y.m`** cible `objc_getClass("TaoView")` ligne 1561. Avec winit la classe ObjC s'appellera autrement (probablement `WinitView`). **À traiter** : changer pour `object_getClass(view)` dynamique au moment de `nucleus_tao_a11y_attach`. Pas urgent — l'a11y n'a pas régressé visuellement encore (à confirmer avec VoiceOver).

5. **AWT initialise NSApp avant nous**. C'est ce qui cause le bug fermeture (point 1 ci-dessus). Pas de solution propre côté winit pour l'instant.

## Comment tester

```bash
# Build natif macOS (les deux archs)
cd decorated-window-tao/src/main/native/macos && ./build.sh

# Build natif Windows (sur Windows uniquement)
cd decorated-window-tao\src\main\native\windows && build.bat

# Cross-check Windows depuis Mac (juste cargo check, pas de link)
cd decorated-window-tao/src/main/native
cargo check --target x86_64-pc-windows-gnu

# Lancer le sample (macOS)
./gradlew :example:run
# → fenêtre Compose s'ouvre, fermer doit donner exit 0 (pas 134)
```

## Comment continuer

### Reprendre Phase 3.4 (Linux)

1. `git checkout wip/winit-migration && git pull`
2. Lire `decorated-window-tao/src/main/native/src/platform/linux/handles.rs` et `cursor.rs` — c'est ce qui reste à migrer
3. Pour le verify côté Linux : se baser sur les autres Linux jobs dans `build-natives.yaml` (matrix x64+aarch64)
4. Comme Linux runner GitHub n'a pas X11/Wayland en display mode, le sample ne se lancera pas en CI — on valide le **build** uniquement, le runtime test reste manuel
5. Une fois Linux passe, ajouter `:decorated-window-tao:check` à `tasks.register("preMerge")` dans le top-level `build.gradle.kts`

### Workflow général

- Chaque phase = 1 commit + push + verifier la PR sur GitHub
- La PR existante #223 reçoit les commits supplémentaires automatiquement (push sur la même branche)

## Fichiers de référence

| Fichier | Quoi |
|---|---|
| `decorated-window-tao/MIGRATION.md` | Plan complet en 6 phases avec checkboxes |
| `decorated-window-tao/HANDOFF.md` | Ce fichier |
| `decorated-window-tao/src/main/native/Cargo.toml` | État actuel des deps Rust |
| `decorated-window-tao/src/main/native/src/event_loop.rs` | Cœur de la migration (ApplicationHandler) |
| `decorated-window-tao/src/main/native/src/platform/macos/handles.rs` | Pattern `ns_view_pointer` à reproduire pour Linux/Windows |
| `decorated-window-tao/src/main/native/macos/window_drag.m` | Helper ObjC `nucleus_tao_ns_view_to_ns_window` |
| `decorated-window-tao/src/main/native/src/platform/linux/handles.rs` | À migrer Phase 3.4 |
| `decorated-window-tao/src/main/native/src/platform/linux/cursor.rs` | À migrer Phase 3.4 |
| `.github/workflows/build-natives.yaml` | Pattern à suivre pour ajouter Linux |
| `CLAUDE.md` (racine) | Conventions projet (Kotlin, JNI, etc.) |
| `~/.claude/CLAUDE.md` (global utilisateur) | Préférences perso : pas de Co-Authored-By, anglais en commentaires, code Kotlin idiomatique, ne pas sur-expliquer (senior dev) |

## Note sur les commits

L'utilisateur a précisé dans son CLAUDE.md global :
- Pas de `Co-Authored-By` ni attribution AI dans les commits/PRs
- Conventional commits avec scope (ex: `refactor(decorated-window-tao): ...`)
- Body décrivant le pourquoi pas le quoi
