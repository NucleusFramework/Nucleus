@file:OptIn(
    ExperimentalTransitionApi::class,
    InternalAnimationApi::class,
)

package dev.nucleusframework.tray.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.InternalAnimationApi
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.tray.NativeTray
import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconRenderProperties
import dev.nucleusframework.tray.utils.MenuContentHash
import dev.nucleusframework.tray.utils.debugln
import dev.nucleusframework.tray.utils.isMenuBarInDarkMode
import dev.nucleusframework.window.tao.TaoStandalonePopup
import dev.nucleusframework.window.tao.isTaoStandalonePopupAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.util.concurrent.atomic.AtomicLong

// --------------------- Public API (defaults) ---------------------

// On Windows + macOS the popup renders in a per-pixel transparent native panel,
// so content animations composite over the desktop. On Linux the popup is
// still an opaque Tao window (no cross-WM transparency equivalent): the
// animation runs over the bare window background. See
// docs/PLAN_MACOS_LINUX_PANEL.md.
//
// Defaults match the historical per-platform behaviour: Windows tray popups
// slide up from the taskbar; macOS/Linux use a plain fade (menu-bar popups
// don't slide on those platforms; KDE gets a near-instant fade).
private val defaultTrayAppEnterTransition =
    if (Platform.Current == Platform.Windows) {
        slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(250, easing = EaseInOut),
        ) + fadeIn(animationSpec = tween(200, easing = EaseInOut))
    } else {
        fadeIn(
            animationSpec =
                tween(
                    if (LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE) 50 else 200,
                    easing = EaseInOut,
                ),
        )
    }

private val defaultTrayAppExitTransition =
    if (Platform.Current == Platform.Windows) {
        slideOutVertically(
            targetOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(250, easing = EaseInOut),
        ) + fadeOut(animationSpec = tween(200, easing = EaseInOut))
    } else {
        fadeOut(
            animationSpec =
                tween(
                    if (LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE) 50 else 200,
                    easing = EaseInOut,
                ),
        )
    }

private val defaultVerticalOffset =
    when (Platform.Current) {
        Platform.Windows -> -10
        Platform.MacOS -> 5
        else ->
            when (LinuxDesktopEnvironment.Current) {
                LinuxDesktopEnvironment.Gnome -> 10
                else -> 0
            }
    }

private const val GC_AFTER_HIDE_DELAY_MS = 1_500L

/**
 * Trims the heap after the popup leaves the screen. The popup keeps its
 * composition alive while hidden (so state survives hide/show), which means
 * garbage from the visible session otherwise lingers for as long as the app
 * idles in the tray — exactly the footprint users check in the task manager.
 * Right after the exit animation there is no UI on screen, so the collection
 * pause is invisible.
 *
 * Must be called from the visibility `LaunchedEffect`: reshowing the popup
 * restarts the effect and cancels the [delay], debouncing rapid toggles for
 * free. The collection runs off the UI thread so the tray loop only pays the
 * stop-the-world portion.
 */
private suspend fun requestGcWhileHidden() {
    delay(GC_AFTER_HIDE_DELAY_MS)
    withContext(Dispatchers.Default) {
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
    }
}

// --------------------- Public API (overloads) ---------------------

/**
 * Tray icon whose click toggles a popup window anchored to it, the icon drawn from an
 * [ImageVector]. See the composable-icon overload for the shared parameters.
 *
 * @param icon The icon. Without [tint] it follows the menu bar theme.
 * @param tint Fixed icon color, or `null` to follow the menu bar theme.
 */
@Composable
public fun NucleusApplicationScope.TrayApp(
    icon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    state: TrayAppState? = null,
    windowSize: DpSize? = null,
    visibleOnStart: Boolean = false,
    enterTransition: EnterTransition = defaultTrayAppEnterTransition,
    exitTransition: ExitTransition = defaultTrayAppExitTransition,
    windowsTitle: String = "",
    windowIcon: Painter? = null,
    undecorated: Boolean = true,
    resizable: Boolean = false,
    horizontalOffset: Int = 0,
    verticalOffset: Int = defaultVerticalOffset,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    menu: (TrayMenuBuilder.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val iconContent: @Composable () -> Unit = {
        val isDark = isMenuBarInDarkMode()
        Image(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter =
                tint?.let { ColorFilter.tint(it) }
                    ?: if (isDark) ColorFilter.tint(Color.White) else ColorFilter.tint(Color.Black),
        )
    }
    TrayApp(
        iconContent = iconContent,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        state = state,
        windowSize = windowSize,
        visibleOnStart = visibleOnStart,
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        windowsTitle = windowsTitle,
        windowIcon = windowIcon,
        undecorated = undecorated,
        resizable = resizable,
        horizontalOffset = horizontalOffset,
        verticalOffset = verticalOffset,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        menu = menu,
        content = content,
    )
}

/**
 * Tray icon whose click toggles a popup window anchored to it, the icon drawn from a [Painter].
 * See the composable-icon overload for the shared parameters.
 *
 * @param icon The icon.
 */
@Composable
public fun NucleusApplicationScope.TrayApp(
    icon: Painter,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    state: TrayAppState? = null,
    windowSize: DpSize? = null,
    visibleOnStart: Boolean = false,
    enterTransition: EnterTransition = defaultTrayAppEnterTransition,
    exitTransition: ExitTransition = defaultTrayAppExitTransition,
    windowsTitle: String = "",
    windowIcon: Painter? = null,
    undecorated: Boolean = true,
    resizable: Boolean = false,
    horizontalOffset: Int = 0,
    verticalOffset: Int = defaultVerticalOffset,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    menu: (TrayMenuBuilder.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val iconContent: @Composable () -> Unit = {
        Image(painter = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
    TrayApp(
        iconContent = iconContent,
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        state = state,
        windowSize = windowSize,
        visibleOnStart = visibleOnStart,
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        windowsTitle = windowsTitle,
        windowIcon = windowIcon,
        undecorated = undecorated,
        resizable = resizable,
        horizontalOffset = horizontalOffset,
        verticalOffset = verticalOffset,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        menu = menu,
        content = content,
    )
}

/** Painter on Windows, ImageVector on macOS/Linux */
@Composable
public fun NucleusApplicationScope.TrayApp(
    windowsIcon: Painter,
    macLinuxIcon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    state: TrayAppState? = null,
    windowSize: DpSize? = null,
    visibleOnStart: Boolean = false,
    enterTransition: EnterTransition = defaultTrayAppEnterTransition,
    exitTransition: ExitTransition = defaultTrayAppExitTransition,
    windowsTitle: String = "",
    windowIcon: Painter? = null,
    undecorated: Boolean = true,
    resizable: Boolean = false,
    horizontalOffset: Int = 0,
    verticalOffset: Int = defaultVerticalOffset,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    menu: (TrayMenuBuilder.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (Platform.Current == Platform.Windows) {
        TrayApp(
            icon = windowsIcon,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            state = state,
            windowSize = windowSize,
            visibleOnStart = visibleOnStart,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            windowsTitle = windowsTitle,
            windowIcon = windowIcon,
            undecorated = undecorated,
            resizable = resizable,
            horizontalOffset = horizontalOffset,
            verticalOffset = verticalOffset,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            menu = menu,
            content = content,
        )
    } else {
        TrayApp(
            icon = macLinuxIcon,
            tint = tint,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            state = state,
            windowSize = windowSize,
            visibleOnStart = visibleOnStart,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            windowsTitle = windowsTitle,
            windowIcon = windowIcon,
            undecorated = undecorated,
            resizable = resizable,
            horizontalOffset = horizontalOffset,
            verticalOffset = verticalOffset,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            menu = menu,
            content = content,
        )
    }
}

/**
 * Tray icon whose click toggles a popup window anchored to it, the icon from a Compose resource.
 * See the composable-icon overload for the shared parameters.
 *
 * @param icon The icon resource.
 */
@Composable
public fun NucleusApplicationScope.TrayApp(
    icon: DrawableResource,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    state: TrayAppState? = null,
    windowSize: DpSize? = null,
    visibleOnStart: Boolean = false,
    enterTransition: EnterTransition = defaultTrayAppEnterTransition,
    exitTransition: ExitTransition = defaultTrayAppExitTransition,
    windowsTitle: String = "",
    windowIcon: Painter? = null,
    undecorated: Boolean = true,
    resizable: Boolean = false,
    horizontalOffset: Int = 0,
    verticalOffset: Int = defaultVerticalOffset,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    menu: (TrayMenuBuilder.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    TrayApp(
        icon = painterResource(icon),
        iconRenderProperties = iconRenderProperties,
        tooltip = tooltip,
        state = state,
        windowSize = windowSize,
        visibleOnStart = visibleOnStart,
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        windowsTitle = windowsTitle,
        windowIcon = windowIcon,
        undecorated = undecorated,
        resizable = resizable,
        horizontalOffset = horizontalOffset,
        verticalOffset = verticalOffset,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        menu = menu,
        content = content,
    )
}

/**
 * Tray icon whose click toggles a popup window anchored to it: a resource icon on Windows, an
 * [ImageVector] on macOS and Linux. See the composable-icon overload for the shared parameters.
 *
 * @param windowsIcon Icon used on Windows.
 * @param macLinuxIcon Icon used on macOS and Linux.
 * @param tint Fixed color of [macLinuxIcon], or `null` to follow the menu bar theme.
 */
@Composable
public fun NucleusApplicationScope.TrayApp(
    windowsIcon: DrawableResource,
    macLinuxIcon: ImageVector,
    tint: Color? = null,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    state: TrayAppState? = null,
    windowSize: DpSize? = null,
    visibleOnStart: Boolean = false,
    enterTransition: EnterTransition = defaultTrayAppEnterTransition,
    exitTransition: ExitTransition = defaultTrayAppExitTransition,
    windowsTitle: String = "",
    windowIcon: Painter? = null,
    undecorated: Boolean = true,
    resizable: Boolean = false,
    horizontalOffset: Int = 0,
    verticalOffset: Int = defaultVerticalOffset,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    menu: (TrayMenuBuilder.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (Platform.Current == Platform.Windows) {
        TrayApp(
            icon = painterResource(windowsIcon),
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            state = state,
            windowSize = windowSize,
            visibleOnStart = visibleOnStart,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            windowsTitle = windowsTitle,
            windowIcon = windowIcon,
            undecorated = undecorated,
            resizable = resizable,
            horizontalOffset = horizontalOffset,
            verticalOffset = verticalOffset,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            menu = menu,
            content = content,
        )
    } else {
        TrayApp(
            icon = macLinuxIcon,
            tint = tint,
            iconRenderProperties = iconRenderProperties,
            tooltip = tooltip,
            state = state,
            windowSize = windowSize,
            visibleOnStart = visibleOnStart,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            windowsTitle = windowsTitle,
            windowIcon = windowIcon,
            undecorated = undecorated,
            resizable = resizable,
            horizontalOffset = horizontalOffset,
            verticalOffset = verticalOffset,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            menu = menu,
            content = content,
        )
    }
}

// --------------------- Core implementation (Tao backend only) ---------------------

/**
 * Tray icon + anchored popup. Runs exclusively on the Nucleus Tao backend,
 * which the `Auto` backend selects automatically once the
 * `decorated-window-tao` module is on the classpath.
 *
 * On Windows + macOS the popup body is hosted in a standalone per-pixel
 * transparent, non-activating native panel — no backing window exists anywhere
 * (nothing in the taskbar/Dock, Alt-Tab or the Start task view). On Linux the
 * popup is a regular undecorated Tao window (opaque): no cross-WM transparency
 * equivalent exists.
 */
@Composable
public fun NucleusApplicationScope.TrayApp(
    iconContent: @Composable () -> Unit,
    iconRenderProperties: IconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
    tooltip: String,
    state: TrayAppState? = null,
    windowSize: DpSize? = null,
    visibleOnStart: Boolean = false,
    enterTransition: EnterTransition = defaultTrayAppEnterTransition,
    exitTransition: ExitTransition = defaultTrayAppExitTransition,
    windowsTitle: String = "",
    windowIcon: Painter? = null,
    undecorated: Boolean = true,
    resizable: Boolean = false,
    horizontalOffset: Int = 0,
    verticalOffset: Int = defaultVerticalOffset,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    menu: (TrayMenuBuilder.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Linux gates on runtime availability: the panel is a raw X11 window,
    // reachable through XWayland on Wayland sessions but absent on rare
    // Wayland-only setups — those fall back to the opaque window impl.
    val panelAvailable =
        remember {
            Platform.Current == Platform.Windows ||
                Platform.Current == Platform.MacOS ||
                (Platform.Current == Platform.Linux && isTaoStandalonePopupAvailable())
        }
    if (panelAvailable) {
        TrayAppImplPanel(
            iconContent,
            iconRenderProperties,
            tooltip,
            state,
            windowSize,
            visibleOnStart,
            enterTransition,
            exitTransition,
            horizontalOffset,
            verticalOffset,
            onPreviewKeyEvent,
            onKeyEvent,
            menu,
            content,
        )
    } else {
        TrayAppImplWindow(
            iconContent,
            iconRenderProperties,
            tooltip,
            state,
            windowSize,
            visibleOnStart,
            enterTransition,
            exitTransition,
            windowsTitle,
            windowIcon,
            undecorated,
            resizable,
            horizontalOffset,
            verticalOffset,
            onPreviewKeyEvent,
            onKeyEvent,
            menu,
            content,
        )
    }
}

// --------------------- Impl: standalone transparent panel (Windows + macOS) ---------------------

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun TrayAppImplPanel(
    iconContent: @Composable () -> Unit,
    iconRenderProperties: IconRenderProperties,
    tooltip: String,
    state: TrayAppState?,
    windowSize: DpSize?,
    visibleOnStart: Boolean,
    enterTransition: EnterTransition,
    exitTransition: ExitTransition,
    horizontalOffset: Int,
    verticalOffset: Int,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    menu: (TrayMenuBuilder.() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val toggleDebounceMs = 150L
    val minVisibleDurationMs = 200L
    val minHiddenDurationMs = 100L

    val trayAppState =
        state ?: rememberTrayAppState(
            initialWindowSize = windowSize ?: DpSize(300.dp, 200.dp),
            initiallyVisible = visibleOnStart,
            initialDismissMode = TrayWindowDismissMode.AUTO,
        )
    val isVisible by trayAppState.isVisible.collectAsState()
    val currentWindowSize by trayAppState.windowSize.collectAsState()
    val dismissMode by trayAppState.dismissMode.collectAsState()

    val tray = remember { NativeTray() }
    val instanceKey = remember { tray.instanceKey() }

    val isDark = isMenuBarInDarkMode()
    val contentHash = ComposableIconUtils.calculateContentHash(iconRenderProperties, iconContent) + isDark.hashCode()
    val pngIconPath =
        remember(contentHash) {
            ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)
        }
    val windowsIconPath =
        remember(contentHash) {
            ComposableIconUtils.renderComposableToIcoFile(iconRenderProperties, iconContent)
        }
    val menuHash = MenuContentHash.calculateMenuHash(menu)

    // The panel is a standalone native surface; its composition (and the
    // user's content state) survives hide/show.
    var popupOnScreen by remember { mutableStateOf(false) }
    var popupScreenPos by remember { mutableStateOf(WindowPosition.Absolute(0.dp, 0.dp)) }

    // Position pre-computed at click time so the LaunchedEffect can use it immediately.
    var pendingPosition by remember { mutableStateOf<WindowPosition?>(null) }

    // Visibility controller for exit-finish observation; content will NOT be disposed.
    val visibleState = remember { MutableTransitionState(false) }

    // Thread-safe timestamps: the tray primary action arrives on a native callback thread.
    val lastDismissAtMs = remember { AtomicLong(0L) }
    val lastHiddenAtMs = remember { AtomicLong(0L) }
    val lastShownAtMs = remember { AtomicLong(0L) }
    val lastPrimaryActionAtMs = remember { AtomicLong(0L) }

    val requestHideExplicit: () -> Unit = {
        val now = System.currentTimeMillis()
        val sinceShow = now - lastShownAtMs.get()
        debugln { "[TrayApp] requestHideExplicit called, sinceShow=${sinceShow}ms" }
        if (sinceShow >= minVisibleDurationMs) {
            trayAppState.hide()
            lastHiddenAtMs.set(System.currentTimeMillis())
        } else {
            val wait = minVisibleDurationMs - sinceShow
            CoroutineScope(Dispatchers.IO).launch {
                delay(wait)
                trayAppState.hide()
                lastHiddenAtMs.set(System.currentTimeMillis())
            }
        }
    }

    val internalPrimaryAction: () -> Unit = action@{
        val now = System.currentTimeMillis()
        val isVisibleNow = trayAppState.isVisible.value
        val timeSinceLastAction = now - lastPrimaryActionAtMs.get()
        debugln {
            "[TrayApp] primaryAction: isVisibleNow=$isVisibleNow, " +
                "timeSinceLastAction=${timeSinceLastAction}ms"
        }
        if (timeSinceLastAction < toggleDebounceMs) {
            debugln { "[TrayApp] primaryAction -> DEBOUNCED" }
            return@action
        }
        lastPrimaryActionAtMs.set(now)
        if (isVisibleNow) {
            debugln { "[TrayApp] primaryAction -> HIDE" }
            requestHideExplicit()
        } else {
            val hiddenAgo = now - lastHiddenAtMs.get()
            val dismissedAgo = now - lastDismissAtMs.get()
            if (hiddenAgo < minHiddenDurationMs) {
                debugln { "[TrayApp] primaryAction -> SHOW BLOCKED (too soon after hide)" }
                return@action
            }
            // A click on the tray icon while the popup is visible first lands in
            // the outside-click monitor (which dismisses), then reaches this
            // callback — without this guard the popup would instantly re-open.
            if (dismissedAgo < 300) {
                debugln { "[TrayApp] primaryAction -> SHOW BLOCKED (recent outside-click dismiss)" }
                return@action
            }
            debugln { "[TrayApp] primaryAction -> SHOW" }
            runCatching {
                val widthPx = currentWindowSize.width.value.toInt()
                val heightPx = currentWindowSize.height.value.toInt()
                pendingPosition =
                    getTrayWindowPositionForInstance(
                        instanceKey,
                        widthPx,
                        heightPx,
                        horizontalOffset,
                        verticalOffset,
                    )
            }
            // Stamp before show(): a leftover Finder mouseDown on the desktop
            // (same gesture that opened the tray) can land in onOutsideClick
            // before the panel is on screen. lastShownAtMs starts at 0, which
            // would make minVisibleDurationMs a no-op and hide immediately.
            lastShownAtMs.set(now)
            trayAppState.show()
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            lastShownAtMs.set(System.currentTimeMillis())
            if (!popupOnScreen) {
                val preComputed = pendingPosition
                pendingPosition = null
                val workArea = TrayScreenGeometry.workAreaLogical()
                val usable = { pos: WindowPosition -> isUsableAnchorPosition(pos, workArea) }

                val position =
                    if (preComputed != null && usable(preComputed)) {
                        debugln { "[TrayApp] Using preComputed position: $preComputed" }
                        preComputed
                    } else {
                        // visibleOnStart / programmatic show: the tray icon may not be laid
                        // out yet. On macOS NSStatusItem reports a left-edge rect until the
                        // status bar assigns a slot — latching that puts the popup at (0,0).
                        debugln { "[TrayApp] No preComputed position, waiting for tray to stabilize..." }
                        val widthPx = currentWindowSize.width.value.toInt()
                        val heightPx = currentWindowSize.height.value.toInt()

                        if (Platform.Current == Platform.Windows) {
                            // Windows moves tray icons around after creation, so wait for the
                            // shell to settle, refresh the cached icon rect, then re-poll.
                            delay(400)
                            debugln { "[TrayApp] Re-capturing tray position from native API..." }
                            WindowsTrayInitializer.refreshPosition(instanceKey)
                            delay(50)
                        }
                        var pos =
                            awaitAnchoredWindowPosition(isUsable = usable) {
                                val next =
                                    getTrayWindowPositionForInstance(
                                        instanceKey,
                                        widthPx,
                                        heightPx,
                                        horizontalOffset,
                                        verticalOffset,
                                    )
                                debugln { "[TrayApp] Polled position: $next" }
                                next
                            }
                        if (!usable(pos)) {
                            // Tray never became ready within the deadline — fall back
                            // to the corner heuristic rather than showing at (0,0).
                            pos = getTrayWindowPosition(widthPx, heightPx, horizontalOffset, verticalOffset)
                            debugln { "[TrayApp] Poll deadline expired, corner fallback: $pos" }
                        }
                        pos
                    }
                if (position is WindowPosition.Absolute) {
                    popupScreenPos = position
                }
                popupOnScreen = true
                // Let the panel render a first frame at the new position
                // before the enter animation starts.
                delay(30)
                visibleState.targetState = true
                debugln { "[TrayApp] Popup now on screen at $position" }
                lastShownAtMs.set(System.currentTimeMillis())
            }
        } else {
            if (popupOnScreen) {
                // Play the exit animation, then hide the panel.
                visibleState.targetState = false
                snapshotFlow { visibleState.isIdle && !visibleState.currentState }.first { it }
                popupOnScreen = false
                lastHiddenAtMs.set(System.currentTimeMillis())
                requestGcWhileHidden()
            }
        }
    }

    // Re-anchor while visible when the popup size or offsets change.
    LaunchedEffect(currentWindowSize, horizontalOffset, verticalOffset) {
        if (popupOnScreen) {
            val w = currentWindowSize.width.value.toInt()
            val h = currentWindowSize.height.value.toInt()
            val pos = getTrayWindowPositionForInstance(instanceKey, w, h, horizontalOffset, verticalOffset)
            if (pos is WindowPosition.Absolute && isUsableAnchorPosition(pos)) popupScreenPos = pos
        }
    }

    LaunchedEffect(pngIconPath, windowsIconPath, tooltip, internalPrimaryAction, menu, contentHash, menuHash) {
        tray.update(pngIconPath, windowsIconPath, tooltip, internalPrimaryAction, menu)
    }

    DisposableEffect(Unit) { onDispose { tray.dispose() } }

    TaoStandalonePopup(
        visible = popupOnScreen,
        position = popupScreenPos,
        size = currentWindowSize,
        focusable = true,
        onOutsideClick = {
            // Process-wide monitor: ignore while hidden, and drop leftover
            // Finder desktop clicks from the same gesture that opened us.
            // Delaying those hides (minVisibleDurationMs) would flash-close.
            if (trayAppState.isVisible.value) {
                val sinceShow = System.currentTimeMillis() - lastShownAtMs.get()
                if (sinceShow < minVisibleDurationMs) {
                    debugln { "[TrayApp] outside click ignored (${sinceShow}ms after show)" }
                } else {
                    lastDismissAtMs.set(System.currentTimeMillis())
                    debugln { "[TrayApp] outside click dismiss, dismissMode=$dismissMode" }
                    if (dismissMode == TrayWindowDismissMode.AUTO) requestHideExplicit()
                }
            }
        },
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
    ) {
        Box(Modifier.fillMaxSize()) {
            // ---- Persistent (non-disposing) visibility wrapper ----
            PersistentAnimatedVisibility(
                visibleState = visibleState,
                enter = enterTransition,
                exit = exitTransition,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer()
                            // Child can still opt into its own per-node enter/exit if desired:
                            .animateEnterExit(),
                ) { content() }
            }
        }
    }
}

// --------------------- Impl: opaque window (Linux) ---------------------

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun NucleusApplicationScope.TrayAppImplWindow(
    iconContent: @Composable () -> Unit,
    iconRenderProperties: IconRenderProperties,
    tooltip: String,
    state: TrayAppState?,
    windowSize: DpSize?,
    visibleOnStart: Boolean,
    enterTransition: EnterTransition,
    exitTransition: ExitTransition,
    windowsTitle: String,
    windowIcon: Painter?,
    undecorated: Boolean,
    resizable: Boolean,
    horizontalOffset: Int,
    verticalOffset: Int,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    onKeyEvent: (KeyEvent) -> Boolean,
    menu: (TrayMenuBuilder.() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val toggleDebounceMs = 280L
    val minVisibleDurationMs = 350L
    val minHiddenDurationMs = 250L

    val trayAppState =
        state ?: rememberTrayAppState(
            initialWindowSize = windowSize ?: DpSize(300.dp, 200.dp),
            initiallyVisible = visibleOnStart,
            initialDismissMode = TrayWindowDismissMode.AUTO,
        )
    val isVisible by trayAppState.isVisible.collectAsState()
    val currentWindowSize by trayAppState.windowSize.collectAsState()
    val dismissMode by trayAppState.dismissMode.collectAsState()

    val tray = remember { NativeTray() }
    val instanceKey = remember { tray.instanceKey() }

    val isDark = isMenuBarInDarkMode()
    val contentHash = ComposableIconUtils.calculateContentHash(iconRenderProperties, iconContent) + isDark.hashCode()
    val pngIconPath =
        remember(contentHash) {
            ComposableIconUtils.renderComposableToPngFile(iconRenderProperties, iconContent)
        }
    val windowsIconPath = pngIconPath
    val menuHash = MenuContentHash.calculateMenuHash(menu)

    var shouldShowWindow by remember { mutableStateOf(false) }

    // Position pre-computed at click time so the LaunchedEffect can use it immediately.
    var pendingPosition by remember { mutableStateOf<WindowPosition?>(null) }

    val windowState =
        rememberWindowState(
            size = currentWindowSize,
            position =
                getTrayWindowPositionForInstance(
                    instanceKey,
                    currentWindowSize.width.value.toInt(),
                    currentWindowSize.height.value.toInt(),
                    horizontalOffset,
                    verticalOffset,
                ),
        )
    LaunchedEffect(currentWindowSize) { windowState.size = currentWindowSize }

    // Visibility controller for exit-finish observation; content will NOT be disposed.
    val visibleState = remember { MutableTransitionState(false) }

    val lastHiddenAtMs = remember { AtomicLong(0L) }
    val lastShownAtMs = remember { AtomicLong(0L) }
    val lastPrimaryActionAtMs = remember { AtomicLong(0L) }

    val requestHideExplicit: () -> Unit = {
        val now = System.currentTimeMillis()
        val sinceShow = now - lastShownAtMs.get()
        if (sinceShow >= minVisibleDurationMs) {
            trayAppState.hide()
            lastHiddenAtMs.set(System.currentTimeMillis())
        } else {
            val wait = minVisibleDurationMs - sinceShow
            CoroutineScope(Dispatchers.IO).launch {
                delay(wait)
                trayAppState.hide()
                lastHiddenAtMs.set(System.currentTimeMillis())
            }
        }
    }

    val internalPrimaryAction: () -> Unit = action@{
        val now = System.currentTimeMillis()
        if (now - lastPrimaryActionAtMs.get() < toggleDebounceMs) return@action
        lastPrimaryActionAtMs.set(now)
        if (trayAppState.isVisible.value) {
            requestHideExplicit()
        } else if (now - lastHiddenAtMs.get() >= minHiddenDurationMs) {
            runCatching {
                val w = currentWindowSize.width.value.toInt()
                val h = currentWindowSize.height.value.toInt()
                pendingPosition =
                    getTrayWindowPositionForInstance(instanceKey, w, h, horizontalOffset, verticalOffset)
            }
            trayAppState.show()
        }
    }

    LaunchedEffect(isVisible) {
        visibleState.targetState = isVisible
        if (isVisible) {
            if (!shouldShowWindow) {
                val preComputed = pendingPosition
                pendingPosition = null
                val widthPx = currentWindowSize.width.value.toInt()
                val heightPx = currentWindowSize.height.value.toInt()
                val workArea = TrayScreenGeometry.workAreaLogical()
                val usable = { pos: WindowPosition -> isUsableAnchorPosition(pos, workArea) }
                val position =
                    if (preComputed != null && usable(preComputed)) {
                        preComputed
                    } else {
                        var pos =
                            awaitAnchoredWindowPosition(isUsable = usable) {
                                getTrayWindowPositionForInstance(
                                    instanceKey,
                                    widthPx,
                                    heightPx,
                                    horizontalOffset,
                                    verticalOffset,
                                )
                            }
                        if (!usable(pos)) {
                            pos = getTrayWindowPosition(widthPx, heightPx, horizontalOffset, verticalOffset)
                        }
                        pos
                    }
                windowState.position = position
                delay(30)
                shouldShowWindow = true
                lastShownAtMs.set(System.currentTimeMillis())
            }
        } else {
            if (shouldShowWindow) {
                snapshotFlow { visibleState.isIdle && !visibleState.currentState }.first { it }
                shouldShowWindow = false
                lastHiddenAtMs.set(System.currentTimeMillis())
                requestGcWhileHidden()
            }
        }
    }

    // Re-anchor when visible and size/offset changes
    LaunchedEffect(currentWindowSize, horizontalOffset, verticalOffset, shouldShowWindow) {
        if (shouldShowWindow) {
            val w = currentWindowSize.width.value.toInt()
            val h = currentWindowSize.height.value.toInt()
            windowState.position =
                getTrayWindowPositionForInstance(instanceKey, w, h, horizontalOffset, verticalOffset)
        }
    }

    LaunchedEffect(pngIconPath, windowsIconPath, tooltip, internalPrimaryAction, menu, contentHash, menuHash) {
        tray.update(pngIconPath, windowsIconPath, tooltip, internalPrimaryAction, menu)
    }

    DisposableEffect(Unit) { onDispose { tray.dispose() } }

    DecoratedWindow(
        onCloseRequest = { requestHideExplicit() },
        state = windowState,
        visible = shouldShowWindow,
        title = windowsTitle,
        icon = windowIcon,
        resizable = resizable,
        focusable = true,
        alwaysOnTop = true,
        undecorated = undecorated,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
    ) {
        val window = nucleusWindow

        // Give the position code access to the popup's TaoWindow (needed by
        // the GDK-backed work-area queries on Linux).
        DisposableEffect(window) {
            TrayScreenGeometry.taoWindowProvider = { window.unsafe.taoWindow }
            onDispose { TrayScreenGeometry.taoWindowProvider = null }
        }

        // On show: raise + focus so focus-loss dismissal works.
        LaunchedEffect(shouldShowWindow) {
            if (!shouldShowWindow) return@LaunchedEffect
            runCatching {
                window.toFront()
                window.requestFocus()
            }
        }

        // Auto-hide on focus loss (edge-triggered: only after focus was gained).
        LaunchedEffect(Unit) {
            var hadFocus = false
            window.focusFlow.collect { focused ->
                if (focused) {
                    hadFocus = true
                    return@collect
                }
                if (!hadFocus) return@collect
                hadFocus = false
                if (!shouldShowWindow) return@collect
                if (dismissMode == TrayWindowDismissMode.AUTO) requestHideExplicit()
            }
        }

        // ---- Persistent (non-disposing) visibility wrapper ----
        PersistentAnimatedVisibility(
            visibleState = visibleState,
            enter = enterTransition,
            exit = exitTransition,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer()
                        .animateEnterExit(),
            ) { content() }
        }
    }
}
