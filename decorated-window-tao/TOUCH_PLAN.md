# Plan — Support Touch & Stylet sur le backend Tao

Ce document recense ce que Tao 0.35 expose nativement en matière d'entrées
tactiles et trace le plan d'implémentation pour les remonter jusqu'à Compose.

## 1. Ce que Tao gère lui-même (sans patch ni helper natif)

| Plateforme | Event Tao émis | Contenu | État |
|---|---|---|---|
| **Windows** | `WindowEvent::Touch(Touch)` via WM_TOUCH / WM_POINTER<br>(`tao/src/platform_impl/windows/event_loop.rs:1619, 1755`) | `id`, `phase` (Started / Moved / Ended / Cancelled), `location` en pixels physiques, `force: Option<Force>` | Multi-touch complet, prêt à câbler |
| **macOS** | `WindowEvent::TouchpadPressure { pressure, stage }` | Pression Force Touch (1 seul point, pas un vrai geste multi-doigt) | Anecdotique — utile pour Force Click, pas pour pinch / zoom / rotate |
| **Linux** | *(rien)* | — | Aucun support tactile via GTK dans Tao 0.35 |
| iOS / Android | `WindowEvent::Touch(Touch)` | idem Windows | Hors périmètre desktop |

**Conclusion** : la seule plateforme desktop où Tao livre du tactile
gratuitement, c'est Windows. Tout le reste demande notre propre code natif.

## 2. État actuel dans le backend Nucleus

- `native/src/lib.rs` matche `WindowEvent::CursorMoved`, `MouseInput`,
  `MouseWheel`, `KeyboardInput`, `ReceivedImeText` — mais **pas**
  `WindowEvent::Touch`.
- Conséquence sur tablette / 2-en-1 Windows : Tao appelle bien
  `RegisterTouchWindow`, donc Windows **ne génère pas** de mouse-events de
  fallback. Les touches sont émises mais droppées silencieusement.
- Côté app : `LazyColumn`, `Modifier.verticalScroll`, `detectDragGestures`,
  `detectTransformGestures` ne réagissent pas. L'utilisateur est obligé
  d'attraper la scrollbar.
- Compose Multiplatform Desktop officiel a le même problème (cf. JBR-2702),
  donc résoudre ça côté Tao nous met **devant** Compose Desktop standard sur
  ce point.

## 3. API Compose ciblée

`ComposeScene.sendPointerEvent` expose une surcharge multi-pointeurs :

```kotlin
fun sendPointerEvent(
    eventType: PointerEventType,
    pointers: List<ComposeScenePointer>,   // id, position, pressed, type, pressure
    buttons: PointerButtons = PointerButtons(),
    keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
    scrollDelta: Offset = Offset.Zero,
    timeMillis: Long = currentTimeMillis(),
    nativeEvent: Any? = null,
    button: PointerButton? = null,
): PointerEventResult
```

Une fois nourrie avec `PointerType.Touch` et un `PointerId` unique par doigt,
toute la chaîne Compose (drag-to-scroll, fling, pinch-zoom,
`detectTransformGestures`, etc.) fonctionne sans toucher au code applicatif.

## 4. Plan d'implémentation

### Phase 0 — API discovery (0,5 j)

Choisir la stratégie pour les gestes macOS trackpad (pinch / rotate) qui n'ont
aucun équivalent Touch :

- **A.** Synthétiser deux `ComposeScenePointer` Touch autour du centre du
  geste → `detectTransformGestures` marche partout, code applicatif
  cross-platform identique. Léger mensonge sémantique.
- **B.** Nouvelle API `Modifier.macTrackpadGesture { onMagnify, onRotate }`
  spécifique au backend Tao. Honnête mais fragmenté.

→ **Recommandation : A**, avec flag interne d'opt-out si nécessaire.

### Phase 1 — Windows touchscreen (1,5 j) — *le quick win*

Tao émet déjà `WindowEvent::Touch`. Il suffit de le router.

**Rust (`native/src/lib.rs`)**
- `match WindowEvent::Touch(touch)` dans la boucle principale (~ligne 1002)
- Nouvelle méthode JNI `EventCallback.onTouchEvent(handle, id, phase, x, y, force)`
  (5 valeurs → ne tient pas dans la signature `onEvent(a, b)`, comme `onKeyEvent`)
- Constantes `TOUCH_PHASE_STARTED / MOVED / ENDED / CANCELLED`

**Kotlin**
- `NativeTaoBridge.EventCallback.onTouchEvent(...)`
- `TaoEventCode.TOUCH_PHASE_*`
- `TaoWindow` agrège l'état multi-touch (`Map<Long, ComposeScenePointer>`),
  émet l'event au host à chaque changement
- `TaoComposeSceneHost.onTouchEvent(pointers, eventType)` →
  `scene.sendPointerEvent(eventType, pointers)`

**Tests manuels** (Surface, 2-en-1, écran tactile branché)
- Drag-scroll dans `LazyColumn`
- Pinch-zoom dans une image (`detectTransformGestures`)
- Tap, double-tap, long-press
- Annulation paume / palm rejection (Cancelled)

**Risques** : aucun bloquant. Tao gère palm rejection nativement via
`Cancelled`.

### Phase 2 — Windows stylet (Surface Pen, Wacom) (0,5 j)

Au-dessus de Phase 1. Tao 0.35 ne distingue pas `POINTER_PEN` de
`POINTER_TOUCH` — les deux arrivent dans `WindowEvent::Touch`.

Deux chemins :

- **A.** Patch local de Tao (forker le match `pointer_input_type` dans
  `windows/event_loop.rs:1619`) — ~10 lignes mais entretien d'un fork
- **B.** Hook WM_POINTER nous-mêmes dans `native/windows/nucleus_tao_windows_deco.c`
  (qui sous-classe déjà la WindowProc), intercepter avant Tao

→ **Recommandation : B** — pas de dette de fork, infrastructure déjà en place.

Map de la pression (0–1024 normalisé Windows) → `ComposeScenePointer.pressure`
(0..1f) avec `PointerType.Stylus`.

### Phase 3 — macOS gestes trackpad (1 j)

Pas de touchscreen Mac, mais pinch / rotate / smart-magnify sont attendus.

**ObjC** — nouveau `native/objc/touchpad_gestures.m`, même pattern que
`objc/window_drag.m` :
- Override / catégorie sur la TaoView
- `magnifyWithEvent:` (NSEventTypeMagnify), `rotateWithEvent:`
  (NSEventTypeRotation), `smartMagnifyWithEvent:` (double-tap deux doigts)
- Forward C function vers Rust → callback Java

**Rust + JNI**
- `EventCallback.onTrackpadGesture(handle, kind, magnitudeFixed, x, y, phase)`
- `kind` : `MAGNIFY = 0`, `ROTATE = 1`, `SMART_MAGNIFY = 2`

**Kotlin (`TaoComposeSceneHost`)**
- Convertit en deux pointeurs Touch synthétiques autour du curseur, distance
  variant avec `magnitude`. `detectTransformGestures` réagit aux changements
  de distance / angle entre les deux pointeurs → pinch et rotate fonctionnels
  avec du code applicatif strictement cross-platform.

**Risques**
- macOS ARC + sous-classes dynamiques : suivre les patterns existants de
  `window_drag.m`
- Conflit potentiel avec NSGestureRecognizer si l'app en pose un par-dessus —
  tester avec un `BasicTextField` en focus pour vérifier que la sélection
  reste fluide.

### Phase 4 — Linux GTK touch (2 j)

Le plus chargé. GDK gère touch via XInput2 (X11) et le protocole Wayland touch
de manière transparente.

**GTK plumbing** — nouveau `native/linux/nucleus_tao_touch.c`, chargé via
`dlopen` (cf. CLAUDE.md, jamais de dépendance compile-time GTK) :
- Sur le `GdkWindow` accessible via le bridge GLX existant :
  `gdk_window_set_events` avec `GDK_TOUCH_MASK`
- Brancher `GDK_TOUCH_BEGIN / UPDATE / END / CANCEL` via
  `gdk_event_get_event_sequence`
- Suivi optionnel : `GtkGestureZoom`, `GtkGestureRotate` (offre des deltas
  prêts à l'emploi)

**Spécificités**
- Wayland vs X11 : transparent au niveau GDK
- Tester sur du vrai matériel — pas un émulateur tactile

**Risques** : peu d'écrans tactiles Linux dans la nature, donc valeur faible
mais coût modéré.

### Phase 5 — Cross-cutting (1 j)

- **Reachability metadata** :
  `decorated-window-tao/src/main/resources/META-INF/native-image/.../reachability-metadata.json`
  — ajouter `onTouchEvent`, `onTrackpadGesture`
- **CI** : `build-natives.yaml` recompile auto les nouveaux `.m` / `.c` via
  les `build.sh` — vérifier qu'ils sont bien picked-up
- **Sample** : nouvel écran `TouchGesturesDemo` dans `sample-tao` —
  `LazyColumn`, image pinch-zoomable, slider rotation. Sert aussi de smoke
  test
- **Doc** : section dans `docs/runtime/decorated-window-tao.md` avec
  tableau de support tactile par OS

## 5. Récap effort / valeur

| Phase | Effort | Valeur | OS |
|---|---|---|---|
| 0. Discovery | 0,5 j | — | — |
| 1. Windows touch | 1,5 j | Très haute | Windows |
| 2. Windows stylet | 0,5 j | Moyenne-haute | Windows |
| 3. macOS gestes | 1 j | Haute | macOS |
| 4. Linux GTK touch | 2 j | Faible | Linux |
| 5. Tests / CI / doc | 1 j | — | — |
| **Total** | **~6,5 j** | | |

## 6. Découpage en PRs

Une PR par phase, dans cet ordre. Chaque PR est shippable indépendamment et
livre une feature visible. Phase 1 seule constitue déjà un argument de
positionnement fort : *first JVM desktop framework with proper Windows
touchscreen support*, là où Compose Desktop officiel est bloqué par AWT
(JBR-2702).

## 7. Hors-scope explicite

- **iOS / Android** : Tao les supporte mais le backend Nucleus est
  desktop-only.
- **Stylet macOS / Linux** : volume utilisateur trop faible pour justifier
  l'effort.
- **3D Touch / Force Touch** : `TouchpadPressure` est exposé par Tao mais ne
  remonte pas naturellement à Compose. À traiter séparément si demande
  utilisateur.
- **Gestes système OS** (swipe trois doigts pour changer de bureau, etc.) :
  consommés par l'OS avant d'atteindre la fenêtre.
