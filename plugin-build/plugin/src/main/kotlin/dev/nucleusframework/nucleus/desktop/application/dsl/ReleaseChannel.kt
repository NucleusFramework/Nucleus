/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

/** Update channel for auto-update publishing. */
enum class ReleaseChannel(
    internal val id: String,
) {
    Latest("latest"),
    Beta("beta"),
    Alpha("alpha"),
}
