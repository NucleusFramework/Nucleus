package dev.nucleusframework.globalhotkey

import dev.nucleusframework.core.runtime.Platform

/**
 * False on a developer's Wayland session, where registering goes through the
 * `org.freedesktop.portal.GlobalShortcuts` portal and every test that does so pops the
 * desktop's "add keyboard shortcuts" dialog. Those tests run on CI only (`CI` set, as on
 * GitHub Actions); run them locally with `CI=1 ./gradlew :global-hotkey:test`.
 *
 * Same backend rule as `detectBackend` in `nucleus_global_hotkey_linux.c`: X11 grabs keys
 * without a dialog, so X11 sessions, macOS and Windows keep running them everywhere.
 */
internal val portalRegistrationAllowed: Boolean
    get() {
        if (Platform.Current != Platform.Linux || System.getenv("CI") != null) return true
        val wayland = System.getenv("XDG_SESSION_TYPE") == "wayland" || System.getenv("WAYLAND_DISPLAY") != null
        return !wayland
    }
