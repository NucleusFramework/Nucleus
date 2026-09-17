package dev.nucleusframework.core.runtime

/**
 * Subprocess helper that exercises [DeepLinkHandler.captureFromArgs] — the path
 * used by secondary single-instance launches before [DeepLinkHandler.writeUriTo].
 *
 * Args: zero or more command-line tokens that may include a deep-link URI.
 *
 * Prints a single line to stdout:
 *   "URI:<uri>"  — [DeepLinkHandler.uri] was set
 *   "NONE"       — no URI was captured
 *   "ERROR:..."  — unexpected failure
 */
fun main(args: Array<String>) {
    try {
        DeepLinkHandler.captureFromArgs(args)
        val uri = DeepLinkHandler.uri
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
