package dev.nucleusframework.lab.app

import dev.nucleusframework.lab.core.commands.LabCommands
import dev.zacsweers.metro.createGraph
import kotlin.system.exitProcess

/**
 * `nucleus-lab-cli`, the packaged additional launcher (console subsystem on Windows): prints
 * where an installed Lab runs and what it can test, without opening a window.
 *
 * ```
 * nucleus-lab-cli            # environment
 * nucleus-lab-cli probes     # every probe with its deep link
 * nucleus-lab-cli fixtures   # every fixture with its variants
 * ```
 */
fun main(args: Array<String>) {
    val graph = createGraph<LabGraph>()
    when (args.firstOrNull()) {
        null, "env" -> {
            val env = graph.environment.snapshot.value
            println(env.summary)
            println("fingerprint ${env.fingerprint}")
            println("app         ${env.appId} ${env.appVersion.orEmpty()}")
            env.nucleusProperties.forEach { (k, v) -> println("$k=$v") }
        }
        "probes" ->
            graph.probes
                .map { it.descriptor }
                .sortedWith(compareBy({ it.domain.ordinal }, { it.id.value }))
                .forEach { d ->
                    val here = if (d.supportsCurrentPlatform) "" else "  (not on this OS)"
                    println("${d.id.value.padEnd(32)} ${LabCommands.deepLink(d.id)}$here")
                }
        "fixtures" ->
            graph.fixtures.sortedBy { it.id }.forEach { f ->
                println("${f.id.padEnd(28)} ${f.variants.joinToString { it.name }}")
            }
        else -> {
            System.err.println("Usage: nucleus-lab-cli [env|probes|fixtures]")
            exitProcess(2)
        }
    }
}
