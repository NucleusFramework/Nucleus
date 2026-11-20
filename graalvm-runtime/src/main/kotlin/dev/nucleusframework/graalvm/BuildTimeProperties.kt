package dev.nucleusframework.graalvm

/**
 * System properties captured while `native-image` builds the image.
 *
 * `-D` options given to `native-image` exist only at build time: the running image does not see
 * them. This object is initialized at build time (see this module's `native-image.properties`), so
 * its fields keep the build-time values; on the JVM they simply read the current properties.
 */
internal object BuildTimeProperties {
    /** `-Djava.awt.headless=true` at build time, which `graalvm { headless = true }` passes. */
    @JvmField
    val headless: Boolean = System.getProperty(HEADLESS_PROPERTY).toBoolean()

    const val HEADLESS_PROPERTY: String = "java.awt.headless"
}
