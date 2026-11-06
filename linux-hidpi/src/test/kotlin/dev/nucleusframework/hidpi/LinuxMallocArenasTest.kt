package dev.nucleusframework.hidpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxMallocArenasTest {
    @Test
    fun `off unless configured`() {
        assertNull(resolveMallocArenaMax(configured = null, arenaMaxEnv = null, tunablesEnv = null))
    }

    @Test
    fun `configured value is the cap`() {
        assertEquals(2, resolveMallocArenaMax(configured = " 2 ", arenaMaxEnv = null, tunablesEnv = null))
    }

    @Test
    fun `zero negative or invalid value disables it`() {
        assertNull(resolveMallocArenaMax(configured = "0", arenaMaxEnv = null, tunablesEnv = null))
        assertNull(resolveMallocArenaMax(configured = "-1", arenaMaxEnv = null, tunablesEnv = null))
        assertNull(resolveMallocArenaMax(configured = "many", arenaMaxEnv = null, tunablesEnv = null))
    }

    @Test
    fun `a value chosen by the environment wins`() {
        assertNull(resolveMallocArenaMax(configured = "2", arenaMaxEnv = "8", tunablesEnv = null))
        assertNull(
            resolveMallocArenaMax(
                configured = "2",
                arenaMaxEnv = null,
                tunablesEnv = "glibc.malloc.trim_threshold=1:glibc.malloc.arena_max=4",
            ),
        )
    }

    @Test
    fun `unrelated tunables keep the cap`() {
        assertEquals(
            2,
            resolveMallocArenaMax(configured = "2", arenaMaxEnv = null, tunablesEnv = "glibc.malloc.tcache_count=0"),
        )
    }

    @Test
    fun `capping is safe to call on any platform`() {
        capLinuxMallocArenas()
    }
}
