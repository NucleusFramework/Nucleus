package dev.nucleusframework.application

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Process-level E2E that **actually flips** the OS color-scheme and checks that
 * under [ProvideNucleusSystemTheme] both [isSystemInDarkMode] and official
 * [isSystemInDarkTheme] track each transition.
 *
 * Run: `./gradlew :nucleus-application:systemThemeE2E`
 */
fun main() {
    if (!LinuxColorSchemeToggle.isAvailable) {
        failReport("gsettings/session bus unavailable — cannot toggle OS theme")
    }

    val reportFile =
        File(System.getProperty("systemThemeE2E.report", "system-theme-e2e.report"))
    reportFile.parentFile?.mkdirs()

    val original = LinuxColorSchemeToggle.read()
    val log = StringBuilder().appendLine("originalScheme=$original")
    val processExit = AtomicInteger(1)

    application(exitProcessOnExit = false) {
        ProvideNucleusSystemTheme {
            // visible=true so the scene keeps producing frames; otherwise
            // snapshot applies from the D-Bus JNI thread may never recompose.
            Window(
                onCloseRequest = ::exitApplication,
                title = "system-theme-e2e",
                visible = true,
            ) {
                val nucleus = isSystemInDarkMode()
                val compose = isSystemInDarkTheme()
                var lastNucleus by remember { mutableStateOf(nucleus) }
                var lastCompose by remember { mutableStateOf(compose) }
                lastNucleus = nucleus
                lastCompose = compose

                Box(Modifier.size(8.dp))

                LaunchedEffect(Unit) {
                    try {
                        for (scheme in listOf("prefer-light", "prefer-dark", "prefer-light")) {
                            val expectDark = LinuxColorSchemeToggle.isDarkScheme(scheme)
                            LinuxColorSchemeToggle.write(scheme)
                            log.appendLine("set scheme=$scheme expectDark=$expectDark")

                            val ok =
                                awaitFrames(timeoutMs = 15_000) {
                                    val d = getPlatformDarkModeDetector().isDark()
                                    lastNucleus == expectDark &&
                                        lastCompose == expectDark &&
                                        d == expectDark
                                }
                            log.appendLine(
                                "  after: detector=${getPlatformDarkModeDetector().isDark()} " +
                                    "nucleus=$lastNucleus compose=$lastCompose ok=$ok",
                            )
                            if (!ok || lastNucleus != lastCompose) {
                                log.appendLine("RESULT=FAIL")
                                reportFile.writeText(log.toString())
                                print(log.toString())
                                exitApplication()
                                return@LaunchedEffect
                            }
                        }
                        log.appendLine("RESULT=PASS")
                        processExit.set(0)
                        reportFile.writeText(log.toString())
                        print(log.toString())
                        exitApplication()
                    } catch (t: Throwable) {
                        log.appendLine("exception=${t.stackTraceToString()}")
                        log.appendLine("RESULT=FAIL")
                        reportFile.writeText(log.toString())
                        System.err.print(log.toString())
                        exitApplication()
                    } finally {
                        runCatching { LinuxColorSchemeToggle.write(original) }
                    }
                }
            }
        }
    }

    if (!reportFile.exists()) {
        failReport("composition did not finish\n$log", reportFile)
    }
    exitProcess(processExit.get())
}

/** Pump Compose frames while waiting for JNI → Snapshot recomposition. */
private suspend fun awaitFrames(
    timeoutMs: Long,
    predicate: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        withFrameNanos { }
        if (predicate()) return true
    }
    withFrameNanos { }
    return predicate()
}

private fun failReport(
    message: String,
    reportFile: File =
        File(System.getProperty("systemThemeE2E.report", "system-theme-e2e.report")),
): Nothing {
    reportFile.parentFile?.mkdirs()
    val text = "RESULT=FAIL\n$message\n"
    reportFile.writeText(text)
    System.err.print(text)
    exitProcess(1)
}
