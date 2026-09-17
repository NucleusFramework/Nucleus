package dev.nucleusframework.desktop.application.dsl

/**
 * GraalVM release channel used when the toolchain is auto-downloaded
 * (see [GraalvmToolchainSettings]).
 *
 * Both lines exist for either [GraalvmDistribution]:
 * - **Innovation** releases (e.g. `25i3`) — newest compiler and runtime features,
 *   short support window. Oracle GraalVM ships them via `gds.oracle.com`, Community
 *   Edition under the `graal-*` tags of `graalvm/graalvm-ce-builds`.
 * - **LTS** releases (e.g. `25`) — long-term support line updated with quarterly
 *   CPUs. Oracle GraalVM ships them via `download.oracle.com`, Community Edition
 *   under the `jdk-*` tags of `graalvm/graalvm-ce-builds`.
 */
enum class GraalvmChannel(
    val defaultVersion: String,
) {
    /** Latest innovation release. This is the default channel. */
    INNOVATION("25i3"),

    /** Latest long-term-support release. */
    LTS("25"),
}
