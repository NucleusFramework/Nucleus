package dev.nucleusframework.lab.probes.input.dnd

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class DndViewModel(
    timeline: Timeline,
) : MviViewModel<DndState, DndIntent, DndEvent, Nothing>(DndState(), DndReducer, timeline, DndProbe.ID) {
    /** Drag callbacks come from the platform bridge: stamped where they land. Moves stay out of the timeline. */
    fun onPhase(phase: DragPhase) {
        val event = DndEvent.Phase(phase)
        if (phase == DragPhase.Moved) reduceSilently(event) else dispatch(event.stamped())
    }

    /** Reads [transferable] completely, on the drop callback's thread, as a real app would. */
    fun onDrop(transferable: Transferable?) {
        if (transferable == null) {
            dispatch(
                DndEvent.Dropped(emptyList(), emptyList(), null, emptyList(), "no AWT transferable"),
                Severity.Error,
            )
            return
        }
        val event = read(transferable)
        dispatch(event.stamped(), if (event.error != null) Severity.Error else Severity.Info)
    }

    fun onExported(
        kind: String,
        action: String,
    ) = dispatch(DndEvent.Exported(kind, action))

    override suspend fun handle(intent: DndIntent) {
        when (intent) {
            DndIntent.Reset -> dispatch(DndEvent.Cleared)
        }
    }

    private fun read(transferable: Transferable): DndEvent.Dropped {
        val flavors = runCatching { transferable.transferDataFlavors.toList() }.getOrDefault(emptyList())
        val errors = mutableListOf<String>()

        fun <T> attempt(
            flavor: DataFlavor,
            block: (Any) -> T,
        ): T? =
            if (!transferable.isDataFlavorSupported(flavor)) {
                null
            } else {
                runCatching { block(transferable.getTransferData(flavor)) }
                    .onFailure {
                        errors += "${flavor.mimeType.substringBefore(';')}: ${it.summary}"
                    }.getOrNull()
            }
        val files =
            attempt(DataFlavor.javaFileListFlavor) { data ->
                (data as List<*>).map {
                    (it as File).absolutePath
                }
            }.orEmpty()
        val text = attempt(DataFlavor.stringFlavor) { it as String }
        val uriFlavor =
            flavors.firstOrNull {
                it.mimeType.startsWith("text/uri-list") &&
                    it.representationClass == String::class.java
            }
        val uris =
            uriFlavor
                ?.let { flavor ->
                    attempt(flavor) {
                        (it as String).lines().filter { l ->
                            l.isNotBlank() &&
                                !l.startsWith("#")
                        }
                    }
                }.orEmpty()
        return DndEvent.Dropped(
            mimeTypes = flavors.map { it.mimeType.substringBefore(';') }.distinct(),
            files = files,
            text = text,
            uris = uris,
            error = errors.joinToString("; ").ifEmpty { null },
        )
    }
}
