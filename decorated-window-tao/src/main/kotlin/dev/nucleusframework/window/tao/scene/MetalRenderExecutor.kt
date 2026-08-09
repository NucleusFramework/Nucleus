package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Single-thread daemon executor for the macOS Metal render threads, where
 * every submitted task runs inside its own ObjC autorelease pool (#494).
 *
 * The render thread is a plain JVM thread, so without a pool the ObjC runtime
 * "just leaks" every object autoreleased on it: per rendered frame, the
 * `CAMetalDrawable` returned by `nextDrawable` and the `MTLCommandBuffer` (plus
 * encoders and driver objects) autoreleased inside skiko's `flushAndSubmit` —
 * ~20 MB/min of native memory while anything on screen animates. skiko's own
 * AWT `MetalRedrawer.mm` wraps its render entry points in `@autoreleasepool`
 * for the same reason.
 *
 * Draining per *task* rather than per JNI call covers the whole frame — begin
 * frame, skiko's flush (which autoreleases between our JNI calls), present and
 * vsync wait all run within one dispatch — and every other render-thread hop
 * (`DirectContext` create/close, TextureView imports) for free.
 */
internal fun newMetalRenderExecutor(threadName: String): ExecutorService =
    object : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(r, threadName).apply { isDaemon = true } },
    ) {
        // Confined to the single worker thread: beforeExecute/afterExecute
        // both run on the thread executing the task.
        private var pool = 0L

        override fun beforeExecute(
            t: Thread,
            r: Runnable,
        ) {
            if (NativeMetalBridge.isLoaded) {
                pool = NativeMetalBridge.nativeAutoreleasePoolPush()
            }
        }

        override fun afterExecute(
            r: Runnable,
            t: Throwable?,
        ) {
            val p = pool
            if (p != 0L) {
                pool = 0L
                NativeMetalBridge.nativeAutoreleasePoolPop(p)
            }
        }
    }
