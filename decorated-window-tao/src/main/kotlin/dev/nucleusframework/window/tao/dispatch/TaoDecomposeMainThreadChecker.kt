package dev.nucleusframework.window.tao.dispatch

import com.arkivanov.decompose.InternalDecomposeApi
import com.arkivanov.decompose.mainthread.MainThreadChecker

/**
 * Decompose `MainThreadChecker` that recognises the Tao main thread (#513).
 *
 * Decompose resolves its checker with `ServiceLoader.load(…).firstOrNull()`, and
 * `extensions-compose` registers `SwingMainThreadChecker`, which only accepts the
 * AWT EDT — never the UI thread under Tao, so every `childStack` / `childSlot`
 * created on `Dispatchers.Main` threw `NotOnMainThreadException`. Because only the
 * first provider counts, the Nucleus Gradle plugin also strips the Swing provider
 * from `extensions-compose` on the runtime classpath (`CleanNativeLibsTransform`),
 * leaving this one as the only candidate whatever the classpath order.
 *
 * The main thread is [TaoMainDispatcher.taoMainThread]: the native loop thread once
 * it runs, the pre-loop fallback thread before that. Until `Dispatchers.Main` has
 * been used there is no UI thread to compare against, so every thread is accepted.
 *
 * Discovered through `META-INF/services/com.arkivanov.decompose.mainthread.MainThreadChecker`;
 * Decompose is `compileOnly`, so without it on the classpath nothing loads this class.
 */
@OptIn(InternalDecomposeApi::class)
internal class TaoDecomposeMainThreadChecker : MainThreadChecker {
    override fun isMainThread(): Boolean {
        val taoMain = TaoMainDispatcher.taoMainThread
        return taoMain == null || Thread.currentThread() === taoMain
    }
}
