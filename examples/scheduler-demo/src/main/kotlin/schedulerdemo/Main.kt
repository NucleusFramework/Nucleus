package schedulerdemo

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.scheduler.DesktopBootReceiver
import dev.nucleusframework.scheduler.TaskRegistry
import dev.nucleusframework.window.jewel.JewelDecoratedWindow
import dev.nucleusframework.window.jewel.JewelTitleBar
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import schedulerdemo.task.BackupTask
import schedulerdemo.task.BackupTaskId
import schedulerdemo.task.NotificationTask
import schedulerdemo.task.NotificationTaskId
import schedulerdemo.task.SyncTask
import schedulerdemo.task.SyncTaskId

private fun buildRegistry() =
    TaskRegistry
        .Builder()
        .register(SyncTaskId) { SyncTask() }
        .register(BackupTaskId) { BackupTask() }
        .register(NotificationTaskId) { NotificationTask() }
        .build()

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun main(args: Array<String>) {
    val openedByScheduler = DesktopBootReceiver.isSchedulerInvocation(args)
    if (openedByScheduler) {
        DesktopBootReceiver.handle(args = args, registry = buildRegistry())
    }

    nucleusApplication(args = args) {
        val textStyle = JewelTheme.createDefaultTextStyle()
        val editorStyle = JewelTheme.createEditorTextStyle()
        val isDark = isSystemInDarkMode()

        val theme =
            if (isDark) {
                JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            } else {
                JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            }

        @Suppress("DEPRECATION")
        val defaultTextContextMenu = androidx.compose.foundation.text.LocalTextContextMenu.current
        IntUiTheme(
            theme = theme,
            styling = ComponentStyling.default(),
        ) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.text.LocalTextContextMenu provides defaultTextContextMenu,
            ) {
                JewelDecoratedWindow(
                    onCloseRequest = { exitApplication() },
                    title = "Scheduler Demo",
                    state =
                        androidx.compose.ui.window.rememberWindowState(
                            position = WindowPosition.Aligned(Alignment.Center),
                        ),
                    content = {
                        JewelTitleBar()
                        SchedulerDemoView(openedByScheduler = openedByScheduler)
                    },
                )
            }
        }
    }
}
