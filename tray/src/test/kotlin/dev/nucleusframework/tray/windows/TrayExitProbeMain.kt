package dev.nucleusframework.tray.windows

import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import kotlin.system.exitProcess

/**
 * Child JVM of `a JVM with a live tray still ends and takes the icon with it`: shows a tray, reports its identity,
 * then ends without disposing it, either through `exitProcess` or by returning from `main`.
 */
fun main(args: Array<String>) {
    val mode = args[0]
    val icon = args[1]
    WindowsTrayInitializer.initialize("exit-probe", icon, "exit probe", null, { Item("x") {} })
    var identity: Pair<Long, Int>? = null
    repeat(500) {
        identity = identity ?: WindowsTrayInitializer.nativeIdentity("exit-probe")
        if (identity == null) Thread.sleep(10)
    }
    val (hwnd, uid) = identity ?: error("tray never came up")
    println("IDENTITY $hwnd $uid")
    System.out.flush()
    if (mode == "exit") exitProcess(0)
}
