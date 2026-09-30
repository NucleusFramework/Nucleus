package dev.nucleusframework.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * E2E reproduction of issue #366 — Linux SIGSEGV in libsqlite3.so when a
 * Room/androidx-sqlite *bundled* driver write runs after a Tao decorated
 * window opened.
 *
 * Mechanism under test: nucleus_tao_linux_widget.c dlopen()s GTK. With
 * RTLD_GLOBAL, on distros where GTK's dependency closure includes
 * libsqlite3 (NixOS: gtk3 -> tinysparql -> sqlite), the
 * system libsqlite3 enters the *global* symbol scope. The androidx
 * bundled-sqlite JNI library binds its sqlite3_* PLT entries lazily, so
 * every sqlite entry point first called *after* the window opened resolves
 * to the system library and operates on statement objects created by the
 * bundled copy -> SIGSEGV (issue: sqlite3VdbeMemGrow via bindText).
 *
 * Must run in a dedicated JVM with LD_LIBRARY_PATH pointing at a patched
 * libgtk-3.so.0 that has libsqlite3.so.0 added as DT_NEEDED to recreate
 * the NixOS closure on any distro — [GtkSqliteInterpositionTest] builds
 * that shim and forks this main (a SIGSEGV kills the whole JVM, so the
 * outcome has to be observed from outside).
 */
private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

fun main() {
    println("[repro] phase 1: open bundled-sqlite DB + read BEFORE any window")
    val dbFile = File(System.getProperty("java.io.tmpdir"), "nucleus-issue366.db")
    dbFile.delete()
    val conn = BundledSQLiteDriver().open(dbFile.absolutePath)
    conn.execSQL("CREATE TABLE track(id INTEGER PRIMARY KEY, name TEXT)")
    conn.prepare("SELECT count(*) FROM track").use { st ->
        st.step()
        println("[repro] phase 1 read OK, count=${st.getLong(0)}")
    }

    nucleusApplication {
        NucleusDecoratedWindowTheme(isDark = true) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "issue-366 repro",
            ) {
                LaunchedEffect(Unit) {
                    delay(3_000)
                    // Prove the widget helper dlopen-ed a functional GTK
                    // (the version probe goes through the same RTLD_LOCAL
                    // load path as every other entry point).
                    val stamp =
                        runCatching {
                            Class
                                .forName("dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge")
                                .getMethod("nativeGtkVersion")
                                .invoke(null)
                        }.getOrElse { "error: $it" }
                    println("[repro] gtk probe version = $stamp")
                    println("[repro] phase 2: text-bound WRITE after window opened")
                    withContext(Dispatchers.IO) {
                        conn.prepare("INSERT INTO track(name) VALUES (?)").use { st ->
                            st.bindText(1, "hello-from-issue-366")
                            st.step()
                        }
                        conn.prepare("SELECT name FROM track").use { st ->
                            st.step()
                            println("[repro] phase 2 read-back: ${st.getText(0)}")
                        }
                    }
                    println("[repro] write OK — bug NOT reproduced")
                    exitProcess(0)
                }
                Box(Modifier.fillMaxSize().background(Color(0xFF0F1115)))
            }
        }
    }
}
