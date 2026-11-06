/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import java.security.MessageDigest

/**
 * Shared constants and helpers for the sandbox native-lib redirect pipeline
 * (issue #317): marker generation, sha256 hex hashing, manifest/shim naming.
 *
 * Used by [dev.nucleusframework.desktop.application.tasks.AbstractStripNativeLibsFromJarsTask]
 * at build time and exercised by tests. The runtime shim
 * (`dev.nucleusframework.sandbox.NucleusSandboxLoader`) mirrors the sha256-hex scheme and the
 * manifest filename — keep them in sync.
 */
internal object SandboxMarkers {
    /** Magic header written at the start of every marker file (first line). */
    const val MAGIC = "NUCLEUS-SANDBOX-MARKER"

    /** Bundled manifest filename, placed next to the extracted native libs in the app resources. */
    const val MANIFEST_FILENAME = "nucleus-sandbox-manifest.properties"

    /** Fixed name of the injected shim JAR on the app classpath. */
    const val SHIM_JAR_NAME = "nucleus-sandbox-shim.jar"

    /** Plugin resource path of the embedded shim JAR. */
    const val SHIM_RESOURCE_PATH = "/nucleus/sandbox/nucleus-sandbox-shim.jar"

    /** ASM owner of the runtime shim class that rewritten call sites route through. */
    const val SHIM_OWNER = "dev/nucleusframework/sandbox/NucleusSandboxLoader"

    /** Descriptor of the rewritten `load(String)` call. */
    const val LOAD_DESC = "(Ljava/lang/String;)V"

    private const val BYTE_MASK = 0xFF

    // Values below this print as a single hex digit and need a leading zero.
    private const val FIRST_TWO_DIGIT_HEX = 0x10

    /**
     * Builds the deterministic marker bytes that replace a native-lib entry inside a rewritten JAR.
     *
     * The marker is keyed on the (stable) mangled jar name and the entry path so it is unique per
     * (jar, entry) and reproducible across builds (the mangled name embeds the jar's md5 content
     * hash). At runtime the third-party loader extracts these exact bytes to a temp file and calls
     * `System.load`; the shim hashes that file and looks the hash up in the manifest.
     */
    fun markerBytes(
        jarMangledName: String,
        entryPath: String,
    ): ByteArray = "$MAGIC\njar=$jarMangledName\nentry=$entryPath\n".toByteArray(Charsets.UTF_8)

    /** Lowercase hex sha256, matching `NucleusSandboxLoader.sha256Hex`. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and BYTE_MASK
                if (v < FIRST_TWO_DIGIT_HEX) append('0')
                append(Integer.toHexString(v))
            }
        }
    }

    /** Flattened bundled filename for a native-lib entry path (matches `AbstractExtractNativeLibsTask`). */
    fun bundledLibName(entryPath: String): String = entryPath.substringAfterLast('/')
}
