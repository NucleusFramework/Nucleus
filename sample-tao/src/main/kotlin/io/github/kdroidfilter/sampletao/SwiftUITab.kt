package io.github.kdroidfilter.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.tao.NativeView
import io.github.kdroidfilter.nucleus.window.tao.NucleusPlatformView
import io.github.kdroidfilter.nucleus.window.tao.consumeOverlayPointerEvents
import java.lang.foreign.MemorySegment

/**
 * SwiftUI tab — wraps a real SwiftUI view inside [NativeView] via
 * `NSHostingView` and drives its `@Published` state from a Compose
 * overlay. Interop happens entirely via the Foreign Function & Memory
 * API ([SampleSwiftUIBridge]); no JNI involvement.
 */
@Composable
internal fun SwiftUITab(modifier: Modifier = Modifier) {
    if (Platform.Current != Platform.MacOS || !SampleSwiftUIBridge.isLoaded) {
        UnsupportedSwiftUIPlatform(modifier)
        return
    }

    var swiftHandle: MemorySegment? by remember { mutableStateOf(null) }
    var counter by remember { mutableStateOf(0) }
    var hue by remember { mutableStateOf(0.55f) }

    // Push Compose state into SwiftUI's @Published model. Compose's
    // dispatcher on macOS runs on the AppKit main thread (= Tao loop
    // thread = the same thread NSHostingView expects to be touched on)
    // so calling `setCounter` here is safe.
    LaunchedEffect(swiftHandle) {
        val h = swiftHandle ?: return@LaunchedEffect
        snapshotFlow { counter }.collect { SampleSwiftUIBridge.setCounter(h, it) }
    }
    LaunchedEffect(swiftHandle) {
        val h = swiftHandle ?: return@LaunchedEffect
        snapshotFlow { hue }.collect { SampleSwiftUIBridge.setHue(h, it) }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)).padding(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
        ) {
            NativeView(
                factory = {
                    val h = SampleSwiftUIBridge.create()
                    val nsViewSegment = SampleSwiftUIBridge.viewPointer(h)
                    swiftHandle = h
                    val nsViewAddress = nsViewSegment.address()
                    object : NucleusPlatformView.NsView {
                        override val nsViewHandle: Long = nsViewAddress
                        override fun dispose() {
                            SampleSwiftUIBridge.release(h)
                            swiftHandle = null
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 12.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    SwiftUIControlPill(
                        counter = counter,
                        onIncrement = { counter++ },
                        onDecrement = { counter-- },
                        onReset = { counter = 0; hue = 0.55f },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwiftUIControlPill(
    counter: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .consumeOverlayPointerEvents()
            .background(Color(0xCC0F172A), shape = CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PillButton(symbol = "−", onClick = onDecrement)
        BasicText(
            text = "counter = $counter",
            style = TextStyle(
                color = Color(0xFFE6E6E6),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        PillButton(symbol = "+", onClick = onIncrement)
        PillButton(symbol = "↺", onClick = onReset)
    }
}

@Composable
private fun PillButton(symbol: String, onClick: () -> Unit) {
    // `background(color, shape = CircleShape)` keeps the hit-test
    // rectangular. `Modifier.clip(CircleShape).background(...)` would
    // restrict pointer hit-testing to the inscribed circle, so clicks
    // landing in the 36×36 layout box's corners would silently miss.
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.White.copy(alpha = 0.10f), shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = symbol,
            style = TextStyle(
                color = Color(0xFFE6E6E6),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun UnsupportedSwiftUIPlatform(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF0F1115)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "SwiftUI demo — macOS only (run ./build-swiftui.sh first)",
            style = TextStyle(
                color = Color(0xFFE6E6E6),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
