/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

/** FreeDesktop.org main application categories for .desktop files. */
enum class AppImageCategory(
    internal val id: String,
) {
    AudioVideo("AudioVideo"),
    Development("Development"),
    Education("Education"),
    Game("Game"),
    Graphics("Graphics"),
    Network("Network"),
    Office("Office"),
    Science("Science"),
    Settings("Settings"),
    System("System"),
    Utility("Utility"),
}
