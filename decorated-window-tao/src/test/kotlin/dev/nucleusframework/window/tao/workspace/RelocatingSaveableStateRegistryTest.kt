package dev.nucleusframework.window.tao.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Key relocation and value ordering of [RelocatingSaveableStateRegistry] — the
 * part of a host change that needs no window and no composition. The headful
 * suite covers the real `rememberSaveable` round trips.
 */
class RelocatingSaveableStateRegistryTest {
    @Test
    fun `keys relocate across hosts by rotation of the anchor delta`() {
        val anchorA = 0x1234_5678_9ABC_DEF0L
        val anchorB = -0x0FED_CBA9_8765_4322L
        val delta = anchorA xor anchorB
        // Two call sites at depths 2 and 7 below the anchor: their hashes differ
        // between hosts by the delta rotated by the accumulated shifts.
        val siteA1 = 0x0000_00AB_CDEF_0123L
        val siteA2 = -0x7777_0000_1111_2222L
        val siteB1 = siteA1 xor delta.rotateLeft(6)
        val siteB2 = siteA2 xor delta.rotateLeft(21)
        val saved =
            RelocatedSavedState(
                anchor = anchorA,
                values =
                    mapOf(
                        siteA1.toString(36) to listOf<Any?>("first"),
                        siteA2.toString(36) to listOf<Any?>(42),
                        "explicit" to listOf<Any?>("named"),
                    ),
            )

        val registry = RelocatingSaveableStateRegistry(saved, anchorB)

        assertEquals("first", registry.consumeRestored(siteB1.toString(36)))
        assertEquals(42, registry.consumeRestored(siteB2.toString(36)))
        assertEquals("named", registry.consumeRestored("explicit"))
        assertNull(registry.consumeRestored(siteB1.toString(36)))
        assertNull(registry.consumeRestored(0x5555L.toString(36)))
    }

    @Test
    fun `values keep their order when providers unregister in reverse`() {
        val registry = RelocatingSaveableStateRegistry(saved = null, anchor = 1L)
        // Three call sites sharing one key — what Compose does with sibling
        // rememberSaveable / rememberScrollState calls in the same group.
        val entries =
            listOf<Any>("tool", 33f, 0).map { value ->
                registry.registerProvider("shared") { value }
            }

        // Compose forgets in reverse composition order, before the host's own
        // disposable effect gets to save.
        entries.asReversed().forEach { it.unregister() }

        assertEquals(mapOf("shared" to listOf<Any?>("tool", 33f, 0)), registry.performSave())
    }

    @Test
    fun `a re-registering provider keeps its place among the values`() {
        val registry = RelocatingSaveableStateRegistry(saved = null, anchor = 1L)
        registry.registerProvider("shared") { "first" }
        val second = registry.registerProvider("shared") { "second" }
        registry.registerProvider("shared") { "third" }

        // A recomposing rememberSaveable: unregisters, then registers again.
        second.unregister()
        registry.registerProvider("shared") { "second-again" }

        assertEquals(mapOf("shared" to listOf<Any?>("first", "second-again", "third")), registry.performSave())
    }

    @Test
    fun `restored values never consumed survive another host change`() {
        val saved = RelocatedSavedState(anchor = 1L, values = mapOf("kept" to listOf<Any?>("value")))
        val registry = RelocatingSaveableStateRegistry(saved, anchor = 2L)
        registry.registerProvider("other") { "live" }

        assertEquals(
            mapOf("kept" to listOf<Any?>("value"), "other" to listOf<Any?>("live")),
            registry.performSave(),
        )
    }

    @Test
    fun `a slot snapshot prefers the live registry over the last save`() {
        val slot = RelocatableSlot()
        assertNull(slot.snapshot(), "nothing known before any host composed")

        slot.savedState = RelocatedSavedState(anchor = 1L, values = mapOf("k" to listOf<Any?>("old")))
        assertEquals(listOf<Any?>("old"), slot.snapshot()?.values?.get("k"))

        // The next host mounts while the previous one is still composed: the
        // live values win over the stale save.
        val live = RelocatingSaveableStateRegistry(saved = null, anchor = 2L)
        live.registerProvider("k") { "new" }
        slot.activeRegistry = live
        val snapshot = slot.snapshot()
        assertEquals(2L, snapshot?.anchor)
        assertEquals(listOf<Any?>("new"), snapshot?.values?.get("k"))

        slot.activeRegistry = null
        assertSame(slot.savedState, slot.snapshot())
    }
}
