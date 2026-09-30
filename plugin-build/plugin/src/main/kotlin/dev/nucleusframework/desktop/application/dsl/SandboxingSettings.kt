/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

/**
 * Sandboxed (store) distribution settings, scoped under `nativeDistributions { sandboxing { ... } }`.
 *
 * Active only when at least one store target format is configured and compatible with the current
 * OS — the same trigger as the rest of the sandboxed pipeline. Those are [TargetFormat.AppX],
 * [TargetFormat.Flatpak], and [TargetFormat.Pkg] **only when it targets the Mac App Store**
 * (`macOS { pkg { appStore = true } }`, the default). A Developer ID PKG is built like a DMG, so
 * nothing here applies to it.
 *
 * The sandboxed pipeline replaces native libs inside dependency JARs with markers and rewrites
 * `System.load(String)` / `Runtime.load(String)` call sites to a runtime shim that loads the
 * signed bundled copy (issue #317). Most extract-and-load libraries (e.g. zstd-kmp) need no
 * configuration here. The options below cover the documented edge cases.
 */
abstract class SandboxingSettings {
    /**
     * JAR simple-name substrings whose native libraries must be kept verbatim (no marker, no
     * call-site rewrite) — an escape hatch for loaders that validate the extracted content
     * (magic bytes / checksums) and would reject a marker file.
     *
     * Note: keeping the real lib in the JAR means `System.load(tempExtractedRealLib)` still runs
     * as-is; on macOS App Store this can still fail library validation. Prefer upstream support
     * for an external library path when available; this knob only prevents the marker crash and
     * is best-effort for non-macOS store sandboxes.
     *
     * Example: `sandboxing { keepNativeLibsInJars("some-validating-lib") }`
     */
    val keepNativeLibsInJars: MutableList<String> = mutableListOf()

    /** Adds one or more JAR name substrings to [keepNativeLibsInJars]. */
    fun keepNativeLibsInJars(vararg substrings: String) {
        keepNativeLibsInJars.addAll(substrings.toList())
    }
}
