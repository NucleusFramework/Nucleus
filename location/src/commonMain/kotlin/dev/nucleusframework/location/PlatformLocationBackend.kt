package dev.nucleusframework.location

/** The platform's backend: the Rust JNI bridge on desktop, the OS API on Android and iOS. */
internal expect fun platformLocationBackend(): LocationBackend
