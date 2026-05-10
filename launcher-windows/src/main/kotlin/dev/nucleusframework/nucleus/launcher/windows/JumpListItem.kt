package dev.nucleusframework.nucleus.launcher.windows

/**
 * A single item in a Windows Jump List (custom category or user task).
 *
 * When clicked, Windows launches the application executable with the specified [arguments].
 * Use [SEPARATOR] to create a visual separator in the user tasks section.
 *
 * @property title Display text shown in the jump list.
 * @property arguments Command-line arguments passed to the app when this item is clicked.
 * @property description Tooltip text shown on hover.
 * @property icon Icon to display next to the item. Supports [TaskbarIconSource.FromStock],
 *   [TaskbarIconSource.FromFile], and [TaskbarIconSource.FromResource]. Null uses the app icon.
 * @property isSeparator Whether this entry is a visual separator (only valid in user tasks).
 */
data class JumpListItem(
    val title: String = "",
    val arguments: String = "",
    val description: String = "",
    val icon: TaskbarIconSource? = null,
    val isSeparator: Boolean = false,
) {
    companion object {
        /** A separator entry for the user tasks section. */
        val SEPARATOR = JumpListItem(isSeparator = true)
    }
}
