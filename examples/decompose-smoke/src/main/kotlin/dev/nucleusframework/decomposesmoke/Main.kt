@file:OptIn(ExperimentalDecomposeApi::class, InternalDecomposeApi::class)

package dev.nucleusframework.decomposesmoke

import androidx.compose.runtime.LaunchedEffect
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DecomposeSettings
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.InternalDecomposeApi
import com.arkivanov.decompose.mainthread.MainThreadChecker
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.ServiceLoader
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Serializable
private data object Details

/** A component whose `childSlot` runs Decompose's main-thread check on creation and navigation. */
private class SmokeComponent(
    context: ComponentContext,
) : ComponentContext by context {
    private val navigation = SlotNavigation<Details>()
    private val slot = childSlot(source = navigation, serializer = Details.serializer()) { config, _ -> config }

    fun open() {
        navigation.activate(Details)
        check(slot.value.child != null) { "slot did not activate" }
    }
}

private val decomposeErrors = mutableListOf<Exception>()
private var failed = false

/** Builds and navigates a component, returning whether Decompose reported it off the main thread. */
private fun buildComponent(): Boolean {
    synchronized(decomposeErrors) { decomposeErrors.clear() }
    SmokeComponent(DefaultComponentContext(LifecycleRegistry().apply { resume() })).open()
    // NotOnMainThreadException is internal to Decompose.
    val errors = synchronized(decomposeErrors) { decomposeErrors.map { it.javaClass.simpleName } }
    return "NotOnMainThreadException" in errors
}

private fun expect(
    step: String,
    rejected: Boolean,
    expectRejected: Boolean,
) {
    val ok = rejected == expectRejected
    if (!ok) failed = true
    val verdict = if (rejected) "rejected" else "accepted"
    println("[decompose-smoke] ${if (ok) "OK  " else "FAIL"} $step on ${Thread.currentThread().name}: $verdict")
}

fun main() {
    DecomposeSettings.update { settings ->
        settings.copy(onDecomposeError = { synchronized(decomposeErrors) { decomposeErrors += it } })
    }
    val checkers = ServiceLoader.load(MainThreadChecker::class.java).map { it::class.java.name }
    println("[decompose-smoke] MainThreadChecker providers: $checkers")

    // The issue's repro: Dispatchers.Main before the loop runs lands on the fallback thread.
    runBlocking {
        withContext(Dispatchers.Main) { expect("pre-loop Dispatchers.Main", buildComponent(), false) }
    }

    nucleusApplication(enableSingleInstance = false) {
        DecoratedWindow(onCloseRequest = ::exitApplication, title = "decompose-smoke") {
            LaunchedEffect(Unit) {
                expect("event-loop Dispatchers.Main", buildComponent(), false)
                // The check must still catch real background access.
                thread(name = "decompose-smoke-background") {
                    expect("background thread", buildComponent(), true)
                }.join()
                println("[decompose-smoke] ${if (failed) "FAILED" else "PASSED"}")
                // nucleusApplication may end the process itself, so a failure exits right here.
                if (failed) exitProcess(1)
                exitApplication()
            }
        }
    }
}
