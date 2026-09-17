package dev.nucleusframework.window.tao.event

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function coverage of the per-platform native→AWT key translation
 * tables ([macNativeKeyToAwt], [linuxNativeKeyToAwt]). Platform-independent:
 * the tables are data, so they are asserted on every OS.
 */
class TaoKeyMappingTest {
    // ── macOS (kVK_* hardware codes) ────────────────────────────────────────

    @Test
    fun `mac layout-aware path maps produced characters over physical position`() {
        // kVK_ANSI_T = 17 producing Hebrew א must still map by... nothing:
        // non-Latin code point falls back to the physical table (T).
        assertEquals('T'.code to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(17, 0x05D0))
        // AZERTY: physical Q key (kVK 12) producing 'a' maps to VK_A.
        assertEquals('A'.code to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(12, 'a'.code))
        assertEquals('A'.code to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(12, 'A'.code))
        // Digits follow the produced character on the top row.
        assertEquals('7'.code to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(26, '7'.code))
    }

    @Test
    fun `mac editing and whitespace keys`() {
        assertEquals(KeyEvent.VK_ENTER to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(36, 13))
        assertEquals(KeyEvent.VK_TAB to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(48, 9))
        assertEquals(KeyEvent.VK_SPACE to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(49, 32))
        assertEquals(KeyEvent.VK_BACK_SPACE to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(51, 8))
        assertEquals(KeyEvent.VK_ESCAPE to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(53, 27))
        assertEquals(KeyEvent.VK_DELETE to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(117, 0xF728))
    }

    @Test
    fun `mac modifier keys carry left-right location`() {
        assertEquals(KeyEvent.VK_META to KeyEvent.KEY_LOCATION_LEFT, macNativeKeyToAwt(55, 0))
        assertEquals(KeyEvent.VK_META to KeyEvent.KEY_LOCATION_RIGHT, macNativeKeyToAwt(54, 0))
        assertEquals(KeyEvent.VK_SHIFT to KeyEvent.KEY_LOCATION_LEFT, macNativeKeyToAwt(56, 0))
        assertEquals(KeyEvent.VK_SHIFT to KeyEvent.KEY_LOCATION_RIGHT, macNativeKeyToAwt(60, 0))
        assertEquals(KeyEvent.VK_ALT to KeyEvent.KEY_LOCATION_LEFT, macNativeKeyToAwt(58, 0))
        assertEquals(KeyEvent.VK_ALT to KeyEvent.KEY_LOCATION_RIGHT, macNativeKeyToAwt(61, 0))
        assertEquals(KeyEvent.VK_CONTROL to KeyEvent.KEY_LOCATION_LEFT, macNativeKeyToAwt(59, 0))
        assertEquals(KeyEvent.VK_CONTROL to KeyEvent.KEY_LOCATION_RIGHT, macNativeKeyToAwt(62, 0))
    }

    @Test
    fun `mac navigation and arrows`() {
        assertEquals(KeyEvent.VK_LEFT to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(123, 0xF702))
        assertEquals(KeyEvent.VK_RIGHT to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(124, 0xF703))
        assertEquals(KeyEvent.VK_DOWN to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(125, 0xF701))
        assertEquals(KeyEvent.VK_UP to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(126, 0xF700))
        assertEquals(KeyEvent.VK_HOME to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(115, 0))
        assertEquals(KeyEvent.VK_END to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(119, 0))
        assertEquals(KeyEvent.VK_PAGE_UP to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(116, 0))
        assertEquals(KeyEvent.VK_PAGE_DOWN to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(121, 0))
    }

    @Test
    fun `mac function keys F1 to F12`() {
        val expected =
            mapOf(
                122 to KeyEvent.VK_F1,
                120 to KeyEvent.VK_F2,
                99 to KeyEvent.VK_F3,
                118 to KeyEvent.VK_F4,
                96 to KeyEvent.VK_F5,
                97 to KeyEvent.VK_F6,
                98 to KeyEvent.VK_F7,
                100 to KeyEvent.VK_F8,
                101 to KeyEvent.VK_F9,
                109 to KeyEvent.VK_F10,
                103 to KeyEvent.VK_F11,
                111 to KeyEvent.VK_F12,
            )
        for ((vk, awt) in expected) {
            assertEquals(awt to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(vk, 0), "kVK $vk")
        }
    }

    @Test
    fun `mac keypad keys carry numpad location and ignore the digit fast path`() {
        assertEquals(KeyEvent.VK_NUMPAD0 to KeyEvent.KEY_LOCATION_NUMPAD, macNativeKeyToAwt(82, '0'.code))
        assertEquals(KeyEvent.VK_NUMPAD9 to KeyEvent.KEY_LOCATION_NUMPAD, macNativeKeyToAwt(92, '9'.code))
        assertEquals(KeyEvent.VK_ADD to KeyEvent.KEY_LOCATION_NUMPAD, macNativeKeyToAwt(69, '+'.code))
        assertEquals(KeyEvent.VK_SUBTRACT to KeyEvent.KEY_LOCATION_NUMPAD, macNativeKeyToAwt(78, '-'.code))
        assertEquals(KeyEvent.VK_MULTIPLY to KeyEvent.KEY_LOCATION_NUMPAD, macNativeKeyToAwt(67, '*'.code))
        assertEquals(KeyEvent.VK_DIVIDE to KeyEvent.KEY_LOCATION_NUMPAD, macNativeKeyToAwt(75, '/'.code))
        assertEquals(KeyEvent.VK_ENTER to KeyEvent.KEY_LOCATION_NUMPAD, macNativeKeyToAwt(76, 13))
    }

    @Test
    fun `mac ctrl combos fall back to the physical letter table`() {
        // Ctrl+C produces control char 0x03 — layout fast path must not fire.
        assertEquals('C'.code to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(8, 0x03))
        assertEquals('A'.code to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(0, 0x01))
        assertEquals('Z'.code to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(6, 0x1A))
    }

    @Test
    fun `mac unknown code maps to zero`() {
        assertEquals(0 to KeyEvent.KEY_LOCATION_STANDARD, macNativeKeyToAwt(255, 0))
    }

    // ── Linux (X11 keysyms) ─────────────────────────────────────────────────

    @Test
    fun `linux latin keysyms map one-to-one`() {
        assertEquals('A'.code to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt('a'.code, 'a'.code))
        assertEquals('Z'.code to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt('Z'.code, 'Z'.code))
        assertEquals('5'.code to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt('5'.code, '5'.code))
    }

    @Test
    fun `linux layout-aware path wins over the keysym`() {
        // Cyrillic keysym producing 'a' (layout switch) → VK_A.
        assertEquals('A'.code to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x06C1, 'a'.code))
    }

    @Test
    fun `linux editing and whitespace keysyms`() {
        assertEquals(KeyEvent.VK_ENTER to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF0D, 13))
        assertEquals(KeyEvent.VK_TAB to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF09, 9))
        assertEquals(KeyEvent.VK_TAB to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFE20, 0))
        assertEquals(KeyEvent.VK_BACK_SPACE to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF08, 8))
        assertEquals(KeyEvent.VK_ESCAPE to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF1B, 27))
        assertEquals(KeyEvent.VK_DELETE to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFFFF, 127))
        assertEquals(KeyEvent.VK_INSERT to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF63, 0))
    }

    @Test
    fun `linux modifiers carry left-right location and AltGr maps to right alt`() {
        assertEquals(KeyEvent.VK_SHIFT to KeyEvent.KEY_LOCATION_LEFT, linuxNativeKeyToAwt(0xFFE1, 0))
        assertEquals(KeyEvent.VK_SHIFT to KeyEvent.KEY_LOCATION_RIGHT, linuxNativeKeyToAwt(0xFFE2, 0))
        assertEquals(KeyEvent.VK_CONTROL to KeyEvent.KEY_LOCATION_LEFT, linuxNativeKeyToAwt(0xFFE3, 0))
        assertEquals(KeyEvent.VK_CONTROL to KeyEvent.KEY_LOCATION_RIGHT, linuxNativeKeyToAwt(0xFFE4, 0))
        assertEquals(KeyEvent.VK_ALT to KeyEvent.KEY_LOCATION_LEFT, linuxNativeKeyToAwt(0xFFE9, 0))
        assertEquals(KeyEvent.VK_ALT to KeyEvent.KEY_LOCATION_RIGHT, linuxNativeKeyToAwt(0xFFEA, 0))
        assertEquals(KeyEvent.VK_ALT to KeyEvent.KEY_LOCATION_RIGHT, linuxNativeKeyToAwt(0xFE03, 0))
        assertEquals(KeyEvent.VK_META to KeyEvent.KEY_LOCATION_LEFT, linuxNativeKeyToAwt(0xFFEB, 0))
        assertEquals(KeyEvent.VK_META to KeyEvent.KEY_LOCATION_RIGHT, linuxNativeKeyToAwt(0xFFEC, 0))
    }

    @Test
    fun `linux ctrl combos fall back to the latin keysym`() {
        // Ctrl+C: keysym stays XK_c, code point is the control char 0x03.
        assertEquals('C'.code to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt('c'.code, 0x03))
    }

    @Test
    fun `linux function keys F1 to F12`() {
        val expected =
            mapOf(
                0xFFBE to KeyEvent.VK_F1,
                0xFFBF to KeyEvent.VK_F2,
                0xFFC0 to KeyEvent.VK_F3,
                0xFFC1 to KeyEvent.VK_F4,
                0xFFC2 to KeyEvent.VK_F5,
                0xFFC3 to KeyEvent.VK_F6,
                0xFFC4 to KeyEvent.VK_F7,
                0xFFC5 to KeyEvent.VK_F8,
                0xFFC6 to KeyEvent.VK_F9,
                0xFFC7 to KeyEvent.VK_F10,
                0xFFC8 to KeyEvent.VK_F11,
                0xFFC9 to KeyEvent.VK_F12,
            )
        for ((keysym, awt) in expected) {
            assertEquals(awt to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(keysym, 0), "XK $keysym")
        }
    }

    @Test
    fun `linux keypad keys carry numpad location`() {
        assertEquals(KeyEvent.VK_NUMPAD0 to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFB0, '0'.code))
        assertEquals(KeyEvent.VK_NUMPAD9 to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFB9, '9'.code))
        assertEquals(KeyEvent.VK_ENTER to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF8D, 13))
        assertEquals(KeyEvent.VK_MULTIPLY to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFAA, 0))
        assertEquals(KeyEvent.VK_ADD to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFAB, 0))
        assertEquals(KeyEvent.VK_SUBTRACT to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFAD, 0))
        assertEquals(KeyEvent.VK_DECIMAL to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFAE, 0))
        assertEquals(KeyEvent.VK_DIVIDE to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFAF, 0))
        assertEquals(KeyEvent.VK_EQUALS to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFBD, 0))
        assertEquals(KeyEvent.VK_HOME to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF95, 0))
        assertEquals(KeyEvent.VK_END to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF9C, 0))
        assertEquals(KeyEvent.VK_PAGE_UP to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF9A, 0))
        assertEquals(KeyEvent.VK_PAGE_DOWN to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF9B, 0))
        assertEquals(KeyEvent.VK_LEFT to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF96, 0))
        assertEquals(KeyEvent.VK_UP to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF97, 0))
        assertEquals(KeyEvent.VK_RIGHT to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF98, 0))
        assertEquals(KeyEvent.VK_DOWN to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF99, 0))
        assertEquals(KeyEvent.VK_INSERT to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF9E, 0))
        assertEquals(KeyEvent.VK_DELETE to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF9F, 0))
        assertEquals(KeyEvent.VK_CLEAR to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFF9D, 0))
        assertEquals(KeyEvent.VK_SEPARATOR to KeyEvent.KEY_LOCATION_NUMPAD, linuxNativeKeyToAwt(0xFFAC, 0))
    }

    @Test
    fun `linux navigation space caps lock and punctuation`() {
        assertEquals(KeyEvent.VK_SPACE to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x0020, 32))
        assertEquals(KeyEvent.VK_CAPS_LOCK to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFFE5, 0))
        assertEquals(KeyEvent.VK_CONTEXT_MENU to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF67, 0))
        assertEquals(KeyEvent.VK_HOME to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF50, 0))
        assertEquals(KeyEvent.VK_END to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF57, 0))
        assertEquals(KeyEvent.VK_PAGE_UP to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF55, 0))
        assertEquals(KeyEvent.VK_PAGE_DOWN to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF56, 0))
        assertEquals(KeyEvent.VK_LEFT to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF51, 0))
        assertEquals(KeyEvent.VK_UP to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF52, 0))
        assertEquals(KeyEvent.VK_RIGHT to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF53, 0))
        assertEquals(KeyEvent.VK_DOWN to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0xFF54, 0))
        assertEquals(KeyEvent.VK_QUOTE to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x0027, 0))
        assertEquals(KeyEvent.VK_BACK_QUOTE to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x0060, 0))
        assertEquals(KeyEvent.VK_MINUS to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x002D, 0))
        assertEquals(KeyEvent.VK_EQUALS to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x003D, 0))
        assertEquals(KeyEvent.VK_OPEN_BRACKET to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x005B, 0))
        assertEquals(KeyEvent.VK_CLOSE_BRACKET to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x005D, 0))
        assertEquals(KeyEvent.VK_SEMICOLON to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x003B, 0))
        assertEquals(KeyEvent.VK_BACK_SLASH to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x005C, 0))
        assertEquals(KeyEvent.VK_COMMA to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x002C, 0))
        assertEquals(KeyEvent.VK_PERIOD to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x002E, 0))
        assertEquals(KeyEvent.VK_SLASH to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x002F, 0))
        assertEquals(0 to KeyEvent.KEY_LOCATION_STANDARD, linuxNativeKeyToAwt(0x1234, 0))
    }
}
