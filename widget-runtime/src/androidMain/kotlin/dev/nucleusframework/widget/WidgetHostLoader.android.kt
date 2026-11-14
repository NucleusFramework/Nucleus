@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget

import dev.nucleusframework.widget.android.AndroidWidgetHost
import dev.nucleusframework.widget.android.WidgetContextProvider

// No ServiceLoader on Android: the host is part of this module and only needs the application
// context WidgetContextProvider captured at process start.
internal actual fun loadWidgetHost(): WidgetHost? = WidgetContextProvider.application?.let(::AndroidWidgetHost)

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()
