package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.buildLinuxAppImageUpdateScript

/**
 * CLI helper for the full-GUI e2e shell script: prints the exact production update script.
 * Invoked as:
 *   java -cp ... dev.nucleusframework.updater.DumpLinuxAppImageUpdateScriptKt \
 *     <newFile> <oldFile> <pid> <logFile>
 */
fun main(args: Array<String>) {
    require(args.size >= 4) { "usage: newFile oldFile pid logFile" }
    print(
        buildLinuxAppImageUpdateScript(
            newFile = args[0],
            oldFile = args[1],
            appPid = args[2].toLong(),
            logFile = args[3],
            restart = true,
            alreadyReplaced = args.getOrNull(4)?.toBooleanStrictOrNull() ?: true,
            selfDelete = false,
        ),
    )
}
