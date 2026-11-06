/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action
import java.util.*

/** Distribution settings of a Kotlin/Native application; only [TargetFormat.Dmg] is supported. */
abstract class NativeApplicationDistributions : AbstractDistributions() {
    private val supportedFormats = EnumSet.of(TargetFormat.Dmg)

    override fun targetFormats(vararg formats: TargetFormat) {
        val unsupportedFormats = formats.filter { it !in supportedFormats }
        if (unsupportedFormats.isNotEmpty()) {
            error(
                "nativeApplication.distributions.targetFormats " +
                    "does not support the following formats: " +
                    unsupportedFormats.joinToString(", "),
            )
        }
        super.targetFormats(*formats)
    }

    val macOS: NativeApplicationMacOSPlatformSettings =
        objects.newInstance(NativeApplicationMacOSPlatformSettings::class.java)

    /** Configures macOS packaging. */
    open fun macOS(fn: Action<NativeApplicationMacOSPlatformSettings>) {
        fn.execute(macOS)
    }
}
