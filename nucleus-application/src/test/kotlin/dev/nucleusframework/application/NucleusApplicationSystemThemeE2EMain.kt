package dev.nucleusframework.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import org.jetbrains.skiko.SystemTheme as SkikoSystemTheme
import org.jetbrains.skiko.currentSystemTheme
import java.io.File
import kotlin.system.exitProcess

/**
 * Process-level E2E: boots a real Compose `application` + `Window` under
 * [ProvideNucleusSystemTheme] (same bridge as `nucleusApplication`) and writes
 * a PASS/FAIL report. Exit code 0 = PASS, 1 = FAIL.
 *
 * Run: `./gradlew :nucleus-application:systemThemeE2E`
 *
 * Note: Compose's `application` defaults to `exitProcessOnExit = true`, so we
 * must write the report *before* [exitApplication] — nothing after `application { }`
 * runs on success.
 */
fun main() {
    val reportFile =
        File(System.getProperty("systemThemeE2E.report", "system-theme-e2e.report"))
    reportFile.parentFile?.mkdirs()

    // exitProcessOnExit = false so we control the process exit code after sampling.
    application(exitProcessOnExit = false) {
        ProvideNucleusSystemTheme {
            Window(
                onCloseRequest = ::exitApplication,
                title = "system-theme-e2e",
                visible = false,
            ) {
                val detector = getPlatformDarkModeDetector().isDark()
                val nucleus = isSystemInDarkMode()
                val compose = isSystemInDarkTheme()
                val skikoDark = currentSystemTheme == SkikoSystemTheme.DARK

                Box(Modifier.size(1.dp))

                LaunchedEffect(detector, nucleus, compose, skikoDark) {
                    val pass = detector == nucleus && nucleus == compose
                    val text =
                        buildString {
                            appendLine("detector=$detector")
                            appendLine("isSystemInDarkMode=$nucleus")
                            appendLine("isSystemInDarkTheme=$compose")
                            appendLine("skikoDark=$skikoDark")
                            appendLine(if (pass) "RESULT=PASS" else "RESULT=FAIL")
                        }
                    reportFile.writeText(text)
                    print(text)
                    exitApplication()
                }
            }
        }
    }

    val text =
        if (reportFile.exists()) {
            reportFile.readText()
        } else {
            "RESULT=FAIL\n(no report — composition did not run)\n"
                .also { reportFile.writeText(it) }
        }
    if (!text.contains("RESULT=PASS")) {
        System.err.print(text)
        exitProcess(1)
    }
}
