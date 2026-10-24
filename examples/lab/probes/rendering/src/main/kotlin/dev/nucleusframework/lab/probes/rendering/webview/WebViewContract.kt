package dev.nucleusframework.lab.probes.rendering.webview

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer

/** One finished navigation, timed from the request to `LoadingState.Finished`. */
@Immutable
data class NavigationRecord(
    val target: String,
    val loadedUrl: String?,
    val millis: Long,
)

@Immutable
data class WebProbeState(
    val page: WebPage = WebPage.Presets.first(),
    val urlInput: String = (WebPage.Presets.first() as WebPage.Remote).url,
    val loading: Boolean = false,
    val progress: Float = 0f,
    val lastLoadedUrl: String? = null,
    val title: String? = null,
    val errors: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val popupShown: Boolean = false,
    /** Milliseconds until the scheduled popup opens; `null` when none is pending. */
    val popupPendingMillis: Long? = null,
    val requestedAtMillis: Long? = null,
    val navigations: List<NavigationRecord> = emptyList(),
)

sealed interface WebProbeIntent {
    data class EditUrl(
        val text: String,
    ) : WebProbeIntent

    data object SubmitUrl : WebProbeIntent

    data class OpenPreset(
        val page: WebPage,
    ) : WebProbeIntent

    data object Back : WebProbeIntent

    data object Forward : WebProbeIntent

    data object Reload : WebProbeIntent

    data object TogglePopup : WebProbeIntent

    /** Opens the popup after [delayMillis]: glyph uploads after the view's compositor started. */
    data class SchedulePopup(
        val delayMillis: Long,
    ) : WebProbeIntent
}

/** What the WebView's own state reports, mirrored from the composition. */
@Immutable
data class WebReport(
    val loading: Boolean,
    val progress: Float,
    val lastLoadedUrl: String?,
    val title: String?,
    val errors: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

sealed interface WebProbeEvent {
    data class UrlEdited(
        val text: String,
    ) : WebProbeEvent

    data class NavigationRequested(
        val page: WebPage,
        val atMillis: Long,
    ) : WebProbeEvent

    data class Reported(
        val report: WebReport,
        val atMillis: Long,
    ) : WebProbeEvent

    data class PopupChanged(
        val shown: Boolean,
    ) : WebProbeEvent

    data class PopupScheduled(
        val delayMillis: Long?,
    ) : WebProbeEvent
}

/** Imperative calls on the composition-owned navigator / state. */
sealed interface WebProbeEffect {
    data class Load(
        val page: WebPage,
    ) : WebProbeEffect

    data object Back : WebProbeEffect

    data object Forward : WebProbeEffect

    data object Reload : WebProbeEffect
}

object WebProbeReducer : Reducer<WebProbeState, WebProbeEvent> {
    private const val HISTORY = 10

    override fun reduce(
        state: WebProbeState,
        event: WebProbeEvent,
    ): WebProbeState =
        when (event) {
            is WebProbeEvent.UrlEdited -> state.copy(urlInput = event.text)
            is WebProbeEvent.NavigationRequested ->
                state.copy(
                    page = event.page,
                    urlInput = (event.page as? WebPage.Remote)?.url ?: state.urlInput,
                    requestedAtMillis = event.atMillis,
                )
            is WebProbeEvent.Reported -> {
                val report = event.report
                val finished = state.loading && !report.loading
                val record =
                    state.requestedAtMillis
                        ?.takeIf { finished }
                        ?.let { NavigationRecord(state.page.label, report.lastLoadedUrl, event.atMillis - it) }
                state.copy(
                    loading = report.loading,
                    progress = report.progress,
                    lastLoadedUrl = report.lastLoadedUrl,
                    title = report.title,
                    errors = report.errors,
                    canGoBack = report.canGoBack,
                    canGoForward = report.canGoForward,
                    requestedAtMillis = if (record != null) null else state.requestedAtMillis,
                    navigations =
                        if (record !=
                            null
                        ) {
                            (state.navigations + record).takeLast(HISTORY)
                        } else {
                            state.navigations
                        },
                )
            }
            is WebProbeEvent.PopupChanged -> state.copy(popupShown = event.shown, popupPendingMillis = null)
            is WebProbeEvent.PopupScheduled -> state.copy(popupPendingMillis = event.delayMillis)
        }
}
