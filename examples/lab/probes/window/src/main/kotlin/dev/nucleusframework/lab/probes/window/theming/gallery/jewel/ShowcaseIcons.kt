// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.nucleusframework.lab.probes.window.theming.gallery.jewel

import org.jetbrains.jewel.ui.icon.PathIconKey

/** Icons of the Jewel showcase, kept under `lab/jewel-gallery/` so they cannot clash on the classpath. */
object ShowcaseIcons {
    private const val ROOT = "lab/jewel-gallery/icons"

    private fun key(path: String) = PathIconKey("$ROOT/$path", ShowcaseIcons::class.java)

    val jewelLogo: PathIconKey = key("jewel-logo.svg")
    val markdown: PathIconKey = key("markdown.svg")
    val sunny: PathIconKey = key("sunny.svg")

    object Components {
        val banners: PathIconKey = key("components/banners.svg")
        val borders: PathIconKey = key("components/borders.svg")
        val brush: PathIconKey = key("components/brush.svg")
        val button: PathIconKey = key("components/button.svg")
        val checkbox: PathIconKey = key("components/checkBox.svg")
        val comboBox: PathIconKey = key("components/comboBox.svg")
        val links: PathIconKey = key("components/links.svg")
        val menu: PathIconKey = key("components/menu.svg")
        val progressBar: PathIconKey = key("components/progressbar.svg")
        val radioButton: PathIconKey = key("components/radioButton.svg")
        val scrollbar: PathIconKey = key("components/scrollbar.svg")
        val segmentedControls: PathIconKey = key("components/segmentedControl.svg")
        val slider: PathIconKey = key("components/slider.svg")
        val splitlayout: PathIconKey = key("components/splitLayout.svg")
        val tabs: PathIconKey = key("components/tabs.svg")
        val textArea: PathIconKey = key("components/textArea.svg")
        val textField: PathIconKey = key("components/textField.svg")
        val toolbar: PathIconKey = key("components/toolbar.svg")
        val tooltip: PathIconKey = key("components/tooltip.svg")
        val tree: PathIconKey = key("components/tree.svg")
        val typography: PathIconKey = key("components/typography.svg")
    }

    object ProgrammingLanguages {
        val Kotlin: PathIconKey = key("kotlin.svg")
    }
}
