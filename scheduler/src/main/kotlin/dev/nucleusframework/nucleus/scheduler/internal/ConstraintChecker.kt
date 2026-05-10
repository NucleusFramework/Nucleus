package dev.nucleusframework.nucleus.scheduler.internal

import dev.nucleusframework.nucleus.scheduler.Constraints
import dev.nucleusframework.nucleus.scheduler.InternalSchedulerApi

/**
 * Result of evaluating [Constraints] against the current system state.
 *
 * @property satisfied `true` if all constraints are met
 * @property unsatisfied names of constraints that are not met (empty when [satisfied])
 */
@InternalSchedulerApi
public data class ConstraintResult(
    val satisfied: Boolean,
    val unsatisfied: Set<String>,
)

/**
 * Evaluates [Constraints] against the current system state.
 *
 * The production implementation ([SystemInfoConstraintChecker]) uses the `system-info`
 * module. Tests can supply a fake via [DesktopBootReceiver.setTestConstraintChecker].
 */
@InternalSchedulerApi
public interface ConstraintChecker {
    fun check(constraints: Constraints): ConstraintResult
}
