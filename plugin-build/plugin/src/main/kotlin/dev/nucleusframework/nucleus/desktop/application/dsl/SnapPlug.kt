/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

/** Well-known Snap interface plugs. */
enum class SnapPlug(
    internal val id: String,
) {
    Desktop("desktop"),
    DesktopLegacy("desktop-legacy"),
    Home("home"),
    X11("x11"),
    Wayland("wayland"),
    Unity7("unity7"),
    BrowserSupport("browser-support"),
    Network("network"),
    NetworkBind("network-bind"),
    Gsettings("gsettings"),
    AudioPlayback("audio-playback"),
    AudioRecord("audio-record"),
    Opengl("opengl"),
    RemovableMedia("removable-media"),
    Cups("cups"),
}
