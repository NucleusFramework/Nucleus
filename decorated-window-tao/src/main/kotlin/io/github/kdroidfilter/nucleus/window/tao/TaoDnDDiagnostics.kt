package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Stage 0 visibility helper — exposes counters and a recent-events log to
 * the sample so we can verify the Compose DnD plumbing without relying on
 * stdout (which gradle's run task buffers and IDEs filter).
 */
object TaoDnDDiagnostics {
    val constructed = mutableIntStateOf(0)
    val isRequiredQueries = mutableIntStateOf(0)
    val requests = mutableIntStateOf(0)
    val transfers = mutableIntStateOf(0)
    val lastMessage = mutableStateOf<String?>(null)

    fun log(msg: String) {
        lastMessage.value = msg
        System.err.println("[TaoDnD] $msg")
        System.err.flush()
    }
}
