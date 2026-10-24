package dev.nucleusframework.lab.probes.rendering.swiftui

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.lab.core.format.summary
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.logging.Level
import java.util.logging.Logger

/** Port over the SwiftUI helper (`lab_swiftui.swift`), reached through FFM — no JNI. */
interface SwiftUiGateway {
    val availability: Availability

    /** Allocates a Swift handle; the caller owns it and must [release] it. */
    fun create(): MemorySegment

    /** The `NSHostingView*` of [handle], for `NucleusPlatformView.NsView`. */
    fun viewAddress(handle: MemorySegment): Long

    fun setCounter(
        handle: MemorySegment,
        value: Int,
    )

    fun setHue(
        handle: MemorySegment,
        hue: Float,
    )

    fun release(handle: MemorySegment)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class FfmSwiftUiGateway : SwiftUiGateway {
    private class Handles(
        val create: MethodHandle,
        val view: MethodHandle,
        val setCounter: MethodHandle,
        val setHue: MethodHandle,
        val release: MethodHandle,
    )

    private var failure: String? = null
    private val handles: Handles? by lazy { load() }

    override val availability: Availability
        get() =
            when {
                Platform.Current != Platform.MacOS -> Availability.Unavailable("SwiftUI exists on macOS only")
                handles == null -> Availability.Unavailable(failure ?: "helper not loaded")
                else -> Availability.Available
            }

    override fun create(): MemorySegment = checkNotNull(handles).create.invoke() as MemorySegment

    override fun viewAddress(handle: MemorySegment): Long =
        (checkNotNull(handles).view.invoke(handle) as MemorySegment).address()

    override fun setCounter(
        handle: MemorySegment,
        value: Int,
    ) {
        checkNotNull(handles).setCounter.invoke(handle, value)
    }

    override fun setHue(
        handle: MemorySegment,
        hue: Float,
    ) {
        checkNotNull(handles).setHue.invoke(handle, hue)
    }

    override fun release(handle: MemorySegment) {
        checkNotNull(handles).release.invoke(handle)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun load(): Handles? {
        if (Platform.Current != Platform.MacOS) return null
        return try {
            // FFM's libraryLookup wants a real path, so the resource is extracted first.
            val arch = if (System.getProperty("os.arch") == "aarch64") "darwin-aarch64" else "darwin-x64"
            val resource = "/nucleus/native/$arch/liblab_swiftui.dylib"
            checkNotNull(FfmSwiftUiGateway::class.java.getResource(resource)) {
                "$resource missing — run :examples:lab:probes:rendering:buildRenderingNatives"
            }
            val file = LabPaths.extractResource(resource, FfmSwiftUiGateway::class.java)

            val linker = Linker.nativeLinker()
            val lookup = SymbolLookup.libraryLookup(file, Arena.ofShared())

            fun handle(
                name: String,
                descriptor: FunctionDescriptor,
            ) = linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)
            Handles(
                create = handle("nucleus_sample_swiftui_create", FunctionDescriptor.of(ValueLayout.ADDRESS)),
                view =
                    handle(
                        "nucleus_sample_swiftui_view",
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                setCounter =
                    handle(
                        "nucleus_sample_swiftui_set_counter",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    ),
                setHue =
                    handle(
                        "nucleus_sample_swiftui_set_hue",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT),
                    ),
                release =
                    handle(
                        "nucleus_sample_swiftui_release",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                    ),
            )
        } catch (t: Throwable) {
            failure = t.summary
            logger.log(Level.WARNING, "SwiftUI helper unavailable", t)
            null
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(FfmSwiftUiGateway::class.java.name)
    }
}
