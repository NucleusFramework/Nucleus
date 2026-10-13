package dev.nucleusframework.lab.probes.system.appearance

import dev.nucleusframework.darkmodedetector.NoopDarkModeDetector
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stampedCallbackFlow
import dev.nucleusframework.systemcolor.isSystemAccentColorSupported
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import java.util.function.Consumer

/** Port over `darkmode-detector` and `system-color`. */
interface AppearanceGateway {
    fun darkModeDetector(): Availability

    fun accentSupport(): Availability

    fun isDark(): Boolean

    /** Pushes from the OS listener, stamped on the thread they arrive on. */
    fun darkModeChanges(): Flow<Stamped<Boolean>>
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusAppearanceGateway : AppearanceGateway {
    private val detector by lazy { getPlatformDarkModeDetector() }

    override fun darkModeDetector(): Availability =
        Availability.of(detector !== NoopDarkModeDetector) { "no detector for this platform" }

    override fun accentSupport(): Availability = Availability.catching { isSystemAccentColorSupported() }

    override fun isDark(): Boolean = detector.isDark()

    override fun darkModeChanges(): Flow<Stamped<Boolean>> =
        stampedCallbackFlow {
            val listener = Consumer<Boolean> { emit(it) }
            detector.registerListener(listener)
            onClose { detector.removeListener(listener) }
        }
}
