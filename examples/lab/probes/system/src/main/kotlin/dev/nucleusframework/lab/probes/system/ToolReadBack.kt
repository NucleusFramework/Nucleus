package dev.nucleusframework.lab.probes.system

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.process.runTool

/**
 * What an OS tool (`ps`, `pmset`, `lsof`, PowerShell…) says, used as an independent read-back
 * of what a Nucleus API claims to have done. [lines] is `null` when the tool could not answer,
 * and [note] then says why.
 */
@Immutable
data class ToolReadBack(
    val command: String,
    val lines: List<String>?,
    val note: String? = null,
) {
    companion object {
        fun unsupported(reason: String): ToolReadBack = ToolReadBack("—", null, reason)

        /**
         * Runs [command] (blocking: call it off the UI thread) and keeps the non-blank lines
         * [filter] accepts, trimmed. A timeout, a launch failure or a non-zero exit reads as
         * no answer, with the reason.
         */
        fun of(
            vararg command: String,
            timeoutMillis: Long = 5_000,
            filter: (String) -> Boolean = { true },
        ): ToolReadBack {
            val shown = command.joinToString(" ")
            val result = runTool(command.toList(), timeoutMillis)
            return when {
                result.error != null -> ToolReadBack(shown, null, result.error)
                result.exitCode != 0 -> {
                    val head =
                        result.output
                            .lineSequence()
                            .take(2)
                            .joinToString(" ")
                            .take(MAX_ERROR_CHARS)
                    ToolReadBack(shown, null, "exit ${result.exitCode}: $head")
                }
                else ->
                    ToolReadBack(
                        shown,
                        result.output
                            .lines()
                            .filter { it.isNotBlank() && filter(it) }
                            .map(String::trim),
                    )
            }
        }

        private const val MAX_ERROR_CHARS = 200
    }
}
