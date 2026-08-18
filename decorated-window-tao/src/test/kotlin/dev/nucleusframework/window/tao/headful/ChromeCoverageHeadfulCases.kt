package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.BasicTitleBar
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.DecoratedDialogState
import dev.nucleusframework.window.DialogTitleBar
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowControlType
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowControlsRenderer
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.newFullscreenControls
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Headful coverage of the public chrome composables. Assertions stay on
 * window geometry and composition side-effects — no AWT Robot.
 */
internal object ChromeCoverageHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            titleBarMapsAndSurvivesMaximize(),
            basicTitleBarFillCenterStaysMapped(),
            windowScaffoldDockedPublishesBarHeight(),
            windowScaffoldOverlayPadsContent(),
            windowControlsRendererSeesPlatformSlots(),
            windowBackgroundAndAppearanceStayMapped(),
            dialogTitleBarMapsWithParent(),
        )

    private fun titleBarMapsAndSurvivesMaximize(): TaoWindowTestCase {
        val composed = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "TitleBar composes and survives maximize/restore",
            content = {
                TitleBar(Modifier.newFullscreenControls()) { state ->
                    SideEffect { composed.set(true) }
                    Box(
                        Modifier
                            .height(12.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally)
                            .background(if (state.isMaximized) Color.Yellow else Color.Cyan),
                    )
                }
                Box(Modifier.fillMaxSize().background(Color(0xFF203040)))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("TitleBar composed") { composed.get() }
                settle()
                val before = requireNotNull(bounds())
                window.setMaximized(true)
                awaitUntil("maximized bounds grew") {
                    val b = bounds() ?: return@awaitUntil false
                    b[2] > before[2] || b[3] > before[3]
                }
                window.setMaximized(false)
                awaitUntil("restored close to original width") {
                    val b = bounds() ?: return@awaitUntil false
                    abs(b[2] - before[2]) <= 80
                }
                check(composed.get()) { "TitleBar dropped out of composition" }
            },
        )
    }

    private fun basicTitleBarFillCenterStaysMapped(): TaoWindowTestCase {
        val composed = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "BasicTitleBar FillCenter composes with RTL controls",
            content = {
                BasicTitleBar(
                    controlButtonsDirection = ControlButtonsDirection.Rtl,
                    layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                    backgroundContent = {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.Magenta))
                    },
                ) {
                    Box(
                        Modifier
                            .height(16.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally)
                            .background(Color.White),
                    )
                    SideEffect { composed.set(true) }
                }
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("BasicTitleBar composed") { composed.get() }
                settle()
                check(requireNotNull(bounds())[2] > 0)
            },
        )
    }

    private fun windowScaffoldDockedPublishesBarHeight(): TaoWindowTestCase {
        val barHeightPx = AtomicInteger(-1)
        return TaoWindowTestCase(
            name = "WindowScaffold docked publishes a non-zero title bar height",
            paintDefaultBackground = false,
            content = {
                val scope = this
                WindowScaffold(
                    titleBar = {
                        scope.TitleBar()
                    },
                    titleBarPlacement = TitleBarPlacement.Docked,
                ) {
                    val insets = LocalWindowChromeInsets.current
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    SideEffect {
                        barHeightPx.set(with(density) { insets.titleBarHeight.roundToPx() })
                    }
                    Box(Modifier.fillMaxSize().background(Color(0xFF303030)))
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("scaffold published bar height") { barHeightPx.get() > 0 }
                settle()
                check(barHeightPx.get() > 0) { "docked scaffold bar height was ${barHeightPx.get()}" }
            },
        )
    }

    private fun windowScaffoldOverlayPadsContent(): TaoWindowTestCase {
        val topPadPx = AtomicInteger(-1)
        return TaoWindowTestCase(
            name = "WindowScaffold overlay reports title-bar height as content padding",
            paintDefaultBackground = false,
            content = {
                val scope = this
                WindowScaffold(
                    titleBar = { scope.TitleBar() },
                    titleBarPlacement = TitleBarPlacement.Overlay(passThroughToContent = true),
                ) { padding ->
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    SideEffect {
                        topPadPx.set(with(density) { padding.calculateTopPadding().roundToPx() })
                    }
                    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("overlay padding published") { topPadPx.get() > 0 }
                settle()
                check(topPadPx.get() > 0) { "overlay content top padding was ${topPadPx.get()}" }
            },
        )
    }

    private fun windowControlsRendererSeesPlatformSlots(): TaoWindowTestCase {
        val seen = CopyOnWriteArrayList<WindowControlType>()
        val renderer =
            WindowControlsRenderer { type, _, _ ->
                SideEffect {
                    if (type !in seen) seen += type
                }
                Box(Modifier.height(12.dp).background(Color.Gray))
            }
        return TaoWindowTestCase(
            name = "WindowControls asks the renderer for each platform slot",
            paintDefaultBackground = false,
            content = {
                val scope = this
                WindowScaffold(
                    titleBar = {
                        Box(Modifier.fillMaxWidth().height(40.dp)) {
                            scope.WindowControls(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                renderer = renderer,
                            )
                        }
                    },
                ) {
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("renderer saw at least close") {
                    seen.contains(WindowControlType.Close)
                }
                settle()
                check(WindowControlType.Close in seen) { "Close slot missing: $seen" }
                check(WindowControlType.Minimize in seen) { "Minimize slot missing: $seen" }
            },
        )
    }

    private fun windowBackgroundAndAppearanceStayMapped(): TaoWindowTestCase {
        val composed = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "WindowBackground and WindowAppearance compose without tearing the window",
            content = {
                WindowAppearance(WindowAppearanceMode.Dark)
                WindowBackground(Color(0xFF101820))
                TitleBar()
                SideEffect { composed.set(true) }
                Box(Modifier.fillMaxSize())
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("appearance/background composed") { composed.get() }
                settle()
                check(requireNotNull(bounds())[3] > 0)
            },
        )
    }

    private fun dialogTitleBarMapsWithParent(): TaoWindowTestCase {
        val dialogComposed = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "DecoratedDialog DialogTitleBar composes next to the parent window",
            dialogSize = DpSize(360.dp, 240.dp),
            dialogContent = {
                DialogTitleBar { _: DecoratedDialogState ->
                    SideEffect { dialogComposed.set(true) }
                }
                Box(Modifier.fillMaxSize().background(Color(0xFF252525)))
            },
            content = {
                TitleBar()
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("dialog window published") { dialogWindow != null }
                awaitUntil("dialog title bar composed") { dialogComposed.get() }
                settle()
                val dialogBounds = dialogWindow?.outerBoundsPx()
                check(dialogBounds != null && dialogBounds[2] > 0) {
                    "dialog never reported a size"
                }
            },
        )
    }
}
