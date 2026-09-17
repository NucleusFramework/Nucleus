package dev.nucleusframework.globalhotkey.repro

import dev.nucleusframework.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.globalhotkey.HotKeyModifier
import dev.nucleusframework.globalhotkey.plus
import java.awt.event.KeyEvent

/**
 * Controlled reproduction / verification for
 * https://github.com/NucleusFramework/Nucleus/issues/264 residual bugs (post #265):
 *
 * 1. Portal shortcut_id must be stable across registration order (not AtomicLong).
 * 2. A loop of register() must produce a single BindShortcuts (one system dialog).
 *
 *   ./gradlew :global-hotkey:runIssue264Repro -PreproOrder=a
 *   ./gradlew :global-hotkey:runIssue264Repro -PreproOrder=b
 *
 * Expected after the fix:
 * - Ctrl+A → nucleus_m2_k41, Ctrl+B → nucleus_m2_k42, Ctrl+C → nucleus_m2_k43
 *   in both orders (same physical key → same shortcut_id).
 * - One commitRegistrations() → one portal bind for the whole set.
 */
fun main() {
    val order = System.getProperty("repro.order", "a")
    val keys =
        when (order) {
            "b" ->
                listOf(
                    "Ctrl+C" to KeyEvent.VK_C,
                    "Ctrl+B" to KeyEvent.VK_B,
                    "Ctrl+A" to KeyEvent.VK_A,
                )
            else ->
                listOf(
                    "Ctrl+A" to KeyEvent.VK_A,
                    "Ctrl+B" to KeyEvent.VK_B,
                    "Ctrl+C" to KeyEvent.VK_C,
                )
        }

    println("=== Issue #264 repro (order=$order) ===")
    println("session=${System.getenv("XDG_SESSION_TYPE")} wayland=${System.getenv("WAYLAND_DISPLAY")}")
    println("Registering ${keys.size} hotkeys via GlobalHotKeyManager (real portal path)...")

    if (!GlobalHotKeyManager.initialize()) {
        System.err.println("initialize failed: ${GlobalHotKeyManager.lastError}")
        kotlin.system.exitProcess(1)
    }

    val results = mutableListOf<Triple<String, Long, String?>>()
    val t0 = System.nanoTime()
    for ((label, keyCode) in keys) {
        val tReg = System.nanoTime()
        println("-- register($label) ...")
        val handle =
            GlobalHotKeyManager.register(
                keyCode = keyCode,
                modifiers = 0 + HotKeyModifier.CONTROL,
                description = "Issue264 $label",
            ) { _, _ -> println("activated $label") }
        val ms = (System.nanoTime() - tReg) / 1_000_000
        if (handle < 0) {
            System.err.println("   FAILED after ${ms}ms: ${GlobalHotKeyManager.lastError}")
        } else {
            val sid = GlobalHotKeyManager.portalShortcutId(handle)
            println("   handle=$handle shortcut_id=$sid in ${ms}ms")
            results += Triple(label, handle, sid)
        }
    }
    val regMs = (System.nanoTime() - t0) / 1_000_000
    println("Stored ${results.size}/${keys.size} in ${regMs}ms (bind deferred)")

    val tBind = System.nanoTime()
    println("-- commitRegistrations() (single BindShortcuts) ...")
    val ok = GlobalHotKeyManager.commitRegistrations()
    val bindMs = (System.nanoTime() - tBind) / 1_000_000
    println("   commit ok=$ok in ${bindMs}ms error=${GlobalHotKeyManager.lastError}")

    println("Mapping (label → handle → shortcut_id):")
    for ((label, handle, sid) in results) {
        println("  $label → handle=$handle → $sid")
    }

    // Stability check for the chords we care about
    val byLabel = results.associate { it.first to it.third }
    val expected =
        mapOf(
            "Ctrl+A" to "nucleus_m2_k41",
            "Ctrl+B" to "nucleus_m2_k42",
            "Ctrl+C" to "nucleus_m2_k43",
        )
    var stable = true
    for ((label, want) in expected) {
        val got = byLabel[label]
        if (got != want) {
            System.err.println("UNSTABLE/WRONG id for $label: got=$got want=$want")
            stable = false
        }
    }
    println(if (stable) "PASS: shortcut_ids stable and order-independent" else "FAIL: shortcut_ids incorrect")

    println("Sleeping 1s, then shutdown...")
    Thread.sleep(1000)
    GlobalHotKeyManager.shutdown()
    println("Done.")
    if (!stable) kotlin.system.exitProcess(2)
}
