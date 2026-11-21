package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.scene.ensureSkikoComposeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Compose 1.13 regression (#671, B1): `rememberVectorPainter` reads
 * `LocalGraphicsResourceCache`, whose default resolves through `LocalOwner`.
 * The Tao application composition has no Owner, so a window or tray icon
 * built at application scope threw `CompositionLocal LocalOwner not present`
 * unless [ProvideApplicationRootLocals] supplies a `null` default.
 */
class ApplicationScopeVectorPainterTest {
    @Test
    fun `rememberVectorPainter works at application scope`() {
        // TaoApplication.run registers the Skiko backend before composing; so does the test.
        ensureSkikoComposeBackend()
        runBlocking(Dispatchers.Unconfined) {
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(NoOpApplier, recomposer)
            var painter: Painter? = null
            try {
                // The initial composition runs synchronously: a missing local throws here.
                composition.setContent {
                    ProvideApplicationRootLocals {
                        painter = rememberVectorPainter(square)
                    }
                }
                assertNotNull(painter)
            } finally {
                composition.dispose()
                recomposer.cancel()
            }
        }
    }

    private val square =
        ImageVector
            .Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                close()
            }.build()
}
