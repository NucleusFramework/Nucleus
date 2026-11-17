package dev.nucleusframework.tray.internal.linux

/**
 * Concrete Runnable wrapper for JNI callbacks.
 * GraalVM native-image cannot resolve run() on SAM-converted lambda classes
 * because they are dynamically generated and not registered for JNI access.
 * This wrapper provides a known, statically registered class.
 */
internal class JniRunnable(private val action: () -> Unit) : Runnable {
    override fun run() = action()
}
