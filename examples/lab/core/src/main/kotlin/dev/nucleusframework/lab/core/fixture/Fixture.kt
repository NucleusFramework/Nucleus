package dev.nucleusframework.lab.core.fixture

import androidx.compose.runtime.Immutable

/**
 * A program that must own its process — it exits on failure, takes the main thread
 * without Compose, or needs JVM flags the Lab itself must not run with. Contributed with
 * `@ContributesIntoSet(AppScope::class)`; the Lab relaunches itself with
 * `-Dlab.fixture=<id>` and `main` hands the process to [run].
 */
interface Fixture {
    val id: String
    val title: String

    /** What a pass looks like, so the tester can judge the run. */
    val description: String

    /** Named flag sets the fixture can be started with; the first is the default. */
    val variants: List<FixtureVariant> get() = listOf(FixtureVariant("default"))

    /** Exit codes that mean something beyond 0 = pass. */
    val exitCodes: Map<Int, String> get() = emptyMap()

    /** Runs in the child process; may never return. */
    fun run(args: Array<String>)
}

@Immutable
data class FixtureVariant(
    val name: String,
    val jvmArgs: List<String> = emptyList(),
    val args: List<String> = emptyList(),
)

const val FIXTURE_PROPERTY: String = "lab.fixture"
