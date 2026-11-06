@file:OptIn(InternalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformRootForTest
import dev.nucleusframework.window.ExperimentalNucleusApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The [PlatformRootForTest] of every live Compose owner in Tao windows (window content and popup layers), so an
 * in-app end-to-end harness can send real pointer and key events through Compose's input pipeline, as
 * `ComposeUiTest` does for test hosts. A root is listed from its creation to its disposal.
 */
@ExperimentalNucleusApi
public object TaoRootsForTest {
    private val live = CopyOnWriteArrayList<PlatformRootForTest>()

    /** The live roots, in creation order. */
    public val roots: List<PlatformRootForTest> get() = live.toList()

    internal fun wrap(delegate: PlatformContext.RootForTestListener?): PlatformContext.RootForTestListener =
        object : PlatformContext.RootForTestListener {
            override fun onRootForTestCreated(root: PlatformRootForTest) {
                live += root
                delegate?.onRootForTestCreated(root)
            }

            override fun onRootForTestDisposed(root: PlatformRootForTest) {
                live -= root
                delegate?.onRootForTestDisposed(root)
            }
        }
}
