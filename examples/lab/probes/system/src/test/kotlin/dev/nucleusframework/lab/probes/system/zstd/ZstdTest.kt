package dev.nucleusframework.lab.probes.system.zstd

import dev.nucleusframework.lab.probes.system.ToolReadBack
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZstdTest {
    private val realResource = JarResource("/jni/aarch64/libzstd-kmp.dylib", "jar:…", 600_000, "ab")
    private val marker = realResource.copy(sizeBytes = 64)

    @Test
    fun `a tiny JAR entry is the sandbox marker`() {
        assertTrue(marker.isMarker)
        assertTrue(!realResource.isMarker)
    }

    @Test
    fun `loaded-from prefers the OS module list, then the manifest, then the temp file`() {
        val load = LoadObservation(listOf("/tmp/zstd-kmp1.tmp"), loadedNow = true, millis = 3, thread = "io")
        val manifest =
            SandboxManifest("/app/m.properties", emptyList(), "libzstd-kmp.dylib", "/app/Frameworks/libzstd-kmp.dylib")
        assertNull(ZstdState().loadedFrom())
        assertTrue(ZstdState(resource = realResource, load = load).loadedFrom()!!.startsWith("/tmp/zstd-kmp1.tmp"))
        assertTrue(
            ZstdState(resource = marker, manifest = manifest, load = load).loadedFrom()!!.startsWith("/app/Frameworks"),
        )
        val mapped = ToolReadBack("lsof", listOf("/real/libzstd-kmp.dylib"))
        assertTrue(
            ZstdState(
                resource = marker,
                manifest = manifest,
                load = load,
                mapped = mapped,
            ).loadedFrom()!!.startsWith("/real/"),
        )
    }

    @Test
    fun `the first observed load is kept over later confirmations`() {
        val first = LoadObservation(listOf("/tmp/zstd-kmp1.tmp"), loadedNow = true, millis = 30, thread = "io")
        val later = LoadObservation(emptyList(), loadedNow = false, millis = 0, thread = "io")
        val state =
            ZstdReducer.reduce(
                ZstdReducer.reduce(ZstdState(), ZstdEvent.Loaded(first)),
                ZstdEvent.Loaded(later),
            )
        assertEquals(first, state.load)
    }

    /** Loads the real JNI library from the zstd-kmp JAR: the same path the probe exercises. */
    @Test
    fun `codec round trip is lossless`() {
        listOf(1, 1_000, 300_000).forEach { size ->
            val input = ZstdCodec.sample(size)
            val compressed = ZstdCodec.compress(input)
            assertContentEquals(input, ZstdCodec.decompress(compressed, input.size))
            if (size > 1_000) assertTrue(compressed.size < input.size / 2, "ratio for $size")
        }
    }
}
