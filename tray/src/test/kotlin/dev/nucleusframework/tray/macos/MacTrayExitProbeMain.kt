package dev.nucleusframework.tray.macos

import dev.nucleusframework.tray.internal.MacTrayInitializer
import kotlin.system.exitProcess

/**
 * Child JVM of `a JVM with a live tray and an open menu still ends`: shows a tray, then ends
 * without disposing it, through `exitProcess`, by returning from `main`, or through `exitProcess`
 * while its menu is open (tracking on the main thread).
 */
fun main(args: Array<String>) {
    val mode = args[0]
    MacTrayInitializer.initialize("exit-probe", args[1], "exit probe", null, { Item("x") {} })
    repeat(500) {
        if (MacProbe.snapshot("exit-probe")?.exists == true) return@repeat
        Thread.sleep(10)
    }
    check(MacProbe.snapshot("exit-probe")?.exists == true) { "tray never came up" }
    if (mode == "exit-open") {
        MacTrayInitializer.debugClick("exit-probe", secondary = true)
        repeat(300) {
            if (MacProbe.snapshot("exit-probe")?.menuOpen == true) return@repeat
            Thread.sleep(10)
        }
        check(MacProbe.snapshot("exit-probe")?.menuOpen == true) { "menu never opened" }
    }
    println("READY")
    System.out.flush()
    if (mode != "return") exitProcess(0)
}
