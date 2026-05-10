package systeminfodemo

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.nucleus.window.jewel.JewelDecoratedWindow
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import systeminfodemo.ui.AppContent
import systeminfodemo.ui.AppTitleBar
import systeminfodemo.ui.buildIslandsTheme

fun main() {
    dev.nucleusframework.nucleus.graalvm.GraalVmInitializer
        .initialize()

    application {
        val (theme, styling) = buildIslandsTheme()

        IntUiTheme(theme = theme, styling = styling) {
            JewelDecoratedWindow(
                onCloseRequest = { exitApplication() },
                title = "Nucleus System Info",
                state = rememberWindowState(position = WindowPosition.Aligned(Alignment.Center)),
                content = {
                    window.minimumSize = java.awt.Dimension(1100, 480)
                    AppTitleBar()
                    AppContent()
                },
            )
        }
    }
}
