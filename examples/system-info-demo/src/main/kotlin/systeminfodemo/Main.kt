package systeminfodemo

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.jewel.JewelDecoratedWindow
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import systeminfodemo.ui.AppContent
import systeminfodemo.ui.AppTitleBar
import systeminfodemo.ui.buildIslandsTheme

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun main() =
    nucleusApplication(backend = NucleusBackend.Tao) {
        val (theme, styling) = buildIslandsTheme()

        @Suppress("DEPRECATION")
        val defaultTextContextMenu = androidx.compose.foundation.text.LocalTextContextMenu.current
        IntUiTheme(theme = theme, styling = styling) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.text.LocalTextContextMenu provides defaultTextContextMenu,
            ) {
                JewelDecoratedWindow(
                    onCloseRequest = { exitApplication() },
                    title = "Nucleus System Info",
                    state = rememberWindowState(position = WindowPosition.Aligned(Alignment.Center)),
                    minimumSize = DpSize(1100.dp, 480.dp),
                    content = {
                        AppTitleBar()
                        AppContent()
                    },
                )
            }
        }
    }
