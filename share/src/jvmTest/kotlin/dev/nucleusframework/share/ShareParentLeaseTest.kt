package dev.nucleusframework.share

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShareParentLeaseTest {
    @Test
    fun keepAliveIsClosedOnceWhenTheShareFailsBeforeTheNativeSide() =
        runTest {
            var closes = 0
            val parent = ShareParent.Linux("wayland:token", keepAlive = { closes++ })

            val error = assertFailsWith<ShareException> { ShareSheet.share(ShareRequest(emptyList()), parent) }

            assertEquals(ShareError.Empty, error.error)
            assertEquals(1, closes)
        }
}
