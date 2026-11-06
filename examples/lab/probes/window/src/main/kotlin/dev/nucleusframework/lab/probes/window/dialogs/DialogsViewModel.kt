package dev.nucleusframework.lab.probes.window.dialogs

import androidx.lifecycle.ViewModel
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.withFileKitDialogSettings
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.delay

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class DialogsViewModel(
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<DialogsState, DialogsIntent, DialogsEvent, Nothing>(
        DialogsState(),
        DialogsReducer,
        timeline,
        DialogsProbe.ID,
    ) {
    private val sessions = sessions(host)
    private var owner: NucleusWindow? = null

    init {
        launch { sessions.isOpen().collect { dispatch(DialogsEvent.SessionChanged(it)) } }
    }

    override suspend fun handle(intent: DialogsIntent) {
        when (intent) {
            DialogsIntent.Open ->
                sessions.open("Dialogs lab") { close -> DialogsOwnerWindow(this@DialogsViewModel, close) }
            DialogsIntent.Close -> sessions.close()
            is DialogsIntent.Show ->
                if (intent.secondary !in state.value.open) dispatch(DialogsEvent.Shown(intent.secondary))
            is DialogsIntent.Dismiss -> {
                if (intent.secondary !in state.value.open) return
                dispatch(DialogsEvent.Dismissed(intent.secondary))
                if (intent.secondary.modal) {
                    // The modal teardown hands focus back to the owner; give it a moment, then ask the OS.
                    delay(FOCUS_SETTLE_MS)
                    owner?.let {
                        dispatch(
                            DialogsEvent.FocusAfterClose(it.isFocused),
                            if (it.isFocused) Severity.Info else Severity.Warning,
                        )
                    }
                }
            }
            is DialogsIntent.Reported ->
                if (state.value.reports[intent.secondary] != intent.report) {
                    dispatch(DialogsEvent.Reported(intent.secondary, intent.report))
                }
            DialogsIntent.LabHostInvoked -> dispatch(DialogsEvent.LabHostInvoked)
            DialogsIntent.OwnerPressed ->
                dispatch(DialogsEvent.OwnerPressed, if (state.value.modalOpen) Severity.Error else Severity.Info)
            is DialogsIntent.Pick -> pick(intent.pick)
            is DialogsIntent.Attached -> owner = intent.window
        }
    }

    /** Runs a FileKit dialog parented to the owner window, timing it. */
    private suspend fun pick(pick: FilePick) {
        val window = owner ?: return
        var parented = false
        val (result, millis) =
            timedMillis {
                runCatching {
                    window.withFileKitDialogSettings { settings ->
                        parented = settings.parent != null
                        when (pick) {
                            FilePick.OpenFile -> FileKit.openFilePicker(dialogSettings = settings)?.path
                            FilePick.OpenImages ->
                                FileKit
                                    .openFilePicker(
                                        type = FileKitType.Image,
                                        mode = FileKitMode.Multiple(),
                                        dialogSettings = settings,
                                    )?.joinToString { it.path }
                            FilePick.Directory -> FileKit.openDirectoryPicker(dialogSettings = settings)?.path
                            FilePick.Save ->
                                FileKit
                                    .openFileSaver(
                                        suggestedName = "lab-report",
                                        extension = "md",
                                        dialogSettings = settings,
                                    )?.path
                        }
                    }
                }
            }
        val text = result.fold(onSuccess = { it ?: "(cancelled)" }, onFailure = { it.summary })
        dispatch(
            DialogsEvent.Picked(
                PickResult(System.currentTimeMillis(), pick, text, parented, millis, ok = result.isSuccess),
            ),
            if (result.isFailure) Severity.Error else Severity.Info,
        )
    }

    private companion object {
        const val FOCUS_SETTLE_MS = 400L
    }
}
