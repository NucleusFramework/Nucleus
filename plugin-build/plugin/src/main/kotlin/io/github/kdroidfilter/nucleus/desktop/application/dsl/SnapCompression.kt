/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

/** Snap package compression algorithm. */
enum class SnapCompression(
    internal val id: String,
) {
    Xz("xz"),
    Lzo("lzo"),
}
