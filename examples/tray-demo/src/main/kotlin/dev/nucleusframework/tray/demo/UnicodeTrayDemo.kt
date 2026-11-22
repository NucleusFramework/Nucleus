package dev.nucleusframework.tray.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.tray.demo.resources.Res
import dev.nucleusframework.tray.demo.resources.icon
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import org.jetbrains.compose.resources.painterResource

/**
 * UnicodeTrayDemo
 *
 * Demonstrates a tray menu with French and Chinese items, including special
 * characters like accented letters (é, à, ç) and punctuation.
 * This validates end-to-end UTF-8 → UTF-16 handling on Windows and generic
 * Unicode rendering across platforms.
 */
fun main() =
    nucleusApplication(enableSingleInstance = false) {
        val painter = painterResource(Res.drawable.icon)

        var lastClicked by remember { mutableStateOf("-") }

        Tray(
            iconContent = {
                Image(painter = painter, contentDescription = "Unicode Tray", modifier = Modifier.fillMaxSize())
            },
            primaryAction = {
                // no-op: right-click to open menu
            },
            tooltip = "Démo: Menu Unicode (Français/中文)",
        ) {
            // French items
            Item(label = "Bonjour – Édition") {
                lastClicked = "Bonjour – Édition"
                println("Clicked: $lastClicked")
            }
            Item(label = "Préférences…") {
                lastClicked = "Préférences…"
                println("Clicked: $lastClicked")
            }
            Item(label = "À propos") {
                lastClicked = "À propos"
                println("Clicked: $lastClicked")
            }
            Item(label = "Quitter") {
                lastClicked = "Quitter"
                println("Clicked: $lastClicked")
                exitApplication()
            }

            Divider()

            // Chinese submenu with mixed content
            SubMenu(label = "设置 / 設置") {
                Item(label = "语言：中文（简体）") {
                    lastClicked = "语言：中文（简体）"
                    println("Clicked: $lastClicked")
                }
                Item(label = "語言：中文（繁體）") {
                    lastClicked = "語言：中文（繁體）"
                    println("Clicked: $lastClicked")
                }
                CheckableItem(label = "启用高级选项 ✓", checked = true) { checked ->
                    lastClicked = "启用高级选项: ${if (checked) "开" else "关"}"
                    println("Clicked: $lastClicked")
                }
            }

            SubMenu(label = "信息 ℹ / 信息") {
                Item(label = "关于 / 關於") {
                    lastClicked = "关于 / 關於"
                    println("Clicked: $lastClicked")
                }
                Item(label = "退出 / 退出") {
                    lastClicked = "退出"
                    println("Clicked: $lastClicked")
                    exitApplication()
                }
            }

            Divider()

            // Mixed accents and symbols
            Item(label = "Café crème – façade – piñata – coöperate") {
                lastClicked = "Café crème – façade – piñata – coöperate"
                println("Clicked: $lastClicked")
            }
            Item(label = "Symbols: € £ ¥ • — … ✓ ✗ © ® ™") {
                lastClicked = "Symbols"
                println("Clicked: $lastClicked")
            }

            Divider()

            Item(label = "Dernier: $lastClicked", isEnabled = false)
        }

        NucleusDecoratedWindowTheme(isDark = isSystemInDarkMode()) {
            DecoratedWindow(onCloseRequest = ::exitApplication, title = "Unicode Tray Demo") {
                TitleBar { Text("Unicode Tray Demo") }
            }
        }
    }
