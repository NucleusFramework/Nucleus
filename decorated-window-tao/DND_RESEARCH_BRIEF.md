# Drag-and-drop sur le backend Tao — plan d'implémentation

> Ce document était initialement un brief de recherche. Après vérification
> directe des sources `org.jetbrains.compose.ui:ui-desktop:1.10.3` extraites
> du cache Gradle, l'architecture Compose côté DnD est entièrement connue.
> Ce qui suit est donc un **plan d'implémentation**, plus une recherche.

## Architecture Compose côté DnD (vérifié, Compose-MP 1.10.3)

### Hook principal : `PlatformContext.dragAndDropManager`

`PlatformContext.skiko.kt:150` :

```kotlin
@InternalComposeUiApi
interface PlatformContext {
    // …
    val dragAndDropManager: PlatformDragAndDropManager get() = EmptyDragAndDropManager
}
```

Le champ est `@InternalComposeUiApi` mais **pas** `internal` — il est
surchargeable depuis `TaoPlatformContext` (qui hérite déjà de
`PlatformContext.Empty()`). Aucune réflexion, aucune PR upstream.

`RootNodeOwner.skiko.kt:133` lit `platformContext.dragAndDropManager` à la
construction du scene root. Tout est câblé automatiquement.

### Interface à implémenter : `PlatformDragAndDropManager`

`PlatformDragAndDropManager.skiko.kt` :

```kotlin
@InternalComposeUiApi
interface PlatformDragAndDropManager {
    val isRequestDragAndDropTransferRequired: Boolean get() = false
    fun requestDragAndDropTransfer(source: PlatformDragAndDropSource, offset: Offset)
}

@InternalComposeUiApi
interface PlatformDragAndDropSource {
    fun StartTransferScope.startDragAndDropTransfer(
        offset: Offset,
        isTransferStarted: () -> Boolean,
    )

    interface StartTransferScope {
        fun startDragAndDropTransfer(
            transferData: DragAndDropTransferData,
            decorationSize: Size,
            drawDragDecoration: DrawScope.() -> Unit,
        ): Boolean
    }
}
```

Pour qu'un `Modifier.dragAndDropSource` déclenche un drag OS (sortant),
`isRequestDragAndDropTransferRequired` doit retourner `true` (sinon Compose
attend que la plateforme démarre le drag elle-même, ce qu'aucune fenêtre
Tao ne fera) et `requestDragAndDropTransfer` ouvre la session OS.

### Hook entrant : `ComposeScene.rootDragAndDropNode`

`ComposeScene.skiko.kt:122` expose publiquement :

```kotlin
val rootDragAndDropNode: ComposeSceneDragAndDropNode
```

`ComposeSceneDragAndDropNode` (`@InternalComposeUiApi`) expose les méthodes
qu'un drop natif doit appeler :

- `acceptDragAndDropTransfer(event): Boolean` — appelé sur `dragEnter`,
  retourne `false` si aucun nœud n'est intéressé (le shim doit alors
  rejeter le drag côté OS).
- `onStarted` / `onEntered` / `onMoved` / `onChanged` / `onExited` / `onDrop` / `onEnded`
- `hasEligibleDropTarget: Boolean` — utilisé par le manager AWT pour décider
  s'il faut accepter ou rejeter pendant `dragOver`.

### Référence à étudier : `AwtDragAndDropManager.desktop.kt`

Implémentation AWT par défaut. C'est exactement le pattern à dupliquer en
substituant les appels AWT par des appels Tao natifs :

- Constructeur : `(rootContainer: JComponent, getRootNode: () -> ComposeSceneDragAndDropNode)`
- `requestDragAndDropTransfer` ouvre un `StartTransferScope` ad-hoc qui
  délègue à un `ComposeTransferHandler` (sous-classe de `TransferHandler`),
  lequel appelle `exportAsDrag(...)` — l'équivalent direct de `DoDragDrop`
  côté Win32 / `beginDraggingSessionWithItems:` côté Cocoa.
- `dropTargetListener` reçoit les events `DropTargetEvent` AWT, fabrique un
  `DragAndDropEvent(action = …, nativeEvent = dtde, positionInRootImpl = …)`
  et appelle `rootNode.onEntered/onMoved/onDrop/...`.

## Format des données — `DragAndDropEvent` et `DragAndDropTransferData`

`DragAndDrop.desktop.kt` définit :

```kotlin
actual class DragAndDropEvent @ExperimentalComposeUiApi constructor(
    val action: DragAndDropTransferAction?,
    val nativeEvent: Any?,                  // ← n'importe quoi
    internal val positionInRootImpl: Offset,
)
```

`nativeEvent` est `Any?`. La fonction d'extension publique
`DragAndDropEvent.awtTransferable: Transferable` ne fonctionne **que** si
`nativeEvent` est un `DropTargetDragEvent` ou `DropTargetDropEvent` AWT —
sinon elle throw.

Conséquences :

- Le code utilisateur "standard" (qui appelle `event.awtTransferable` ou
  `event.dragData()`) **continue de marcher** si le shim Tao construit un
  `DropTargetDragEvent`/`DropTargetDropEvent` AWT comme `nativeEvent`. Ces
  classes vivent dans `java.desktop` mais leur instanciation n'allume pas
  le toolkit AWT — elles peuvent être créées en mode headless. La
  contrainte est que leur constructeur exige un `DropTarget` (qui exige un
  `Component`). On peut feinter avec un `Component` "shadow" jamais
  affiché (cf. ce que `TaoComposeSceneHost` fait déjà pour les
  `KEY_TYPED` synthétiques avec `javax.swing.JPanel()`).
- Alternative : exposer un **type Tao natif** (`TaoDropEvent`) comme
  `nativeEvent`, et fournir nos propres extensions
  (`event.taoFiles: List<File>`, `event.taoText: String?`, …). Le code
  utilisateur perd alors la portabilité avec les samples
  AWT — mais le projet vise précisément les apps qui n'embarquent pas
  AWT.

Recommandation : **les deux**. Le shim crée un `nativeEvent` opaque (data
class Kotlin) et fournit des extensions publiques `event.taoDragData()` ;
on ajoute en plus un adaptateur `Transferable` minimal pour rester
compatible avec `awtTransferable` quand l'utilisateur en a besoin. Ça reste
pur `java.datatransfer`/`java.awt.dnd` côté types — pas d'init Toolkit.

`DragAndDropTransferData(transferable: DragAndDropTransferable, …)` côté
sortant exige une `DragAndDropTransferable`. L'unique constructeur public
desktop est :

```kotlin
fun DragAndDropTransferable(transferable: java.awt.datatransfer.Transferable): DragAndDropTransferable
```

(retourne un `AwtDragAndDropTransferable` interne). Donc côté **sortant**,
les utilisateurs vont fabriquer un `Transferable` AWT (ex. `StringSelection`,
ou un `FileTransferable` custom). Le shim Tao fait alors :

```kotlin
val awt = (transferData.transferable as? AwtDragAndDropTransferable)?.toAwtTransferable()
```

et traduit les `DataFlavor`s AWT vers les payloads natifs (NSPasteboard /
IDataObject / GtkSelectionData) au moment de démarrer la session.

`AwtDragAndDropTransferable` est `internal`, mais accessible via
`(transferable as? AwtDragAndDropTransferable)?.toAwtTransferable()` —
même package + reflection si besoin (la classe internalisée est juste
`internal`, pas obfusquée).

## Le backend Tao actuel (rappel)

Module : `decorated-window-tao`. Compose Desktop sans AWT pour le rendu :

- **[Tao 0.35](https://github.com/tauri-apps/tao)** (Rust, fork de winit),
  compilé en `cdylib` `nucleus_tao`, appelé via JNI.
- **`CanvasLayersComposeScene`** rendu via Skia/Skiko directement dans :
  - macOS — `CAMetalLayer` sur le `NSView` Tao
  - Windows — WGL sur le `HWND` Tao
  - Linux — EGL (Wayland) / GLX (X11) sur la surface GTK
- **Pas de `ComposeWindow`, pas de `SkiaLayer`, pas d'EDT.** AWT n'est utilisé
  que pour fabriquer des objets de données (KeyEvent synthétique pour
  `KEY_TYPED`).

Events Tao déjà câblés (`src/main/native/src/lib.rs:1373+`) : `Resized`,
`Moved`, `ScaleFactorChanged`, `Focused`, `CloseRequested`, `Destroyed`,
`CursorMoved`, `CursorLeft`, `MouseInput`, `MouseWheel`, `KeyboardInput`,
`ReceivedImeText`, `ModifiersChanged`, `RedrawRequested`,
`MainEventsCleared`.

Tao émet `WindowEvent::FileDropped { paths }`, `WindowEvent::HoveredFile { path }`,
`WindowEvent::HoveredFileCancelled` — **non consommés**. `with_file_drop_handler`
n'est pas appelé sur le `WindowBuilder`. Ces events suffisent pour un
file-drop minimal sur Win/Linux mais pas pour la position de hover ni les
formats non-fichier.

Hooks `PlatformContext` déjà overridés dans
`TaoComposeSceneHost.kt` / `TaoPlatformContext` : `windowInfo`,
`windowInsets`, `setPointerIcon`, `startInputMethod`, `semanticsOwnerListener`.
Ajout de `dragAndDropManager` = un override de plus.

## Verdict de faisabilité (état actuel)

| Variante | macOS | Windows | Linux X11 | Linux Wayland |
|---|---|---|---|---|
| **DnD intra-scène** (source ↔ target dans la même fenêtre) | ✅ Stage 0 (manager câblé) | ✅ Stage 0 | ✅ Stage 0 | ✅ Stage 0 |
| **DnD entrant — fichiers depuis l'OS** | ⏳ Stage 2 | ✅ **Stage 3** | ⏳ Stage 4 | ⏳ Stage 4 |
| **DnD entrant — texte/HTML/URI** | ⏳ Stage 2 | ⏳ étoffer Stage 3 (1-2 j) | ⏳ Stage 4 | ⏳ Stage 4 |
| **DnD sortant — texte** | ⏳ Stage 5 | ✅ **Stage 5** | ⏳ Stage 5 | ⏳ Stage 5 |
| **DnD sortant — fichiers** | ⏳ Stage 5 | ✅ **Stage 5** | ⏳ Stage 5 | ⏳ Stage 5 |
| **API utilisateur compatible Compose Desktop** (`event.awtTransferable`) | (héritera de Stage 2) | ✅ commit `d95b0b24` | (héritera de Stage 4) | (héritera de Stage 4) |
| **Compatible GraalVM native-image** | (héritera) | ✅ tested | (héritera) | (héritera) |

**Windows complet** (entrée + sortie, JVM + native-image, API portable). Reste
à porter sur macOS (Stage 2 + 5) et Linux (Stage 4 + 5).

## Plan natif par OS

### macOS — `objc/dnd.m` (nouveau, à côté de `window_drag.m` et `a11y.m`)

Tao 0.35 n'expose pas le content-view directement, mais le projet a déjà
`nativeNsViewHandle(handle)` qui retourne le `NSView*`.

**Entrant** : sous-classer (ou catégoriser via `class_addMethod`) la NSView
Tao pour conformer à `<NSDraggingDestination>`.

| Sélecteur | Mapping Compose |
|---|---|
| `draggingEntered:` | construire `DragAndDropEvent(action, nativeEvent, posInRoot)` ; appeler `rootNode.acceptDragAndDropTransfer(ev)` ; si `false` retourner `NSDragOperationNone` |
| `draggingUpdated:` | `rootNode.onMoved(ev)` ; retourner masque selon `rootNode.hasEligibleDropTarget` |
| `prepareForDragOperation:` | `YES` si `hasEligibleDropTarget` |
| `performDragOperation:` | matérialiser depuis `[sender draggingPasteboard]` (file URLs, NSPasteboardTypeString, etc.) en un `Transferable` ; `rootNode.onDrop(ev)` |
| `concludeDragOperation:` / `draggingExited:` | `rootNode.onExited` puis `onEnded` |

`[view registerForDraggedTypes:@[NSPasteboardTypeFileURL, NSPasteboardTypeString, NSPasteboardTypeURL]]`
dans `attach()`.

**Sortant** : classe ObjC conforme à `<NSDraggingSource>`. À la fin du
drag, `draggingSession:endedAtPoint:operation:` invoque
`transferData.onTransferCompleted(action)` côté Kotlin. Image de drag
rendue depuis `drawDragDecoration` vers un `NSImage`.

### Windows — extension du WndProc subclass existant

Préreq : `OleInitialize(NULL)` sur le thread propriétaire du HWND
**avant** `RegisterDragDrop`. Le commit `3e63d55` a déjà retiré un
`CoInitialize` redondant pour préserver l'STA Tao — la voie est libre.
`OleUninitialize` au shutdown.

**Entrant** : COM object `IDropTarget` (+ `IUnknown`).

| Méthode | Mapping |
|---|---|
| `DragEnter(IDataObject*, keyState, pt, *effect)` | enum des `FORMATETC` ; construire `DragAndDropEvent` ; appeler `acceptDragAndDropTransfer` ; écrire effet de retour |
| `DragOver(keyState, pt, *effect)` | `onMoved` ; effect = `DROPEFFECT_*` selon `hasEligibleDropTarget` |
| `DragLeave()` | `onExited` puis `onEnded` |
| `Drop(IDataObject*, keyState, pt, *effect)` | matérialiser (CF_HDROP → `DragQueryFileW`, CF_UNICODETEXT → `GlobalLock`, etc.) ; `onDrop` |

`RegisterDragDrop(hwnd, target)` dans `attach()`, `RevokeDragDrop` dans
`detach()`. Le HWND est déjà accessible via `nativeHwndHandle(handle)`.

**Sortant** : `DoDragDrop(pData, pSource, allowedEffects, &result)` sur le
thread du HWND. `IDropSource` (`QueryContinueDrag`, `GiveFeedback`) +
`IDataObject` adapter sur le `Transferable` côté Kotlin (mapping
`DataFlavor` → `FORMATETC`/`STGMEDIUM`). Appel **bloquant** : doit être
pompé depuis le thread Compose, donc `requestDragAndDropTransfer` poste un
continuation et lance `DoDragDrop` à la prochaine itération de loop.

**Tao file-drop fast-path** : ne pas activer
`with_file_drop_handler` côté Rust (Win32 n'autorise qu'un `IDropTarget`
par HWND).

### Linux — extension via GTK

Le projet pull déjà `gtk = "0.18"` et `gdkx11-sys`. Tao expose la
`GtkWindow` ; le widget pertinent est le `GtkDrawingArea` enfant
(récupérable via le handle Linux que renvoie `nativeLinuxHandles`).

**Entrant** : `gtk_drag_dest_set(widget, …)` au `attach()` avec :

```c
gtk_drag_dest_set(widget,
    GTK_DEST_DEFAULT_MOTION | GTK_DEST_DEFAULT_HIGHLIGHT,
    targets, n_targets,
    GDK_ACTION_COPY | GDK_ACTION_MOVE | GDK_ACTION_LINK);
```

Targets minimum : `text/uri-list`, `text/plain;charset=utf-8`,
`application/octet-stream`. Signaux à brancher : `drag-motion`,
`drag-leave`, `drag-drop`, `drag-data-received`.

GTK abstrait XDND (X11) et `wl_data_device` (Wayland) — un seul code.

**Sortant** : `gtk_drag_begin_with_coordinates(...)`. Signaux
`drag-data-get` (remplir `GtkSelectionData` depuis le `Transferable`) et
`drag-end` (→ `onTransferCompleted`). Drag image via
`gtk_drag_set_icon_surface` sur une surface Cairo rendue depuis
`drawDragDecoration`.

**Si Tao a déjà enregistré une cible de drop sur la fenêtre** : appeler
`gtk_drag_dest_unset(window)` immédiatement après l'init Tao pour
reprendre la propriété, puis `gtk_drag_dest_set` sur **notre** widget.

## Threading & lifecycle

Mêmes hooks que les bridges existants — branchements dans `attach()` /
`detach()` de `TaoComposeSceneHost` :

| OS | Thread des callbacks DnD | Thread Compose | Bridge |
|---|---|---|---|
| macOS | main NSRunLoop | même thread (`-XstartOnFirstThread` / GraalVM) | callbacks synchrones doivent retourner vite ; consulter un snapshot `hasEligibleDropTarget` mis à jour côté Compose |
| Windows | thread propriétaire du HWND (= thread Compose) | même thread | tout sur un thread, pas de cross-thread |
| Linux | GTK main loop (= thread Compose) | même thread | idem |

JNI : sur macOS, cacher `JavaVM*` dans `JNI_OnLoad`, cacher
`jclass`/`jmethodID` au `NewGlobalRef`. Pattern `AttachCurrentThread` /
`DetachCurrentThread` défensif si la callback peut arriver sur un thread
non attaché (rare en pratique, mais voir `objc/a11y.m` pour le pattern
exact déjà utilisé dans le projet).

GraalVM native-image : ajouter dans `reachability-metadata.json`
(`decorated-window-tao/.../nucleus.decorated-window-tao/reachability-metadata.json`)
les classes JNI-touchées par le shim DnD :
- `TaoDragAndDropBridge` (object Kotlin) avec ses méthodes statiques de
  callback,
- les types `Transferable`/`DataFlavor`/`StringSelection` si le code les
  utilise par réflexion (probable côté `AwtDragAndDropTransferable`),
- toute classe `Transferable` custom écrite dans le module.

## Plan d'exécution & avancement

### ✅ Stage 0 — squelette `TaoDragAndDropManager` (livré, commit `b925e62`)

- `TaoDragAndDropManager.kt` : `PlatformDragAndDropManager` qui log + compte
  les appels (`isRequestDragAndDropTransferRequired = true`).
- `TaoDnDDiagnostics.kt` : compteurs `mutableIntStateOf` exposés à l'UI.
- Câblage dans **les trois** hosts macOS/Windows/Linux
  (`TaoComposeSceneHost.kt`, `TaoComposeSceneHostWindows.kt`,
  `TaoComposeSceneHostLinux.kt`) — chaque `PlatformContext` reçoit le
  manager par paramètre. Lambda paresseuse `{ scene!!.rootDragAndDropNode }`
  pour résoudre le chicken-and-egg constructeur.
- Sample : bandeau "DRAG ME / DROP HERE" avec compteurs vivants.
- **Validé sur Windows** : `mgr=1, qry≥1, req≥1, xfer≥1` après un drag
  source intra-scène.

> ⚠️ Piège rencontré : trois hosts existent (un par OS), pas un seul. Le
> câblage doit être répliqué dans chacun.

### ✅ Stage 3 — Windows entrant `IDropTarget` (livré, commits `0121b25a` + `d95b0b24`)

**Couche native** (`nucleus_tao_dnd.dll`, ~430 lignes C) :
- `nucleus_tao_dnd.c` : COM object `IDropTarget` avec vtable statique
  partagée entre instances. Implémente `QueryInterface` / `AddRef` /
  `Release` + les 4 méthodes DnD.
- JNI exports `nativeRegister(hwnd, callback) → HRESULT` et `nativeRevoke(hwnd)`.
- `OleInitialize(NULL)` reference-counted sur le thread du HWND (= thread
  Compose), `OleUninitialize` à la révocation.
- **Eviction de Tao** : Tao 0.35 enregistre **toujours** son propre
  `IDropTarget` pour pouvoir émettre `WindowEvent::FileDropped`.
  `RegisterDragDrop` retourne alors `DRAGDROP_E_ALREADYREGISTERED` (HRESULT
  0x80040101). On appelle `RevokeDragDrop(hwnd)` avant notre register pour
  reprendre le contrôle. Effet collatéral : `WindowEvent::FileDropped` côté
  Rust ne sort plus — c'est voulu, on prend tout en charge côté Kotlin.
- Stubs `memset` / `memcpy` / `memcmp` requis (`/NODEFAULTLIB`).
  `IsEqualIID` se développe en `memcmp` — piège à anticiper.
- Linkage : `kernel32.lib user32.lib ole32.lib oleaut32.lib uuid.lib shell32.lib`.

**Couche Kotlin** :
- `NativeTaoWindowsDndBridge.kt` : `external fun nativeRegister/nativeRevoke` +
  interface `Callback { onDragEnter, onDragOver, onDragLeave, onDrop }`.
- `TaoDragAndDropPayload.kt` : POJO data class minimaliste (`files: List<String>`)
  exposable comme `nativeEvent` pour les utilisateurs qui veulent éviter AWT.
- `InboundDnDCallback` (inner class **nommée**, pas anonyme — important pour
  GraalVM JNI metadata, voir risques) dans `TaoComposeSceneHostWindows`.
  Construit un `DragAndDropEvent` à chaque callback et appelle les méthodes
  publiques de `scene.rootDragAndDropNode` (`acceptDragAndDropTransfer` →
  `onStarted` → `onEntered` → `onMoved` → `onDrop` → `onEnded`).

**Couche transparence AWT** (commit `d95b0b24`) :
- `TaoFilesTransferable.kt` : implémente `java.awt.datatransfer.Transferable`
  exposant `javaFileListFlavor` (`List<File>`) + `stringFlavor` (paths
  joints `\n`).
- `TaoSyntheticAwtDnd.kt` : sous-classes de `DropTargetDragEvent` /
  `DropTargetDropEvent` qui overrident `getTransferable()` pour retourner
  notre `Transferable`. `acceptDrop` / `dropComplete` no-op (pas de peer
  natif AWT). Utilisent un `JPanel + DropTarget` shadow (jamais affiché).
  Construction sans init du Toolkit AWT (les classes
  `java.awt.datatransfer.*` vivent dans `java.datatransfer`, headless-safe ;
  `JPanel` est déjà chargé pour les events `KEY_TYPED` synthétiques).
- Conséquence : `event.awtTransferable.getTransferData(javaFileListFlavor)`
  fonctionne **identiquement** à `decorated-window-jni` / Compose Desktop
  standard. Code utilisateur portable sans modification.

**Reachability metadata** (`reachability-metadata.json`) :
- 12 entrées ajoutées (callback nommée, `DataFlavor`, `Transferable`,
  `DropTarget`, `DropTargetContext`, `DropTargetDragEvent`,
  `DropTargetDropEvent`, `JPanel`, `File`, `ArrayList`).

> ⚠️ Pièges rencontrés :
> - DLL `nucleus_tao_dnd.dll` séparée de `nucleus_tao.dll` — le
>   `NativeLibraryLoader` les charge indépendamment.
> - La callback DOIT être une classe nommée (pas anonyme `object : Callback`)
>   pour que `GetMethodID` réussisse en native-image.
> - `positionInRootImpl` est un paramètre `internal val` du constructeur de
>   `DragAndDropEvent`, mais le **paramètre** lui-même est public — appelable
>   depuis l'extérieur via argument nommé. Pas besoin de réflexion.

### ⏳ Stage 2 — macOS entrant `NSDraggingDestination` (3-4 j)

`objc/dnd.m` à créer à côté de `objc/window_drag.m`. Ajouter une
catégorie ou sous-classe sur la `NSView` Tao (déjà accessible via
`NativeTaoBridge.nativeNsViewHandle`) implémentant le protocole
`<NSDraggingDestination>` :

| Sélecteur | Action |
|---|---|
| `draggingEntered:` | construire `TaoSyntheticDragEvent`, appeler `rootDragAndDropNode.acceptDragAndDropTransfer` ; retourner `NSDragOperationCopy` ou `None` |
| `draggingUpdated:` | `onMoved` ; effet selon `hasEligibleDropTarget` |
| `draggingExited:` | `onExited` + `onEnded` |
| `prepareForDragOperation:` | `YES` si `hasEligibleDropTarget` |
| `performDragOperation:` | matérialiser depuis `[sender draggingPasteboard]` (file URLs, NSPasteboardTypeString) ; `onDrop` |
| `concludeDragOperation:` | cleanup |

`[view registerForDraggedTypes:@[NSPasteboardTypeFileURL, NSPasteboardTypeString, NSPasteboardTypeURL]]` au `attach()`.

Réutiliser `TaoSyntheticDragEvent`/`DropEvent`/`TaoFilesTransferable`
existants — la couche transparence AWT est commune à tous les OS.

JNI : pattern `objc/a11y.m` (cache `JavaVM*` dans `JNI_OnLoad`,
`AttachCurrentThread` défensif). La macOS main thread est généralement
attachée mais le défensif est gratuit.

### ⏳ Stage 4 — Linux entrant via GTK (2-3 j)

Connecter les signaux `drag-motion`, `drag-leave`, `drag-drop`,
`drag-data-received` côté Rust (`src/main/native/src/lib.rs`) sur le
widget GTK de la fenêtre Tao. Forwarder vers Kotlin via le pattern JNI
existant (`TaoApplication$EventDispatcher` ou un nouveau callback).

`gtk_drag_dest_set(widget, GTK_DEST_DEFAULT_MOTION | GTK_DEST_DEFAULT_HIGHLIGHT, targets, n, GDK_ACTION_COPY|MOVE|LINK)` au boot ; `gtk_drag_dest_unset(window)` d'abord si Tao en a posé un.

Targets minimum : `text/uri-list`, `text/plain;charset=utf-8`.

GTK abstrait XDND (X11) et `wl_data_device` (Wayland) — un seul code
pour les deux protocoles.

### ✅ Stage 5 Windows — DnD sortant `DoDragDrop` (livré)

**Couche native** (~500 lignes ajoutées à `nucleus_tao_dnd.c`) :
- `IDataObject` (`NDO_*`) qui clone son HGLOBAL à chaque `GetData` (le
  destinataire prend possession via `ReleaseStgMedium`, on doit donc
  ré-allouer à chaque appel).
- `IEnumFORMATETC` (`NEF_*`) — itérateur sur le tableau de `FORMATETC` du
  data object.
- `IDropSource` (`NDS_*`) — `QueryContinueDrag` (drop sur release du
  bouton gauche, cancel sur Escape) + `GiveFeedback` retourne
  `DRAGDROP_S_USEDEFAULTCURSORS` (curseurs OS standard).
- Helpers `build_hdrop` (DROPFILES + double-null-terminated WCHAR list)
  et `build_unicode_text` (HGLOBAL WCHAR + null final).
- JNI export `nativeStartDrag(hwnd, files, text, allowedEffects)` :
  matérialise les payloads, alloue les COM objects, appelle `DoDragDrop`
  (bloquant — pompe sa propre boucle modale), retourne le `DROPEFFECT_*`
  négocié.

**Couche Kotlin** :
- `NativeTaoWindowsDndBridge.nativeStartDrag` + constantes `DROP_EFFECT_*`
  (Copy=1, Move=2, Link=4, None=0).
- `TaoDragAndDropManager.OutboundLauncher` interface fonctionnelle +
  `OutboundRequest(files, text, supportedActions, decorationSize, drawDragDecoration)`
  — découple la logique commune (extraction `Transferable` →
  files+text, mapping bitmask actions, callback `onTransferCompleted`)
  du branchement OS-spécifique.
- `TaoComposeSceneHostWindows.launchWindowsOutboundDrag` : mappe
  `OutboundRequest` vers `NativeTaoWindowsDndBridge.nativeStartDrag`.

**Friend-access AWT (la solution propre)** :
- `src/main/java/androidx/compose/ui/draganddrop/TaoTransferableAccess.java`
  — fichier **Java** qui cast `DragAndDropTransferable` →
  `AwtDragAndDropTransferable` et appelle `toAwtTransferable()`. Java
  n'honore pas le `internal` Kotlin, donc même-package suffit.
- **Critique pour native-image** : pas de réflexion. `instanceof` +
  appel d'interface = bytecode statique, zéro métadata. `kotlin("jvm")`
  pull `src/main/java` automatiquement, aucune config Gradle requise.

> ⚠️ Pièges rencontrés :
> - Première version utilisait la réflexion (`javaClass.methods.firstOrNull
>   { it.name == "toAwtTransferable" }`) — fonctionne en JVM, casse
>   silencieusement en native-image (l'anonyme retourné par
>   `DragAndDropTransferable(awt: Transferable)` n'a pas de métadata).
>   Le Java helper résout ça à la racine.
> - `OleInitialize` doit être appelée avant `DoDragDrop` aussi (pas
>   seulement `RegisterDragDrop`). Reference-counted : safe d'appeler
>   plusieurs fois.

### ⏳ Stage 5 macOS / Linux — DnD sortant (à porter)

Côté Kotlin, **tout est prêt** : `OutboundLauncher` est OS-agnostique,
`TaoTransferableAccess` aussi, l'interface `OutboundRequest` carry
`files: List<File> + text: String?`. Reste à câbler une lambda
`launchMacOsOutboundDrag` / `launchLinuxOutboundDrag` qui appelle un
nouveau JNI bridge.

- macOS : `[NSView beginDraggingSessionWithItems:event:source:]` dans
  `objc/dnd.m` (ajouté avec Stage 2). Une classe ObjC conforme à
  `<NSDraggingSource>`. Image rendue depuis `drawDragDecoration` →
  `NSImage` → `NSDraggingItem`. NSDraggingItem par format (`fileURL`,
  `string`).
- Linux : `gtk_drag_begin_with_coordinates`. Signaux `drag-data-get` (se
  base sur les payloads pré-matérialisés) et `drag-end` (→ effet
  retourné). Drag image via `gtk_drag_set_icon_surface`.

### Étoffer Windows avant Stage 2/4 (optionnel, 1-2 j)

Ajouter au shim Windows existant la prise en charge de :
- `CF_UNICODETEXT` → `stringFlavor`
- `CF_HTML` → custom `text/html` `DataFlavor`
- Drop effect Move/Link selon `keyState`
- Coordonnées DPI-correctes (actuellement physiques, OK sur 100% scale)

Tout le travail Kotlin est déjà fait — il suffit d'enrichir
`TaoFilesTransferable` (renommer en `TaoMultiFlavorTransferable` ?) et
d'étendre `extract_files` côté C en `extract_payload`.

## Risques résiduels

- **Drift de l'API Compose `@InternalComposeUiApi`** : `PlatformContext`,
  `PlatformDragAndDropManager`, `ComposeSceneDragAndDropNode` sont tous
  `@InternalComposeUiApi`. Compose-MP peut casser ces signatures sur une
  bump majeure. Mitigation : isoler dans un nombre limité de fichiers
  (manager + glue par host), faire un test smoke à chaque upgrade
  Compose.
- **Callback JNI doit être une classe nommée** (pas `object : Callback {}`
  anonyme) sinon GraalVM JNI `GetMethodID` retourne null sur la classe
  synthétique. Pattern à respecter pour Stage 2 (macOS) et Stage 4
  (Linux).
- **Sandboxing macOS** : si l'app est sandboxée, les `NSURL` reçues sont
  security-scoped — il faut `[url startAccessingSecurityScopedResource]`
  / `stop…` pour la durée de vie du `File` exposé à l'utilisateur.
- **Wayland drag image** : compositeur-dépendant ; certaines distros
  ignorent l'image, on ne peut rien y faire.
- **Drops virtuels Windows** (7-Zip, clients mail) : utilisent
  `CFSTR_FILEDESCRIPTORW` + `CFSTR_FILECONTENTS` plutôt que CF_HDROP.
  Hors scope v1, à documenter.
- **Conflit avec le `IDropTarget` interne de Tao** : Tao 0.35 enregistre
  toujours son propre target sur Windows (et `gtk_drag_dest_set` sur
  Linux). Confirmé en pratique — solution : `Revoke`/`unset` avant notre
  registration. Effet collatéral : `WindowEvent::FileDropped` côté Rust
  ne sort plus, on prend tout en charge côté Kotlin.
- ~~**`AwtDragAndDropTransferable` est `internal`**~~ : résolu via un
  fichier **Java** dans le package `androidx.compose.ui.draganddrop`
  (`TaoTransferableAccess.java`). Java n'honore pas le `internal`
  Kotlin → cast `instanceof` direct, appel d'interface en bytecode
  statique. **Critique** : la réflexion comme alternative casse en
  native-image (l'anonyme du factory `DragAndDropTransferable(awt)` n'a
  pas de métadata), le Java helper évite ce piège entièrement.
- **AWT en native-image** : la couche transparence AWT charge `JPanel`,
  `DropTarget`, `DropTargetContext`, `DropTargetDragEvent`,
  `DropTargetDropEvent`, `DataFlavor`. Coût : ~12 entrées
  `reachability-metadata.json`, ~100 KB de classes JDK supplémentaires.
  Acceptable car le toolkit AWT est déjà initialisé pour le `JPanel`
  synthétique des events `KEY_TYPED` (existant pré-DnD).
