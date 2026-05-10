package io.github.kdroidfilter.sampletao

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import kotlin.io.path.Path

/**
 * Foreign Function & Memory bridge to the SwiftUI demo dylib (see
 * `swift_view.swift`). No JNI, no `JavaVM*`, no Objective-C shim — Java
 * resolves Swift's `@_cdecl`-exported symbols via [SymbolLookup] and
 * builds [MethodHandle]s through [Linker].
 *
 * This is the path most modern "Java + native lib" applications follow
 * since JDK 22 (the API was preview in JDK 21 / JEP 442 and stabilized
 * in JDK 22 / JEP 454).
 *
 * The Swift handle pointer is tracked as an opaque [MemorySegment]; the
 * `NSView*` pointer is exposed as a `Long` so it slots straight into
 * [io.github.kdroidfilter.nucleus.window.tao.NucleusPlatformView.NsView]
 * without further conversion.
 */
internal object SampleSwiftUIBridge {

    private class Handles(
        val createH: MethodHandle,
        val viewH: MethodHandle,
        val setCounterH: MethodHandle,
        val setHueH: MethodHandle,
        val releaseH: MethodHandle,
    )

    private val handles: Handles? = tryLoad()

    /**
     * `true` if the dylib loaded successfully. The SwiftUITab uses this
     * to gate showing the demo: on macOS without the dylib (e.g. during
     * development before `build-swiftui.sh` has been run, or on a non-
     * macOS platform) it falls back to a placeholder.
     */
    val isLoaded: Boolean get() = handles != null

    private fun tryLoad(): Handles? = try {
        // Resolve + extract the per-arch dylib resource into a temp file
        // so `Linker` can load it. We extract rather than going through
        // `System.loadLibrary` because FFM's `SymbolLookup.libraryLookup`
        // wants a real filesystem path. The temp file is `deleteOnExit`
        // so we don't leave artefacts after the JVM dies.
        val arch = if (System.getProperty("os.arch") == "aarch64") "darwin-aarch64" else "darwin-x64"
        val resourcePath = "/nucleus/native/$arch/libsample_tao_swiftui.dylib"
        val stream = SampleSwiftUIBridge::class.java.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
        val tmpFile = File.createTempFile("sample_tao_swiftui_", ".dylib")
        tmpFile.deleteOnExit()
        stream.use { input -> tmpFile.outputStream().use { output -> input.copyTo(output) } }

        val arena = Arena.ofShared()
        val linker = Linker.nativeLinker()
        val lookup = SymbolLookup.libraryLookup(Path(tmpFile.absolutePath), arena)

        Handles(
            createH = linker.downcallHandle(
                lookup.find("nucleus_sample_swiftui_create").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS),
            ),
            viewH = linker.downcallHandle(
                lookup.find("nucleus_sample_swiftui_view").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ),
            setCounterH = linker.downcallHandle(
                lookup.find("nucleus_sample_swiftui_set_counter").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            ),
            setHueH = linker.downcallHandle(
                lookup.find("nucleus_sample_swiftui_set_hue").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT),
            ),
            releaseH = linker.downcallHandle(
                lookup.find("nucleus_sample_swiftui_release").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            ),
        )
    } catch (t: Throwable) {
        System.err.println("[SampleSwiftUIBridge] failed to load dylib: ${t.message}")
        null
    }

    /** Allocates the Swift handle. Caller owns it — must call [release]. */
    fun create(): MemorySegment = handles!!.createH.invoke() as MemorySegment

    /** Returns the wrapped `NSHostingView*` so the caller can pass it to [NativeView]. */
    fun viewPointer(handle: MemorySegment): MemorySegment = handles!!.viewH.invoke(handle) as MemorySegment

    fun setCounter(handle: MemorySegment, value: Int) { handles!!.setCounterH.invoke(handle, value) }

    fun setHue(handle: MemorySegment, hue: Float) { handles!!.setHueH.invoke(handle, hue) }

    fun release(handle: MemorySegment) { handles!!.releaseH.invoke(handle) }
}
