package dev.nucleusframework.lab.app

import dev.nucleusframework.application.aotTraining
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.lab.app.shell.LabApp
import dev.nucleusframework.lab.core.fixture.FIXTURE_PROPERTY
import dev.nucleusframework.lab.core.process.FIXTURE_ARGUMENT
import dev.nucleusframework.lab.probes.shell.widgets.LAB_COUNTER_WIDGET
import dev.nucleusframework.lab.probes.shell.widgets.LabCounterWidget
import dev.nucleusframework.widget.widgets
import dev.zacsweers.metro.createGraph
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val graph = createGraph<LabGraph>()

    // A fixture owns its process: hand it over before any Lab UI exists.
    val fixtureId =
        System.getProperty(FIXTURE_PROPERTY)
            ?: args.firstOrNull { it.startsWith(FIXTURE_ARGUMENT) }?.removePrefix(FIXTURE_ARGUMENT)
    if (fixtureId != null) {
        val fixture = graph.fixtures.firstOrNull { it.id == fixtureId }
        if (fixture == null) {
            System.err.println("Unknown fixture '$fixtureId'. Known: ${graph.fixtures.joinToString { it.id }}")
            exitProcess(2)
        }
        fixture.run(args.filterNot { it.startsWith(FIXTURE_ARGUMENT) }.toTypedArray())
        return
    }

    nucleusApplication(args) {
        // Exits after 45 s under -Dnucleus.aot.mode=training, so packaging can record an AOT cache.
        aotTraining(duration = 45.seconds)
        onDeepLink(graph.commands::handleDeepLink)
        // Windows 11 widgets board (probe shell.windows-widgets); declared in windows { widgets { } } too.
        widgets { widget(LAB_COUNTER_WIDGET) { LabCounterWidget() } }
        LabApp(graph)
    }
}
