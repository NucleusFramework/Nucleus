/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action

/**
 * Single entry point for code signing across all platforms, for distribution outside of a store.
 *
 * It is a thin facade over the per-platform settings — `macOS { signing { } }`,
 * `windows { signing { } }` and `linux { signing { } }` — operating on the very same
 * instances, so both styles can be mixed freely. All values default from
 * `compose.desktop.*` Gradle properties / environment variables, so CI only needs to set
 * those and toggle the per-platform `enabled`/`sign` flag.
 *
 * ```kotlin
 * nativeDistributions {
 *     signing {
 *         macOS   { sign.set(true); identity.set("Developer ID Application: …") }
 *         windows { enabled = true; certificateFile.set(file("cert.pfx")) }
 *         linux   { enabled.set(true); keyId.set("ABCD1234…") }
 *     }
 * }
 * ```
 */
class UnifiedSigningSettings(
    private val macOSSettings: MacOSSigningSettings,
    private val windowsSettings: WindowsSigningSettings,
    private val linuxSettings: LinuxSigningSettings,
) {
    /** Configures macOS code signing (same instance as `macOS { signing { } }`). */
    fun macOS(fn: Action<MacOSSigningSettings>) {
        fn.execute(macOSSettings)
    }

    /** Configures Windows code signing (same instance as `windows { signing { } }`). */
    fun windows(fn: Action<WindowsSigningSettings>) {
        fn.execute(windowsSettings)
    }

    /** Configures Linux package signing (same instance as `linux { signing { } }`). */
    fun linux(fn: Action<LinuxSigningSettings>) {
        fn.execute(linuxSettings)
    }
}
