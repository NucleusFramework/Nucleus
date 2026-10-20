package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.NucleusApp

/**
 * The `WM_CLASS` the Tao windows carry on an X11 display. X11 desktops pair a window with its
 * launcher entry, and so show the app's icon in the dock, by matching `WM_CLASS` against the
 * entry's `StartupWMClass`.
 *
 * GTK 3 names the windows after the launcher binary (instance `warlock`, class `Warlock`), while
 * the plugin writes `StartupWMClass` as `linux.startupWMClass` or else the main class with dots
 * replaced by dashes, the name AWT's XToolkit uses. The two only agree when `startupWMClass` is
 * set to the binary's name, so the bridge applies the baked value ([NucleusApp.linuxStartupWmClass])
 * instead. That holds for the JVM packages and native images alike: both write the same entry.
 */
internal object TaoX11WmClass {
    /** The `WM_CLASS` to set, or `null` to keep GTK's default (the launcher binary's name). */
    fun resolve(startupWmClass: String?): String? = startupWmClass?.trim()?.takeIf { it.isNotEmpty() }

    /** [resolve] for this process. */
    fun current(): String? = resolve(NucleusApp.linuxStartupWmClass)
}
