package dev.nucleusframework.tray.utils

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.io.File
import kotlin.system.exitProcess

/**
 * Two-JVM helper for [Issue534ReproTest].
 *
 * `--hold` loads WinTray and stays alive so the OS keeps that extracted DLL locked.
 * `--extract` is a second process that loads a different WinTray binary.
 */
object Issue534ReproMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val ok =
                NativeLibraryLoader.load(
                    "nucleus_tray",
                    Issue534ReproMain::class.java,
                    
                )
            println("LOAD_OK=$ok")
            System.out.flush()
            if ("--hold" in args) {
                val stopPath =
                    args.firstOrNull { it.startsWith("--stop-file=") }
                        ?.removePrefix("--stop-file=")
                        ?: error("--hold requires --stop-file=<path>")
                val stopFile = File(stopPath)
                println("HOLDING")
                System.out.flush()
                while (!stopFile.exists()) {
                    Thread.sleep(50)
                }
            }
        } catch (t: Throwable) {
            System.err.println("LOAD_FAIL=${t.javaClass.name}: ${t.message}")
            t.printStackTrace()
            exitProcess(1)
        }
    }
}
