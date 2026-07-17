package dev.nucleusframework.desktop.application.dsl

/**
 * Oracle GraalVM release channel used when the toolchain is auto-downloaded
 * (see [GraalvmToolchainSettings]).
 *
 * Oracle publishes two lines of Oracle GraalVM (the former Enterprise Edition):
 * - **Innovation** releases (e.g. `25i1`) — newest compiler and runtime features,
 *   short support window, distributed via `gds.oracle.com`.
 * - **LTS** releases (e.g. `25`) — long-term support line updated with quarterly
 *   CPUs, distributed via `download.oracle.com`.
 */
enum class GraalvmChannel(
    val defaultVersion: String,
) {
    /** Latest Oracle GraalVM innovation release. This is the default channel. */
    INNOVATION("25i1"),

    /** Latest Oracle GraalVM long-term-support release. */
    LTS("25"),
}
