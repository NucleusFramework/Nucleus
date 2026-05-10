package dev.nucleusframework.nucleus.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File

/**
 * Patches the LC_BUILD_VERSION of a Mach-O binary using vtool.
 * Sets both the minimum deployment target (minos) and the SDK version,
 * enabling SDK-gated AppKit features (e.g. Liquid Glass on macOS 26+).
 *
 * @return true if the patch succeeded, false if vtool is missing or failed.
 */
internal fun patchMachOBuildVersion(
    binary: File,
    minVersion: String,
    sdkVersion: String,
    logger: Logger,
): Boolean {
    val vtool = File("/usr/bin/vtool")
    if (!vtool.exists()) {
        logger.warn(
            "vtool not found at /usr/bin/vtool — skipping macOS build version patch. " +
                "Install Xcode Command Line Tools to enable this feature.",
        )
        return false
    }

    logger.lifecycle("Patching ${binary.name} LC_BUILD_VERSION: minos=$minVersion sdk=$sdkVersion")

    // Remove existing code signature (vtool cannot modify signed binaries)
    ProcessBuilder("codesign", "--remove-signature", binary.absolutePath)
        .redirectErrorStream(true)
        .start()
        .waitFor()

    val vtoolExit =
        ProcessBuilder(
            vtool.absolutePath,
            "-set-build-version",
            "macos",
            minVersion,
            sdkVersion,
            "-tool",
            "ld",
            "0.0",
            "-replace",
            "-output",
            binary.absolutePath,
            binary.absolutePath,
        ).redirectErrorStream(true)
            .start()
            .waitFor()

    if (vtoolExit != 0) {
        logger.warn("vtool exited with code $vtoolExit for ${binary.name} — build version patch may have failed")
        return false
    }

    return true
}
