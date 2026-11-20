package dev.nucleusframework.core.runtime

/**
 * The window backend a Nucleus application is running on, resolved at runtime.
 *
 * External libraries (taskbar, notifications, tray, …) can branch on
 * [WindowBackend.Current] to adapt their behaviour — e.g. avoid touching the
 * AWT event dispatch thread when running on [Tao] — while depending only on
 * `core-runtime` (pure JVM, no Compose / no Tao).
 *
 * The active backend is recorded by `nucleusApplication` at launch via
 * [setActive]. When the host application does **not** use Nucleus, nothing
 * records it and [Current] falls back to [Awt] — the safe default, since a
 * plain Compose Desktop / Swing app is AWT-based.
 *
 * ```
 * if (WindowBackend.Current == WindowBackend.Tao) {
 *     // no AWT EDT available — post work through the native event loop instead
 * }
 * ```
 */
public enum class WindowBackend {
    /** AWT-bound windowing — a plain Compose Desktop / Swing app that does not use `nucleusApplication`. */
    Awt,

    /** No-AWT backend (`decorated-window-tao`), driven by a native event loop. */
    Tao,
    ;

    public companion object {
        @Volatile
        private var active: WindowBackend? = null

        /**
         * The backend the running application uses, or [Awt] when the app does
         * not use Nucleus (nothing has called [setActive]).
         */
        @JvmStatic
        public val Current: WindowBackend
            get() = active ?: Awt

        /**
         * `true` when a Nucleus entry point has recorded a backend — i.e. the
         * app is launched through `nucleusApplication`. When `false`, [Current]
         * is the [Awt] fallback rather than an explicitly resolved value.
         */
        @JvmStatic
        public val isNucleusManaged: Boolean
            get() = active != null

        /**
         * Records the resolved backend. Called once by Nucleus at application
         * launch; not intended for application or library code.
         */
        @JvmStatic
        public fun setActive(backend: WindowBackend) {
            active = backend
        }
    }
}
