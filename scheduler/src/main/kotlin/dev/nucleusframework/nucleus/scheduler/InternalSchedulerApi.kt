package dev.nucleusframework.nucleus.scheduler

/**
 * Marks declarations that are internal to the scheduler infrastructure
 * and should not be used by application code.
 *
 * The `scheduler-testing` module uses these APIs to swap the platform backend
 * with an in-memory implementation.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal scheduler API. Use the scheduler-testing module instead.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class InternalSchedulerApi
