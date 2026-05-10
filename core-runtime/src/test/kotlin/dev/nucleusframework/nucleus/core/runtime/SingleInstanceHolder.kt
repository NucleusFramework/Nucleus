package dev.nucleusframework.nucleus.core.runtime

import java.nio.file.Paths

/**
 * Subprocess helper that exercises the real [SingleInstanceManager].
 *
 * Args: <lockDir> <lockIdentifier> [holdSeconds]
 *
 * Prints to stdout (one tag per line):
 *   "SINGLE"          — isSingleInstance() returned true (primary)
 *   "NOT_SINGLE"      — isSingleInstance() returned false (secondary)
 *   "RESTORE_REQUEST" — primary received a restore request from another instance
 *   "ERROR:<message>"  — exception was thrown
 *
 * When SINGLE:
 *   - If holdSeconds is provided, holds the lock for that duration then exits with code 0
 *   - Otherwise, sleeps forever (expects SIGKILL to simulate crash)
 *
 * Exit codes: 0 = primary, 1 = secondary, 2 = error
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: <lockDir> <lockId> [holdSeconds]")
        System.exit(2)
    }

    val lockDir = Paths.get(args[0])
    val lockId = args[1]
    val holdSeconds = args.getOrNull(2)?.toLongOrNull()

    SingleInstanceManager.configuration =
        SingleInstanceManager.Configuration(
            lockFilesDir = lockDir,
            lockIdentifier = lockId,
        )

    val result =
        try {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = null,
                onRestoreRequest = {
                    println("RESTORE_REQUEST")
                    System.out.flush()
                },
            )
        } catch (e: Throwable) {
            println("ERROR:${e.javaClass.simpleName}:${e.message}")
            System.out.flush()
            System.exit(2)
            return
        }

    if (result) {
        println("SINGLE")
        System.out.flush()
        if (holdSeconds != null) {
            Thread.sleep(holdSeconds * 1000)
        } else {
            Thread.sleep(Long.MAX_VALUE)
        }
    } else {
        println("NOT_SINGLE")
        System.out.flush()
    }

    System.exit(if (result) 0 else 1)
}
