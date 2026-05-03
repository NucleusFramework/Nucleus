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

## Verdict de faisabilité

| Variante | macOS | Windows | Linux X11 | Linux Wayland |
|---|---|---|---|---|
| **DnD intra-scène** (source ↔ target dans la même fenêtre) | ✅ | ✅ | ✅ | ✅ |
| **DnD entrant — fichiers depuis l'OS** | ⚠️ shim ObjC requis (Tao n'émet pas) | 🟡 fast-path Tao possible (`with_file_drop_handler`) mais limité ; pour la position/format → `IDropTarget` | 🟡 fast-path Tao via GTK ; pour position/formats riches → `gtk_drag_dest_set` direct | 🟡 idem (GTK abstrait XDND/wl_data_device) |
| **DnD entrant — texte/HTML/URI** | ⚠️ shim ObjC | ❌ Tao ne le couvre pas → `IDropTarget` obligatoire | ❌ → `gtk_drag_dest_set` | ❌ → `gtk_drag_dest_set` |
| **DnD sortant** (`Modifier.dragAndDropSource` → OS) | ❌ shim ObjC (`beginDraggingSessionWithItems:`) | ❌ shim Win32/COM (`DoDragDrop` + `IDropSource` + `IDataObject`) | ❌ shim GTK (`gtk_drag_begin_with_coordinates`) | ❌ idem |

**Aucun blocage fondamental** sur les trois OS — seulement de la plomberie
native à écrire.

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

## Plan d'exécution

### Stage 0 — squelette `TaoDragAndDropManager` (½ journée)

1. Nouveau fichier
   `decorated-window-tao/src/main/kotlin/io/github/kdroidfilter/nucleus/window/tao/TaoDragAndDropManager.kt`.
2. Implémentation initiale "log only" :
   ```kotlin
   internal class TaoDragAndDropManager(
       private val getRootNode: () -> ComposeSceneDragAndDropNode,
   ) : PlatformDragAndDropManager {
       override val isRequestDragAndDropTransferRequired = true
       override fun requestDragAndDropTransfer(source: PlatformDragAndDropSource, offset: Offset) {
           println("requestDragAndDropTransfer offset=$offset")
       }
   }
   ```
3. Câblage dans `TaoPlatformContext` (champ `dragAndDropManager`).
4. Câblage dans `TaoComposeSceneHost.attach()` : passer un
   `getRootNode = { scene!!.rootDragAndDropNode }` au manager.
5. Sample : ajouter un `Modifier.dragAndDropSource` + un
   `Modifier.dragAndDropTarget` dans `jewel-sample` ou `sample-cmp`.
6. **Critère de validation** : pression-glissement sur la source produit
   le log `requestDragAndDropTransfer`. Drop intra-scène (sans appel OS)
   marche dès cette étape grâce au routage interne du `DragAndDropNode`.

### Stage 1 — DnD intra-scène complet (½ jour)

Vérifier que les modifiers Compose `dragAndDropSource` ↔
`dragAndDropTarget` fonctionnent end-to-end **sans** code natif (le
manager peut court-circuiter et ne jamais appeler l'OS quand
`hasEligibleDropTarget` est `true` à la position du drop).

C'est le palier livrable d'une v0.1 si on n'a pas le temps d'attaquer le
natif.

### Stage 2 — macOS entrant (3-4 j)

`objc/dnd.m` + JNI bridge `TaoDragAndDropBridge.kt` (statics
`onDragEntered/Updated/Drop/Exited`). Test : drag d'un fichier depuis
Finder, lecture de `dragData().readFiles()` côté Compose.

### Stage 3 — Windows entrant (4-6 j)

`windows/nucleus_tao_dnd.c` + COM object `IDropTarget`. `OleInitialize` à
gérer dans le bootstrap WndProc subclass. Test équivalent : drag depuis
Explorer.

### Stage 4 — Linux entrant (2-3 j)

GTK signals connectés depuis Rust (le moins coûteux car le binding
existe déjà). Test : drag depuis Nautilus.

### Stage 5 — Sortant tous OS (1 semaine)

Plus simple que l'entrant : un seul flow par OS, l'OS est consommateur.
Démarrer par macOS qui a la plus jolie API (`NSDraggingItem`).

## Risques résiduels

- **Drift de l'API Compose `@InternalComposeUiApi`** : `PlatformContext`,
  `PlatformDragAndDropManager`, `ComposeSceneDragAndDropNode` sont tous
  `@InternalComposeUiApi`. Compose-MP peut casser ces signatures sur une
  bump majeure. Mitigation : isoler dans un fichier unique, faire un test
  smoke à chaque upgrade Compose.
- **Sandboxing macOS** : si l'app est sandboxée, les `NSURL` reçues sont
  security-scoped — il faut `[url startAccessingSecurityScopedResource]`
  / `stop…` pour la durée de vie du `File` exposé à l'utilisateur.
- **Wayland drag image** : compositeur-dépendant ; certaines distros
  ignorent l'image, on ne peut rien y faire.
- **Drops virtuels Windows** (7-Zip, clients mail) : utilisent
  `CFSTR_FILEDESCRIPTORW` + `CFSTR_FILECONTENTS` plutôt que CF_HDROP.
  Hors scope v1, à documenter.
- **Conflit `with_file_drop_handler` Tao** : sur Windows et Linux, ne
  surtout pas l'activer en parallèle de notre registration (un seul
  `IDropTarget`/`gtk_drag_dest_set` actif à la fois).
- **`AwtDragAndDropTransferable` est `internal`** : pour récupérer le
  `java.awt.datatransfer.Transferable` depuis un
  `DragAndDropTransferData`, il faut soit du `same-package access` (en
  déclarant dans `androidx.compose.ui.draganddrop`), soit de la
  réflexion. Le manager AWT du framework triche déjà via cast direct car
  il vit dans le bon package. Notre code peut faire pareil :
  ```kotlin
  package androidx.compose.ui.draganddrop
   internal fun DragAndDropTransferable.toAwt(): Transferable? =
       (this as? AwtDragAndDropTransferable)?.toAwtTransferable()
  ```
  petit fichier "friend" dans le module Tao qui squatte le package — ou
  utiliser la réflexion si on préfère ne pas mélanger les packages.
