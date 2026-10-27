package dev.nucleusframework.authentication

/**
 * The desktop window that owns the prompt. Only Windows parents it: the macOS panel and
 * the polkit agent's dialog belong to the system.
 *
 * With a Tao window: `AuthenticationParent.Windows(window.nativeHandle)` — or, from
 * `nucleus-application`, `NucleusWindow.authenticate`, which resolves it.
 */
public sealed interface AuthenticationParent {
    /** The app's frontmost window. */
    public data object Auto : AuthenticationParent

    /** A Windows `HWND`; a child window resolves to its top-level window. */
    public data class Windows(
        public val hwnd: Long,
    ) : AuthenticationParent
}
