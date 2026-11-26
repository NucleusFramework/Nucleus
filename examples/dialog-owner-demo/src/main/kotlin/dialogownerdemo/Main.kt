package dialogownerdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import kotlinx.coroutines.delay

// The E2E drives this app from outside: it finds both windows by title, raises the main one the way
// the taskbar does, and types. Every text change is printed so the driver can tell which window got it.
fun main() =
    nucleusApplication {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "DialogOwnerDemo Main",
            state = rememberWindowState(width = 700.dp, height = 500.dp),
        ) {
            var mainText by remember { mutableStateOf("") }
            var showDialog by remember { mutableStateOf(false) }
            val focus = remember { FocusRequester() }
            Column(Modifier.fillMaxSize().background(Color(0xFF203050)).padding(24.dp)) {
                BasicTextField(
                    value = mainText,
                    onValueChange = {
                        mainText = it
                        println("E2E main-text=$it")
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(8.dp)
                            .focusRequester(focus),
                )
            }
            LaunchedEffect(Unit) {
                focus.requestFocus()
                delay(1500)
                showDialog = true
            }
            if (showDialog) {
                DecoratedDialog(
                    onCloseRequest = { showDialog = false },
                    title = "DialogOwnerDemo Dialog",
                    state = rememberDialogState(width = 360.dp, height = 240.dp),
                ) {
                    var dialogText by remember { mutableStateOf("") }
                    val dialogFocus = remember { FocusRequester() }
                    Column(Modifier.fillMaxSize().background(Color(0xFF805020)).padding(24.dp)) {
                        BasicTextField(
                            value = dialogText,
                            onValueChange = {
                                dialogText = it
                                println("E2E dialog-text=$it")
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Color.White,
                                    ).padding(8.dp)
                                    .focusRequester(dialogFocus),
                        )
                    }
                    LaunchedEffect(Unit) {
                        dialogFocus.requestFocus()
                        println("E2E dialog-shown")
                    }
                }
            }
        }
    }
