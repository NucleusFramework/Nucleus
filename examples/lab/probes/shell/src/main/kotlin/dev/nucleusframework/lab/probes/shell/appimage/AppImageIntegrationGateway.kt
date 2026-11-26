package dev.nucleusframework.lab.probes.shell.appimage

import dev.nucleusframework.core.runtime.AppImageIntegration
import dev.nucleusframework.core.runtime.AppImageIntegrationResult
import dev.nucleusframework.core.runtime.AppImageIntegrationStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Port over `core-runtime`'s [AppImageIntegration]. */
interface AppImageIntegrationGateway {
    fun status(): AppImageIntegrationStatus

    fun integrate(): AppImageIntegrationResult
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusAppImageIntegrationGateway : AppImageIntegrationGateway {
    private val integration = AppImageIntegration()

    override fun status(): AppImageIntegrationStatus = integration.status()

    override fun integrate(): AppImageIntegrationResult = integration.integrate()
}
