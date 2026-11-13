@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.concurrent.Volatile

/**
 * The platform host on iOS, which has no `ServiceLoader`: `widget-ios` installs its host here before
 * the app (or its widget extension) calls [NucleusWidgets.start].
 */
@InternalNucleusWidgetApi
public object WidgetHostRegistry {
    @Volatile
    internal var installed: WidgetHost? = null

    /** Makes [host] the one [NucleusWidgets.start] connects to. */
    public fun install(host: WidgetHost) {
        installed = host
    }
}

internal actual fun loadWidgetHost(): WidgetHost? = WidgetHostRegistry.installed?.takeIf { it.isSupported }

private const val MILLIS_PER_SECOND = 1000.0

internal actual fun currentEpochMillis(): Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
