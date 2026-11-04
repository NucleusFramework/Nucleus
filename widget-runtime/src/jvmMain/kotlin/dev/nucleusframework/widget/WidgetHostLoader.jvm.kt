@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget

import java.util.ServiceLoader

// A ServiceLoader lookup, not reflection: native-image keeps META-INF/services providers
// (graalvm-runtime includes them), the same way Tao's Dispatchers.Main is installed.
internal actual fun loadWidgetHost(): WidgetHost? =
    ServiceLoader.load(WidgetHost::class.java, WidgetHost::class.java.classLoader).firstOrNull { it.isSupported }
