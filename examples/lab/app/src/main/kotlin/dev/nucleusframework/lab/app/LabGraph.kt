package dev.nucleusframework.lab.app

import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.checks.CheckStore
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.environment.EnvironmentProvider
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

/** The whole Lab: every probe, fixture, gateway and ViewModel contributed to [AppScope]. */
@DependencyGraph(AppScope::class)
interface LabGraph : ViewModelGraph {
    @Multibinds(allowEmpty = true)
    val probes: Set<Probe>

    @Multibinds(allowEmpty = true)
    val fixtures: Set<Fixture>

    val timeline: Timeline
    val environment: EnvironmentProvider
    val checks: CheckStore
    val commands: LabCommands
    val sessions: SessionHost
}
