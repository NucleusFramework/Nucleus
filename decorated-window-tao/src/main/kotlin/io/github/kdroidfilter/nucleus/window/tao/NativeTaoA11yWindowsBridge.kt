package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * Loader stub for `nucleus_tao_a11y.dll` (Windows-only).
 *
 * The DLL itself is consumed entirely from the C side by `nucleus_tao.dll` —
 * the Rust crate resolves its exports via `GetModuleHandleW` + `GetProcAddress`
 * after Kotlin has triggered the load through this bridge. We don't declare
 * any `external` methods because the DLL has no JNI entry points; it's a pure
 * COM provider whose only public surface is `nucleus_tao_a11y_*` C exports.
 *
 * Loading the DLL here ensures `LoadLibraryW` registers it in the process,
 * so the Rust side's `GetModuleHandleW("nucleus_tao_a11y.dll")` succeeds.
 */
internal object NativeTaoA11yWindowsBridge {
    private val loaded: Boolean = run {
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            NativeLibraryLoader.load("nucleus_tao_a11y", NativeTaoA11yWindowsBridge::class.java)
        } else {
            false
        }
    }

    val isLoaded: Boolean get() = loaded
}
