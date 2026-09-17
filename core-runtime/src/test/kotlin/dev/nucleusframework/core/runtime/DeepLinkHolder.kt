package dev.nucleusframework.core.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Subprocess helper that exercises [DeepLinkHandler.setHandler] against CLI args.
 *
 * Args: zero or more command-line tokens that may include a deep-link URI.
 *
 * Prints a single line to stdout:
 *   "URI:<uri>"  — callback was invoked with that URI
 *   "NONE"       — callback was never invoked (handler registered but no URI parsed)
 *   "ERROR:..."  — unexpected failure
 *
 * Exit codes: 0 = success (URI or NONE), 2 = error
 */
fun main(args: Array<String>) {
    val received = AtomicReference<String?>(null)
    val latch = CountDownLatch(1)

    try {
        DeepLinkHandler.setHandler(args) { uri ->
            received.set(uri.toString())
            latch.countDown()
        }

        // setHandler delivers cold-start args synchronously; brief wait for safety.
        latch.await(200, TimeUnit.MILLISECONDS)

        val uri = received.get()
        if (uri != null) {
            println("URI:$uri")
        } else {
            println("NONE")
        }
        System.out.flush()
        System.exit(0)
    } catch (e: Throwable) {
        println("ERROR:${e.javaClass.simpleName}:${e.message}")
        System.out.flush()
        System.exit(2)
    }
}
