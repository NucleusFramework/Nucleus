package dev.nucleusframework.lab.core

/**
 * Work that must run when the Lab process starts, before any UI exists — typically answering an
 * OS activation the process was launched for (a COM class object the system waits on). Contributed
 * with `@ContributesIntoSet(AppScope::class)`; `main` runs every one of them, but not in a fixture.
 */
fun interface LabStartup {
    fun start(args: Array<String>)
}
