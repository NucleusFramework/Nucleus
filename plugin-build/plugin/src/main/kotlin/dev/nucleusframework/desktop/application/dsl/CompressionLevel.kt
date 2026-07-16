/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

/** Archive compression level for electron-builder output. */
enum class CompressionLevel(
    internal val id: String,
) {
    Store("store"),
    Normal("normal"),
    Maximum("maximum"),

    /**
     * Like [Maximum] for every format electron-builder handles natively (its `id` maps to
     * `"maximum"`), but additionally enables the plugin's post-processing recompression steps that
     * electron-builder's own `maximum` leaves on the table: LZMA (ULMO) for DMG and `xz -9e` for DEB.
     * These steps are slower and are therefore opt-in via this level rather than [Maximum].
     */
    Ultra("maximum"),
}
