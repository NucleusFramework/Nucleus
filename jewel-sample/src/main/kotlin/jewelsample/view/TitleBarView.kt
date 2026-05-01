package jewelsample.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.tao.DecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.tao.TitleBar
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import jewelsample.IntUiThemes
import jewelsample.showcase.ShowcaseIcons
import jewelsample.showcase.views.forCurrentOs
import jewelsample.viewmodel.MainViewModel
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.skiko.hostOs
import java.awt.Desktop
import java.net.URI

@OptIn(ExperimentalFoundationApi::class)
@ExperimentalLayoutApi
@Composable
internal fun DecoratedWindowScope.TitleBarView() {
    val startPadding = if (hostOs.isMacOS) 0.dp else 8.dp
    val background = JewelTheme.globalColors.panelBackground
    val contentColor = JewelTheme.contentColor
    TitleBar(height = 40.dp, background = background, titleColor = contentColor) { state ->
        CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(Modifier.align(Alignment.Start).padding(start = startPadding)) {
            Dropdown(
                Modifier.height(30.dp),
                menuContent = {
                    MainViewModel.views.forEach {
                        selectableItem(
                            selected = MainViewModel.currentView == it,
                            onClick = { MainViewModel.currentView = it },
                            keybinding = it.keyboardShortcut?.forCurrentOs(),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(it.iconKey, null, modifier = Modifier.size(20.dp), hint = Size(20))
                                Text(it.title)
                            }
                        }
                    }
                },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MainViewModel.currentView.iconKey, null, hint = Size(20))
                        Text(MainViewModel.currentView.title)
                    }
                }
            }
        }

        Text(title)

        Row(Modifier.align(Alignment.End)) {
            Tooltip({ Text("Open Jewel Github repository") }) {
                val jewelGithubLink = "https://github.com/JetBrains/intellij-community/tree/master/platform/jewel"
                IconButton(
                    { Desktop.getDesktop().browse(URI.create(jewelGithubLink)) },
                    Modifier.size(40.dp).padding(5.dp),
                ) {
                    Icon(ShowcaseIcons.gitHub, "Github")
                }
            }

            Tooltip(
                tooltip = {
                    when (MainViewModel.theme) {
                        IntUiThemes.Light -> Text("Switch to light theme with light header")
                        IntUiThemes.LightWithLightHeader -> Text("Switch to dark theme")
                        IntUiThemes.Dark -> Text("Switch to system theme")
                        IntUiThemes.System -> Text("Switch to light theme")
                    }
                },
            ) {
                IconButton(
                    {
                        MainViewModel.theme =
                            when (MainViewModel.theme) {
                                IntUiThemes.Light -> IntUiThemes.LightWithLightHeader
                                IntUiThemes.LightWithLightHeader -> IntUiThemes.Dark
                                IntUiThemes.Dark -> IntUiThemes.System
                                IntUiThemes.System -> IntUiThemes.Light
                            }
                    },
                    Modifier.size(40.dp).padding(5.dp),
                ) {
                    val (iconKey, description) =
                        when (MainViewModel.theme) {
                            IntUiThemes.Light -> ShowcaseIcons.themeLight to "Light"
                            IntUiThemes.LightWithLightHeader ->
                                ShowcaseIcons.themeLightWithLightHeader to "Light with light header"
                            IntUiThemes.Dark -> ShowcaseIcons.themeDark to "Dark"
                            IntUiThemes.System -> ShowcaseIcons.themeSystem to "System"
                        }
                    Icon(key = iconKey, contentDescription = description, hints = arrayOf(Size(20)))
                }
            }
        }
        }
    }
}
