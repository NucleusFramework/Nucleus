package io.github.kdroidfilter.nucleus.core.runtime

import java.nio.file.Paths

/**
 * Minimal process launched by [SingleInstanceIntegrationTest] to exercise
 * [SingleInstanceManager] in a real multi-process scenario.
 *
 * Usage:  java ... SingleInstanceTestHarnessKt <lockDir> <lockId> <holdSeconds>
 *
 * Exit codes:
 *   0 – primary instance (lock acquired)
 *   1 – secondary instance (lock NOT acquired)
 *   2 – error
 *
 * Stdout protocol (one tag per line):
 *   LOCK_ACQUIRED   – this process is the primary instance
 *   LOCK_DENIED     – another instance holds the lock
 *   READY           – primary instance is fully initialised and holding the lock
 *   RESTORE_REQUEST – primary instance received a restore request
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("Usage: <lockDir> <lockId> <holdSeconds>")
        System.exit(2)
    }

    val lockDir = Paths.get(args[0])
    val lockId = args[1]
    val holdSeconds = args[2].toLongOrNull() ?: run {
        System.err.println("Invalid holdSeconds: ${args[2]}")
        System.exit(2)
        return
    }

    SingleInstanceManager.configuration = SingleInstanceManager.Configuration(
        lockFilesDir = lockDir,
        lockIdentifier = lockId,
    )

    val isPrimary = SingleInstanceManager.isSingleInstance(
        onRestoreRequest = {
            println("RESTORE_REQUEST")
            System.out.flush()
        },
    )

    if (isPrimary) {
        println("LOCK_ACQUIRED")
        println("READY")
        System.out.flush()
        // Hold the lock for the requested duration
        Thread.sleep(holdSeconds * 1000)
    } else {
        println("LOCK_DENIED")
        System.out.flush()
    }

    System.exit(if (isPrimary) 0 else 1)
}
