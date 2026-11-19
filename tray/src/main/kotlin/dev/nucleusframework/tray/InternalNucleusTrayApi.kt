package dev.nucleusframework.tray

/**
 * Marks tray internals shared with the `tray-app` module (the tray engine, icon rendering and the
 * icon's on-screen position). Not part of the supported API: it can change in any release.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal Nucleus tray API, shared with the tray-app module.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class InternalNucleusTrayApi
