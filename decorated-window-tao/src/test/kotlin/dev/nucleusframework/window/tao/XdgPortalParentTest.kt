package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XdgPortalParentTest {
    @Test
    fun `x11 portal string is lowercase hex without a 0x prefix`() {
        val parent = XdgPortalParent.X11(0x2a)
        assertEquals("x11:2a", parent.portalParent)
        assertEquals(0x2aL, parent.xid)
        assertEquals("XdgPortalParent.X11", parent.toString())
    }

    @Test
    fun `x11 rejects zero and values above 32 bits`() {
        assertFailsWith<IllegalArgumentException> { XdgPortalParent.X11(0) }
        assertFailsWith<IllegalArgumentException> { XdgPortalParent.X11(0x1_0000_0000L) }
        XdgPortalParent.X11(1)
        XdgPortalParent.X11(0xffff_ffffL)
    }

    @Test
    fun `wayland export formats the portal parent and close is idempotent`() {
        val export = XdgForeignExport("abc-token", windowHandle = 0L)
        assertEquals("abc-token", export.handle)
        assertEquals("wayland:abc-token", export.portalParent)
        assertFalse(export.isClosed)

        val parent = XdgPortalParent.Wayland(export)
        assertEquals("abc-token", parent.handle)
        assertEquals("wayland:abc-token", parent.portalParent)
        assertTrue(parent.toString().contains("closed=false"))

        parent.close()
        assertTrue(export.isClosed)
        parent.close()
        export.close()
        assertTrue(export.isClosed)
        assertTrue(export.toString().contains("closed=true"))
    }
}
