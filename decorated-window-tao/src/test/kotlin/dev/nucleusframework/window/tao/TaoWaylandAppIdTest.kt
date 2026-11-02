package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaoWaylandAppIdTest {
    private fun resolve(
        desktopEntryId: String?,
        env: Map<String, String> = emptyMap(),
        isNativeImage: Boolean = false,
    ) = TaoWaylandAppId.resolve(desktopEntryId, env::get, isNativeImage)

    @Test
    fun `a JVM package sends its desktop entry's name, not the launcher's`() {
        // linux.packageName = "nucleus-lab", packageName = "NucleusLab": the entry is
        // nucleus-lab.desktop while GTK would send the launcher's name, NucleusLab
        assertEquals("nucleus-lab", resolve("nucleus-lab"))
    }

    @Test
    fun `nothing baked keeps GTK's default`() {
        assertNull(resolve(null))
        assertNull(resolve(""))
        assertNull(resolve("bad/id"))
    }

    @Test
    fun `a Flatpak sends its Flatpak app id`() {
        assertEquals(
            "dev.nucleusframework.Lab",
            resolve("nucleus-lab", mapOf("FLATPAK_ID" to "dev.nucleusframework.Lab")),
        )
    }

    @Test
    fun `a snap and a native image keep GTK's default`() {
        assertNull(resolve("nucleus-lab", mapOf("SNAP_NAME" to "nucleus-lab")))
        assertNull(resolve("nucleus-lab", isNativeImage = true))
    }
}
