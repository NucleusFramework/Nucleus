package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.dispatch.TaoDecomposeMainThreadChecker
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Decompose's main-thread check follows the Tao main thread (#513). */
class TaoDecomposeMainThreadCheckerTest {
    private val checker = TaoDecomposeMainThreadChecker()
    private val previous = TaoMainDispatcher.taoMainThread

    @AfterTest
    fun restore() {
        TaoMainDispatcher.taoMainThread = previous
    }

    @Test
    fun `every thread is accepted before a Tao main thread exists`() {
        TaoMainDispatcher.taoMainThread = null
        assertTrue(checker.isMainThread())
        assertTrue(onOtherThread { checker.isMainThread() })
    }

    @Test
    fun `only the Tao main thread is accepted once it exists`() {
        TaoMainDispatcher.taoMainThread = Thread.currentThread()
        assertTrue(checker.isMainThread())
        assertFalse(onOtherThread { checker.isMainThread() })
    }

    private fun onOtherThread(block: () -> Boolean): Boolean {
        var result = false
        thread { result = block() }.join()
        return result
    }
}
