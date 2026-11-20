package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Link
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.UnsupportedHere

/**
 * One ViewModelStore per probe (and per reset generation), kept while the user browses
 * elsewhere: going back to a probe finds its state and its OS subscriptions as they were.
 */
private class ProbeStores {
    private val stores = mutableMapOf<ProbeId, Pair<Int, ViewModelStoreOwner>>()

    fun ownerFor(
        id: ProbeId,
        generation: Int,
    ): ViewModelStoreOwner {
        val existing = stores[id]
        if (existing != null && existing.first == generation) return existing.second
        existing?.second?.viewModelStore?.clear()
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        stores[id] = generation to owner
        return owner
    }
}

@Composable
fun ProbeHost(
    probe: Probe,
    generation: Int,
    shell: ShellViewModel,
) {
    val stores = remember { ProbeStores() }
    val saveable = rememberSaveableStateHolder()
    val descriptor = probe.descriptor
    if (!descriptor.supportsCurrentPlatform) {
        UnsupportedHere("${descriptor.title} runs on ${descriptor.platforms.joinToString { it.name }} only.")
        return
    }
    key(descriptor.id, generation) {
        saveable.SaveableStateProvider("${descriptor.id}#$generation") {
            CompositionLocalProvider(LocalViewModelStoreOwner provides stores.ownerFor(descriptor.id, generation)) {
                CompositionLocalProvider(LocalShell provides shell) {
                    probe.Content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeHeader(
    descriptor: ProbeDescriptor,
    shell: ShellViewModel,
) {
    val colors = LabTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(descriptor.title, style = LabTheme.typography.title, modifier = Modifier.weight(1f))
            Link("Copy link") { shell.onIntent(ShellIntent.CopyDeepLink(descriptor.id)) }
            Link("Copy report") { shell.onIntent(ShellIntent.CopyReport(descriptor.id)) }
            Link("Reset") { shell.onIntent(ShellIntent.ResetProbe(descriptor.id)) }
        }
        Text(descriptor.summary, color = colors.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(descriptor.id.value, style = LabTheme.typography.mono, color = colors.accent)
            descriptor.modules.forEach { Text(":$it", style = LabTheme.typography.mono, color = colors.textMuted) }
        }
    }
}
