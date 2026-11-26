package dev.nucleusframework.lab.probes.rendering.webview

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class WebViewViewModel(
    timeline: Timeline,
) : MviViewModel<WebProbeState, WebProbeIntent, WebProbeEvent, WebProbeEffect>(
        WebProbeState(),
        WebProbeReducer,
        timeline,
        WebViewProbe.ID,
    ) {
    private var scheduledPopup: Job? = null

    init {
        // The view loads its first page as soon as it exists: time that one too.
        dispatch(WebProbeEvent.NavigationRequested(state.value.page, System.currentTimeMillis()))
        // NUCLEUS_DEMO_AUTOPOPUP_MS / -Dlab.webview.autoPopupMs: the tao-demo automation hook.
        val autoPopup =
            (System.getProperty("lab.webview.autoPopupMs") ?: System.getenv("NUCLEUS_DEMO_AUTOPOPUP_MS"))
                ?.toLongOrNull()
        if (autoPopup != null) schedulePopup(autoPopup)
    }

    override suspend fun handle(intent: WebProbeIntent) {
        when (intent) {
            is WebProbeIntent.EditUrl -> reduceSilently(WebProbeEvent.UrlEdited(intent.text))
            WebProbeIntent.SubmitUrl -> navigate(WebPage.Remote(normalize(state.value.urlInput)))
            is WebProbeIntent.OpenPreset -> navigate(intent.page)
            WebProbeIntent.Back -> emit(WebProbeEffect.Back)
            WebProbeIntent.Forward -> emit(WebProbeEffect.Forward)
            WebProbeIntent.Reload -> {
                dispatch(WebProbeEvent.NavigationRequested(state.value.page, System.currentTimeMillis()))
                emit(WebProbeEffect.Reload)
            }
            WebProbeIntent.TogglePopup -> dispatch(WebProbeEvent.PopupChanged(!state.value.popupShown))
            is WebProbeIntent.SchedulePopup -> schedulePopup(intent.delayMillis)
        }
    }

    /** Mirrors the composition-owned WebView state; only finished loads reach the timeline. */
    fun report(report: WebReport) {
        val before = state.value
        reduceSilently(WebProbeEvent.Reported(report, System.currentTimeMillis()))
        val after = state.value
        if (after.navigations.size != before.navigations.size ||
            after.navigations.lastOrNull() != before.navigations.lastOrNull()
        ) {
            after.navigations.lastOrNull()?.let {
                timeline.record(source, EntryKind.Event, "Loaded ${it.loadedUrl ?: it.target} in ${it.millis} ms")
            }
        }
        if (report.errors > before.errors) {
            timeline.record(
                source,
                EntryKind.Event,
                "WebView reported ${report.errors - before.errors} error(s)",
                Severity.Warning,
            )
        }
    }

    private suspend fun navigate(page: WebPage) {
        dispatch(WebProbeEvent.NavigationRequested(page, System.currentTimeMillis()))
        emit(WebProbeEffect.Load(page))
    }

    private fun schedulePopup(delayMillis: Long) {
        scheduledPopup?.cancel()
        dispatch(WebProbeEvent.PopupScheduled(delayMillis))
        scheduledPopup =
            launch {
                delay(delayMillis)
                dispatch(WebProbeEvent.PopupChanged(true))
            }
    }

    private fun normalize(input: String): String = input.trim().let { if ("://" in it) it else "https://$it" }
}
