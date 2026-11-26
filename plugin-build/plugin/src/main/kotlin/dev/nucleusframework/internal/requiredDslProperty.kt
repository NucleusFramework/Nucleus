/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.internal

import kotlin.reflect.KProperty

internal fun <T : Any> requiredDslProperty(missingMessage: String) = RequiredPropertyDelegate<T>(missingMessage)

/**
 * Property delegate for a DSL value that must be set before it is read:
 * reading it while unset fails with [missingMessage].
 */
class RequiredPropertyDelegate<T>(
    val missingMessage: String,
) {
    var realValue: T? = null

    /** Stores [newValue]. */
    operator fun setValue(
        ref: Any,
        property: KProperty<*>,
        newValue: T,
    ) {
        realValue = newValue
    }

    /** Returns the stored value, or fails with [missingMessage] when none was set. */
    operator fun getValue(
        ref: Any,
        property: KProperty<*>,
    ): T = realValue ?: error(missingMessage)
}
