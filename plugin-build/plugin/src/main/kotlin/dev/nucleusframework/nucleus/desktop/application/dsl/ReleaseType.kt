/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

/** GitHub release type for publishing. */
enum class ReleaseType(
    internal val id: String,
) {
    Release("release"),
    Draft("draft"),
    Prerelease("prerelease"),
}
