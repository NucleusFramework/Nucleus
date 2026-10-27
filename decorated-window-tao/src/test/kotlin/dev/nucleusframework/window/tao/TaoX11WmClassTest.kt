package dev.nucleusframework.window.tao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaoX11WmClassTest {
    @Test
    fun `the windows take the StartupWMClass the packager wrote`() {
        // Derived from mainClass = "dev.nucleusframework.lab.app.MainKt": the Lab's entry says
        // StartupWMClass=dev-nucleusframework-lab-app-MainKt while GTK would name the window
        // after the launcher, NucleusLab.
        assertEquals(
            "dev-nucleusframework-lab-app-MainKt",
            TaoX11WmClass.resolve("dev-nucleusframework-lab-app-MainKt"),
        )
    }

    @Test
    fun `an explicit startupWMClass is kept as written`() {
        assertEquals("warlock", TaoX11WmClass.resolve("warlock"))
        assertEquals("warlock", TaoX11WmClass.resolve("  warlock "))
    }

    @Test
    fun `nothing baked keeps GTK's default`() {
        assertNull(TaoX11WmClass.resolve(null))
        assertNull(TaoX11WmClass.resolve(""))
        assertNull(TaoX11WmClass.resolve("   "))
    }
}
