package dev.nucleusframework.lab.core.checks

import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.environment.EnvironmentProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
enum class CheckStatus { Untested, Pass, Fail, Skipped }

@Serializable
data class CheckResult(
    val status: CheckStatus = CheckStatus.Untested,
    val note: String = "",
    val epochMillis: Long = 0,
)

/** fingerprint → probe id → check id → result. */
@Serializable
data class CheckBook(
    val results: Map<String, Map<String, Map<String, CheckResult>>> = emptyMap(),
)

/**
 * Manual check results, persisted per environment fingerprint so a run on another
 * OS, display server or package format starts from a clean sheet.
 */
@SingleIn(AppScope::class)
@Inject
class CheckStore(
    private val environment: EnvironmentProvider,
) {
    private val file: Path = LabPaths.dataDir.resolve("checks.json")
    private val state = MutableStateFlow(load())
    val book: StateFlow<CheckBook> = state.asStateFlow()

    private val fingerprint: String get() = environment.snapshot.value.fingerprint

    fun resultsFor(
        book: CheckBook,
        probe: ProbeId,
    ): Map<String, CheckResult> = book.results[fingerprint]?.get(probe.value).orEmpty()

    fun set(
        probe: ProbeId,
        checkId: String,
        status: CheckStatus,
        note: String? = null,
    ) {
        state.update { book ->
            val env = book.results[fingerprint].orEmpty()
            val probeResults = env[probe.value].orEmpty()
            val previous = probeResults[checkId] ?: CheckResult()
            val next =
                previous.copy(
                    status = status,
                    note = note ?: previous.note,
                    epochMillis = System.currentTimeMillis(),
                )
            book.copy(results = book.results + (fingerprint to env + (probe.value to probeResults + (checkId to next))))
        }
        save()
    }

    fun reset(probe: ProbeId) {
        state.update { book ->
            val env = book.results[fingerprint].orEmpty()
            book.copy(results = book.results + (fingerprint to env - probe.value))
        }
        save()
    }

    private fun load(): CheckBook =
        runCatching { if (file.exists()) json.decodeFromString(CheckBook.serializer(), file.readText()) else null }
            .onFailure { logger.log(Level.WARNING, "Unreadable $file, starting from scratch", it) }
            .getOrNull() ?: CheckBook()

    private fun save() {
        runCatching { file.writeText(json.encodeToString(CheckBook.serializer(), state.value)) }
            .onFailure { logger.log(Level.WARNING, "Could not write $file", it) }
    }

    private companion object {
        val json = Json { prettyPrint = true }
        val logger: Logger = Logger.getLogger(CheckStore::class.java.name)
    }
}
