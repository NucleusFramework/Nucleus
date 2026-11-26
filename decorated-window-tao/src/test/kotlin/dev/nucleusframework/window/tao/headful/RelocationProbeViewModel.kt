package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

/**
 * The `viewModel { }` each workspace fixture composes inside relocatable
 * content (a tab body, a satellite): a case reads the instance a host resolved
 * to tell a view model that followed its content from one that was rebuilt,
 * and [cleared] tells when its owner let it go.
 */
internal class RelocationProbeViewModel(
    val ownerId: String,
    private val cleared: MutableList<RelocationProbeViewModel>,
) : ViewModel() {
    override fun onCleared() {
        cleared += this
    }
}

/** The probe view models cleared over a run, in clearing order. */
internal fun clearedProbeViewModels(): MutableList<RelocationProbeViewModel> = mutableStateListOf()
