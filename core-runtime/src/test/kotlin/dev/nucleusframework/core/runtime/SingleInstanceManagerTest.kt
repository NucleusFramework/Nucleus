package dev.nucleusframework.core.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleInstanceManagerTest {
    @Test
    fun `this process reports as the single instance and re-entry is a no-op`() {
        val restores = AtomicInteger(0)
        val first =
            SingleInstanceManager.isSingleInstance {
                restores.incrementAndGet()
            }
        assertTrue(first)
        val again =
            SingleInstanceManager.isSingleInstance {
                restores.incrementAndGet()
            }
        assertTrue("re-entry must keep the already-held lock", again)
        assertTrue(restores.get() == 0)
    }
}
