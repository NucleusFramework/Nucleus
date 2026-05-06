package io.github.kdroidfilter.sampletao

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.tao.NativeView
import io.github.kdroidfilter.nucleus.window.tao.consumeOverlayPointerEvents

/**
 * **WebView demo tab.**
 *
 * A live `WKWebView` mounted via `NativeView`, with a Compose-rendered
 * watermark (containing a focus-test `BasicTextField`) painted on top
 * via `NativeView`'s `content` slot.
 *
 * The overlay slot lives in a borderless transparent NSPanel above the
 * host window — it intercepts pointer events inside its bounds (so the
 * text field can be clicked + typed into), passes everything else
 * through to the WebView. macOS-only.
 */
@Composable
internal fun WebViewTab(modifier: Modifier = Modifier) {
    if (Platform.Current != Platform.MacOS || !SampleWebViewBridge.isLoaded) {
        UnsupportedPlatform(modifier)
        return
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
                factory = { SampleWebViewBridge.nativeCreate() },
                modifier = Modifier.fillMaxSize(),
                update = { handle ->
                    if (!loadedFlag[0]) {
                        loadedFlag[0] = true
                        SampleWebViewBridge.nativeLoadUrl(handle, "https://example.com")
                    }
                },
                onRelease = { SampleWebViewBridge.nativeRelease(it) },
            ) {
                // Compose UI rendered ON TOP of the WebView. Lives in a
                // separate NSPanel with its own ComposeScene, so it
                // intercepts events + supports focus / keyboard input.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Watermark()
                }
            }
        }
    }
}

@Composable
private fun Watermark() {
    var text by remember { mutableStateOf("Type here…") }
    var clickCount by remember { mutableIntStateOf(0) }
    var hasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .height(56.dp)
            .width(360.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xCC0F172A))
            .border(1.dp, Color(0x4080D8FF), RoundedCornerShape(28.dp))
            .consumeOverlayPointerEvents()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Diagnostic clickable badge: every click increments the counter
        // — proves the Compose click pipeline reaches the overlay scene.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF34D399))
                .clickable { clickCount++ },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = clickCount.toString(),
                style = TextStyle(color = Color(0xFF0F172A), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
        Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.18f)))
        // BasicTextField fills the entire visible field shape — its
        // built-in pointer pipeline owns the I-beam cursor, the
        // focus-on-tap, and (crucially) the multi-tap detector that
        // turns double-click into word selection / triple-click into
        // select-all. Wrapping it in an outer `clickable` + inner
        // `fillMaxWidth()` instead made every click look like a fresh
        // single-tap to the wrapper, never reaching the field's
        // selection gesture.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = if (hasFocus) 0.16f else 0.06f))
                .border(
                    1.dp,
                    if (hasFocus) Color(0xFF60A5FA) else Color.Transparent,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 12.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(Color(0xFF60A5FA)),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .onFocusChanged { hasFocus = it.isFocused },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) { innerTextField() }
                },
            )
        }
    }

    LaunchedEffect(hasFocus) {
        println("[WebViewTab] TextField focus = $hasFocus")
    }
    LaunchedEffect(clickCount) {
        if (clickCount > 0) println("[WebViewTab] click counter = $clickCount")
    }
    LaunchedEffect(text) {
        println("[WebViewTab] text = \"$text\"")
    }
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
