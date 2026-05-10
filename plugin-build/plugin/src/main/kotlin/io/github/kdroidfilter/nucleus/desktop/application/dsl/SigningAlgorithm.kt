/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

/** Windows code-signing hash algorithm. */
enum class SigningAlgorithm(
    internal val id: String,
) {
    Sha1("sha1"),
    Sha256("sha256"),
    Sha512("sha512"),
}
