package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.component.Icon as JewelIcon

/** An icon of the Lab: an IntelliJ platform icon, or a vector a probe brings itself. */
@Immutable
class LabIcon private constructor(
    internal val key: IconKey?,
    internal val vector: ImageVector?,
) {
    companion object {
        /** A probe's own vector (a Material icon inside a specimen, a custom path). */
        fun of(vector: ImageVector): LabIcon = LabIcon(null, vector)

        internal fun of(key: IconKey): LabIcon = LabIcon(key, null)
    }
}

/** The icons the Lab uses, mapped onto IntelliJ's own set (`AllIconsKeys`), light and dark. */
object LabIcons {
    val Search = LabIcon.of(AllIconsKeys.Actions.Search)
    val Copy = LabIcon.of(AllIconsKeys.Actions.Copy)
    val Checklist = LabIcon.of(AllIconsKeys.Toolwindows.ToolWindowTodo)
    val Terminal = LabIcon.of(AllIconsKeys.Nodes.Console)
    val ThemeSystem = LabIcon.of(labIcon("systemTheme"))
    val ThemeLight = LabIcon.of(labIcon("lightTheme"))
    val ThemeDark = LabIcon.of(labIcon("darkTheme"))
    val Refresh = LabIcon.of(AllIconsKeys.Actions.Refresh)
    val Play = LabIcon.of(AllIconsKeys.Actions.Execute)
    val Rerun = LabIcon.of(AllIconsKeys.Actions.Rerun)
    val Stop = LabIcon.of(AllIconsKeys.Actions.Suspend)
    val Close = LabIcon.of(AllIconsKeys.Actions.Close)
    val Add = LabIcon.of(AllIconsKeys.General.Add)
    val Delete = LabIcon.of(AllIconsKeys.General.Delete)
    val Edit = LabIcon.of(AllIconsKeys.Actions.Edit)
    val Settings = LabIcon.of(AllIconsKeys.General.Settings)
    val Info = LabIcon.of(AllIconsKeys.General.Information)
    val Warning = LabIcon.of(AllIconsKeys.General.Warning)
    val Error = LabIcon.of(AllIconsKeys.General.Error)
    val Success = LabIcon.of(AllIconsKeys.Status.Success)
    val Folder = LabIcon.of(AllIconsKeys.Nodes.Folder)
    val File = LabIcon.of(AllIconsKeys.FileTypes.Any_type)
    val Text = LabIcon.of(AllIconsKeys.FileTypes.Text)
    val Image = LabIcon.of(AllIconsKeys.FileTypes.Image)
    val Link = LabIcon.of(AllIconsKeys.Ide.Link)
    val External = LabIcon.of(AllIconsKeys.Ide.External_link_arrow)
    val More = LabIcon.of(AllIconsKeys.Actions.More)
    val ChevronDown = LabIcon.of(AllIconsKeys.General.ChevronDown)
    val ChevronRight = LabIcon.of(AllIconsKeys.General.ChevronRight)
    val Check = LabIcon.of(AllIconsKeys.Actions.Checked)
    val History = LabIcon.of(AllIconsKeys.General.History)
    val Pin = LabIcon.of(AllIconsKeys.General.Pin)
    val Filter = LabIcon.of(AllIconsKeys.General.Filter)
    val Reset = LabIcon.of(AllIconsKeys.General.Reset)
    val Preview = LabIcon.of(AllIconsKeys.Actions.Preview)
    val Keyboard = LabIcon.of(AllIconsKeys.General.Keyboard)
    val Mouse = LabIcon.of(AllIconsKeys.General.Mouse)
    val Notification = LabIcon.of(AllIconsKeys.Toolwindows.Notifications)
    val Window = LabIcon.of(AllIconsKeys.Actions.MoveToWindow)
    val ProjectTree = LabIcon.of(AllIconsKeys.Toolwindows.ToolWindowProject)
    val Dock = LabIcon.of(AllIconsKeys.General.OpenInToolWindow)
    val Hide = LabIcon.of(AllIconsKeys.General.HideToolWindow)
    val Layout = LabIcon.of(AllIconsKeys.General.Layout)
    val Desktop = LabIcon.of(AllIconsKeys.Nodes.Desktop)
    val Lock = LabIcon.of(AllIconsKeys.Nodes.Locked)
}

private fun labIcon(name: String): IconKey = PathIconKey("lab/icons/$name.svg", LabIcons::class.java)

/** An icon, 16 dp unless [modifier] sizes it. [tint] recolours it (IntelliJ icons keep their own colours). */
@Composable
fun Icon(
    icon: LabIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val sized = modifier.size(16.dp)
    when {
        icon.key != null -> JewelIcon(icon.key, contentDescription, sized, tint = tint)
        icon.vector != null ->
            JewelIcon(
                rememberVectorPainter(icon.vector),
                contentDescription,
                sized,
                tint = tint.takeOrElseContent(),
            )
    }
}

@Composable
private fun Color.takeOrElseContent(): Color = if (this == Color.Unspecified) LabTheme.colors.text else this
