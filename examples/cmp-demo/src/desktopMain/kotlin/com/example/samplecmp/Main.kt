package com.example.samplecmp

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar

fun main() =
    nucleusApplication {
        DecoratedWindow(onCloseRequest = ::exitApplication, title = "Sample CMP") {
            TitleBar {
                Text(
                    text = "Sample CMP",
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 12.dp),
                )
            }
            App()
        }
    }
