package dev.nucleusframework.nucleus.launcher.windows

/**
 * A named category in a Windows Jump List.
 *
 * Categories appear as labeled groups in the taskbar right-click menu.
 * Each category contains a list of [JumpListItem] entries.
 *
 * @property name The category display name (must be unique across categories).
 * @property items The items in this category.
 */
data class JumpListCategory(
    val name: String,
    val items: List<JumpListItem>,
)
