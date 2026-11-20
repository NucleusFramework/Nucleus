package dev.nucleusframework.lab.probes.rendering.webview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.webview.web.LoadingState
import dev.nucleusframework.webview.web.WebContent
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel
import dev.nucleusframework.webview.web.WebViewState as NativeWebViewState

@ContributesIntoSet(AppScope::class)
@Inject
class WebViewProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "WebView",
            domain = Domain.Rendering,
            summary =
                "Does a native web view (WKWebView / WebView2 / WebKitGTK) embed, take " +
                    "input and sit under Compose?",
            modules = listOf("decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "render",
                        "nucleusframework.dev renders inside the rounded frame and the load time is reported",
                    ),
                    Check("overlay", "The Compose chip and status pill stay drawn on top of the page while it scrolls"),
                    Check(
                        "popup",
                        "The Compose popup opens over the page; its text is crisp, also when scheduled after a load",
                    ),
                    Check(
                        "scroll",
                        "Scroll HUD page: trackpad/wheel scroll moves the page (not the Lab), with momentum on macOS",
                    ),
                    Check(
                        "input",
                        "Pointer & focus page: hover/click counters move, " +
                            "typing goes into the page field, not the URL field",
                    ),
                    Check("history", "Back / Forward follow the page history and enable only when possible"),
                ),
            keywords = listOf("wkwebview", "webview2", "webkitgtk", "nativeview", "composewebview"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<WebViewViewModel>()
        val state by vm.state.collectAsState()
        val web = rememberWebViewState((state.page as? WebPage.Remote)?.url ?: "about:blank")
        val navigator = rememberWebViewNavigator()
        LaunchedEffect(Unit) { if (state.page is WebPage.Local) web.content = state.page.toContent() }
        LaunchedEffect(vm) { vm.effects.collect { it.applyTo(web, navigator) } }
        LaunchedEffect(web, navigator) {
            snapshotFlow {
                WebReport(
                    loading = web.loadingState !is LoadingState.Finished,
                    progress = (web.loadingState as? LoadingState.Loading)?.progress ?: if (web.isLoading) 0f else 1f,
                    lastLoadedUrl = web.lastLoadedUrl,
                    title = web.pageTitle,
                    errors = web.errorsForCurrentRequest.size,
                    canGoBack = navigator.canGoBack,
                    canGoForward = navigator.canGoForward,
                )
            }.collect(vm::report)
        }

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                ChoiceRow(
                    "Page",
                    WebPage.Presets,
                    state.page,
                    name = { it.label },
                ) { vm.onIntent(WebProbeIntent.OpenPreset(it)) }
                TextFieldRow("URL", state.urlInput) { vm.onIntent(WebProbeIntent.EditUrl(it)) }
                Actions {
                    PrimaryAction("Go") { vm.onIntent(WebProbeIntent.SubmitUrl) }
                    SecondaryAction("Back", enabled = state.canGoBack) { vm.onIntent(WebProbeIntent.Back) }
                    SecondaryAction("Forward", enabled = state.canGoForward) { vm.onIntent(WebProbeIntent.Forward) }
                    SecondaryAction("Reload") { vm.onIntent(WebProbeIntent.Reload) }
                }
                SubHeading("Compose over the view")
                Actions {
                    SecondaryAction(
                        if (state.popupShown) "Hide popup" else "Show popup",
                    ) { vm.onIntent(WebProbeIntent.TogglePopup) }
                    SecondaryAction("Popup in 3 s") { vm.onIntent(WebProbeIntent.SchedulePopup(3_000)) }
                }
            },
            observed = {
                Readout("loading", if (state.loading) "${(state.progress * 100).toInt()} %" else "finished")
                Readout("last loaded URL", state.lastLoadedUrl)
                Readout("title", state.title)
                Readout("errors", state.errors.toString(), tone = if (state.errors > 0) Tone.Error else Tone.Neutral)
                Readout(
                    "popup",
                    state.popupPendingMillis?.let { "opens in $it ms" } ?: if (state.popupShown) "shown" else "hidden",
                )
                state.navigations.asReversed().take(3).forEach {
                    Readout("loaded in", "${it.millis} ms · ${it.loadedUrl ?: it.target}")
                }
            },
            wide = {
                SpecimenFrame(height = 420.dp) {
                    WebView(state = web, navigator = navigator, modifier = Modifier.fillMaxSize()) {
                        WebViewOverlay(state) { vm.onIntent(WebProbeIntent.TogglePopup) }
                    }
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("rendering.webview")
    }
}

private fun WebPage.toContent(): WebContent =
    when (this) {
        is WebPage.Remote -> WebContent.Url(url)
        is WebPage.Local -> WebContent.Data(html)
    }

private fun WebProbeEffect.applyTo(
    web: NativeWebViewState,
    navigator: WebViewNavigator,
) {
    when (this) {
        is WebProbeEffect.Load -> web.content = page.toContent()
        WebProbeEffect.Back -> if (navigator.canGoBack) navigator.navigateBack()
        WebProbeEffect.Forward -> if (navigator.canGoForward) navigator.navigateForward()
        WebProbeEffect.Reload -> navigator.reload()
    }
}
