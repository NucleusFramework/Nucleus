package io.github.kdroidfilter.sampletao

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.tao.LocalTaoWindow
import io.github.kdroidfilter.nucleus.window.tao.NativeView
import io.github.kdroidfilter.nucleus.window.tao.NucleusPlatformView
import io.github.kdroidfilter.nucleus.window.tao.consumeOverlayPointerEvents
import kotlinx.coroutines.delay

private const val INITIAL_URL = "https://nucleusframework.dev"

/**
 * **WebView demo tab.**
 *
 * A live native WebView mounted via `NativeView`, with a Compose-
 * rendered floating navigation pill (back / forward / reload + URL
 * field) painted on top via `NativeView`'s `content` slot.
 *
 *  - **macOS**: real `WKWebView` embedded as an `NSView` sibling.
 *    Overlay slot lives in a borderless transparent NSPanel above the
 *    host window and intercepts pointer events inside its bounds.
 *  - **Linux**: real `WebKitWebView` reparented into Tao's GTK
 *    content widget tree via `NucleusPlatformView.GtkWidget`. **No
 *    overlay slot** — the `content` lambda is rendered by Compose on
 *    top of GTK and may visually overlap the page, but the WebView
 *    receives all pointer/keyboard events directly through GTK.
 */
@Composable
internal fun WebViewTab(modifier: Modifier = Modifier) {
    if (!isSampleWebViewSupported()) {
        UnsupportedPlatform(modifier)
        return
    }

    val taoWindow = LocalTaoWindow.current
    val parentHwnd = remember(taoWindow) {
        if (Platform.Current == Platform.Windows) taoWindow?.nativeHandle ?: 0L else 0L
    }
    var controller: SampleWebViewController? by remember { mutableStateOf(null) }
    var urlInput by remember { mutableStateOf(INITIAL_URL) }
    var urlFocused by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    // Lightweight polling: backend-native navigation observers
    // (WKWebView KVO / WebKitWebView property notify) would be
    // cleaner but a 120ms tick is plenty for a sample. Skips updating
    // the URL field while the user is editing it so we don't fight
    // their cursor.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            canGoBack = c.canGoBack()
            canGoForward = c.canGoForward()
            isLoading = c.isLoading()
            if (!urlFocused) c.currentUrl()?.let { urlInput = it }
            delay(120L)
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)).padding(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
        ) {
            // One-shot navigation tracker so `update` doesn't reload on
            // every recomposition.
            val loadedFlag = remember { booleanArrayOf(false) }
            NativeView(
                factory = {
                    val view = createSampleWebViewPlatformView(parentHwnd) { c -> controller = c }
                    view
                },
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 12.dp,
                update = { _ ->
                    val c = controller
                    if (!loadedFlag[0] && c != null) {
                        loadedFlag[0] = true
                        c.loadUrl(INITIAL_URL)
                    }
                },
            ) {
                // Compose UI rendered ON TOP of the WebView
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    NavPill(
                        url = urlInput,
                        onUrlChange = { urlInput = it },
                        onUrlFocusChange = { urlFocused = it },
                        onSubmit = { controller?.loadUrl(urlInput) },
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        isLoading = isLoading,
                        onBack = { if (canGoBack) controller?.goBack() },
                        onForward = { if (canGoForward) controller?.goForward() },
                        onReload = { controller?.reload() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavPill(
    url: String,
    onUrlChange: (String) -> Unit,
    onUrlFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLoading: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .height(56.dp)
            .width(520.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xCC0F172A))
            .border(1.dp, Color(0x4080D8FF), RoundedCornerShape(28.dp))
            .consumeOverlayPointerEvents(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavIconButton(symbol = "‹", enabled = canGoBack, onClick = onBack)
            NavIconButton(symbol = "›", enabled = canGoForward, onClick = onForward)
            NavIconButton(
                symbol = "⟳",
                enabled = true,
                onClick = onReload,
                spinning = isLoading,
            )
            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.18f)))
            UrlField(
                url = url,
                onUrlChange = onUrlChange,
                onFocusChange = onUrlFocusChange,
                onSubmit = onSubmit,
                modifier = Modifier.weight(1f),
            )
        }
        if (isLoading) {
            IndeterminateBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun IndeterminateBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading-bar")
    val progress by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loading-bar-progress",
    )
    Canvas(modifier = modifier) {
        val segmentWidth = size.width * 0.3f
        val x = size.width * progress
        drawRect(
            color = Color(0xFF60A5FA),
            topLeft = Offset(x, 0f),
            size = Size(segmentWidth, size.height),
        )
    }
}

@Composable
private fun NavIconButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    spinning: Boolean = false,
) {
    val rotation = if (spinning) {
        val transition = rememberInfiniteTransition(label = "spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spin-angle",
        )
        angle
    } else 0f
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = symbol,
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun UrlField(
    url: String,
    onUrlChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hasFocus by remember { mutableStateOf(false) }
    // BasicTextField fills the entire visible field shape — its
    // built-in pointer pipeline owns the I-beam cursor, the
    // focus-on-tap, and the multi-tap selection detector. Wrapping
    // it in an outer `clickable` + inner `fillMaxWidth()` instead
    // made every click look like a fresh single-tap to the wrapper.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = if (hasFocus) 0.16f else 0.06f))
            .border(
                1.dp,
                if (hasFocus) Color(0xFF60A5FA) else Color.Transparent,
                RoundedCornerShape(18.dp),
            )
            // BasicTextField only applies the I-beam over the rendered text
            // glyphs, which on a singleLine field is a thin strip at the top
            // (textStyle = 13.sp inside a 36.dp box, top-aligned). Apply it on
            // the outer field rect so the whole pill shows the I-beam — what
            // every HTML/native text input does.
            .pointerHoverIcon(PointerIcon.Text)
            .padding(horizontal = 12.dp),
    ) {
        BasicTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Color(0xFF60A5FA)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }, onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged {
                    hasFocus = it.isFocused
                    onFocusChange(it.isFocused)
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) { innerTextField() }
            },
        )
    }
}

/**
 * Builds the right [NucleusPlatformView] flavour for the running OS:
 * NSView wrapper on macOS, GtkWidget wrapper on Linux. Hands the
 * resulting controller back via [onController] so the Composable can
 * drive navigation without knowing which backend is live.
 */
private fun createSampleWebViewPlatformView(
    parentHwnd: Long,
    onController: (SampleWebViewController?) -> Unit,
): NucleusPlatformView = when (Platform.Current) {
    Platform.MacOS -> {
        val ptr = SampleWebViewBridge.nativeCreate()
        onController(MacOsSampleWebViewController(ptr))
        object : NucleusPlatformView.NsView {
            override val nsViewHandle: Long = ptr
            override fun dispose() {
                SampleWebViewBridge.nativeRelease(ptr)
                onController(null)
            }
        }
    }
    Platform.Linux -> {
        val ptr = SampleWebViewLinuxBridge.nativeCreate()
        onController(LinuxSampleWebViewController(ptr))
        object : NucleusPlatformView.GtkWidget {
            override val gtkWidgetHandle: Long = ptr
            override fun dispose() {
                SampleWebViewLinuxBridge.nativeRelease(ptr)
                onController(null)
            }
        }
    }
    Platform.Windows -> {
        require(parentHwnd != 0L) { "WebViewTab: parent HWND not yet realised; LocalTaoWindow missing" }
        val ptr = SampleWebViewWindowsBridge.nativeCreate(parentHwnd, INITIAL_URL)
        require(ptr != 0L) { "wry WebView creation failed (WebView2 Runtime missing?)" }
        onController(WindowsSampleWebViewController(ptr))
        object : NucleusPlatformView.HWnd {
            override val hwndHandle: Long = ptr
            override fun setBounds(xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) {
                // wry's WebView2 controller attaches directly to the parent
                // HWND (no hosting HWND), so the controller's bounds (x, y,
                // w, h) is what positions the WebView. setFrame on the
                // returned HWND has no visual effect — the controller draws
                // on top regardless of the HWND rect.
                SampleWebViewWindowsBridge.nativeSetBounds(ptr, xPx, yPx, widthPx, heightPx)
            }
            override fun dispose() {
                SampleWebViewWindowsBridge.nativeRelease(ptr)
                onController(null)
            }
        }
    }
    else -> error("WebView demo unsupported on ${Platform.Current}")
}

@Composable
private fun UnsupportedPlatform(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "WebView demo — macOS only",
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}
