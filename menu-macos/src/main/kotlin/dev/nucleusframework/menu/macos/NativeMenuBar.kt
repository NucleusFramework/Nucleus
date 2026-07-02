package dev.nucleusframework.menu.macos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// ─── Public composable ───────────────────────────────────────────────────────

/**
 * Installs a native macOS application menu bar that reacts to Compose state.
 *
 * The menu bar is rebuilt whenever a state read inside [content] changes.
 * The original menu bar is saved on first composition and restored on disposal.
 *
 * The **first** [NativeMenuBarScope.Menu] call produces the application menu
 * (the bold entry in the menu bar whose title is the app name).
 *
 * Submenus titled exactly `"Services"`, `"Window"`, or `"Help"` are
 * automatically registered with macOS so the system can populate them
 * (see module documentation for details).
 *
 * ```kotlin
 * NativeMenuBar {
 *     Menu("MyApp") {
 *         Item("About MyApp", icon = NsMenuItemImage.SystemSymbol("info.circle")) { }
 *         Separator()
 *         Item("Quit", shortcut = NativeKeyShortcut("q")) { exitProcess(0) }
 *     }
 *     Menu("File") {
 *         Item("New", shortcut = NativeKeyShortcut("n")) { println("New") }
 *     }
 * }
 * ```
 *
 * @param content Builder lambda executed in [NativeMenuBarScope].
 */
@Composable
fun NativeMenuBar(content: NativeMenuBarScope.() -> Unit) {
    if (!NsMenu.isAvailable) return

    val savedMenu = remember { NsMenu.mainMenu }
    var currentMenu by remember { mutableStateOf<NsMenu?>(null) }

    val scope = NativeMenuBarScope()
    scope.content()

    SideEffect {
        currentMenu?.close()
        NativeNsMenuBridge.clearAllActions()
        val (menu, roleHandles) = materializeMenuBar(scope.entries)
        currentMenu = menu
        NsMenu.setMainMenu(menu)
        // Register well-known menus after setMainMenu so macOS picks them up.
        for ((role, handle) in roleHandles) {
            when (role) {
                MenuRole.Window -> NativeNsMenuBridge.nativeSetWindowsMenu(handle)
                MenuRole.Help -> NativeNsMenuBridge.nativeSetHelpMenu(handle)
                MenuRole.None -> { /* nothing */ }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            currentMenu?.close()
            NativeNsMenuBridge.clearAllActions()
            savedMenu?.let {
                NsMenu.setMainMenu(it)
                it.close()
            }
        }
    }
}

// ─── Scopes ──────────────────────────────────────────────────────────────────

/** Receiver scope for [NativeMenuBar]. Use [Menu] to add top-level menus. */
class NativeMenuBarScope internal constructor() {
    @PublishedApi internal val entries = mutableListOf<MenuBarEntry>()

    /**
     * Adds a top-level menu to the menu bar.
     *
     * @param text Title shown in the menu bar.
     * @param enabled Whether the menu is clickable.
     * @param mnemonic Ignored on macOS (accepted for API compatibility).
     * @param content Items inside this menu.
     */
    @Suppress("UnusedParameter")
    fun Menu(
        text: String,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        content: NativeMenuScope.() -> Unit,
    ) {
        val scope = NativeMenuScope()
        scope.content()
        entries += MenuBarEntry(text, enabled, scope.entries.toList(), MenuRole.None)
    }

    /**
     * Adds the Window menu to the menu bar.
     *
     * macOS automatically populates this menu with the window list
     * and "Bring All to Front", regardless of the displayed title.
     *
     * On macOS 12 and earlier, AppKit cannot auto-populate an **empty**
     * Window menu (it raises an internal range exception, which is caught
     * natively so the app survives). Provide at least one item — e.g.
     * "Minimize"/"Zoom" — to have the menu installed on those systems.
     *
     * @param text Localized title shown in the menu bar.
     * @param content Items inside this menu (optional on macOS 13+; macOS adds its own).
     */
    fun MenuWindow(
        text: String,
        enabled: Boolean = true,
        content: NativeMenuScope.() -> Unit = {},
    ) {
        val scope = NativeMenuScope()
        scope.content()
        entries += MenuBarEntry(text, enabled, scope.entries.toList(), MenuRole.Window)
    }

    /**
     * Adds the Help menu to the menu bar.
     *
     * macOS automatically adds a search field at the top of this menu,
     * regardless of the displayed title.
     *
     * @param text Localized title shown in the menu bar.
     * @param content Items inside this menu (optional; macOS adds its own).
     */
    fun MenuHelp(
        text: String,
        enabled: Boolean = true,
        content: NativeMenuScope.() -> Unit = {},
    ) {
        val scope = NativeMenuScope()
        scope.content()
        entries += MenuBarEntry(text, enabled, scope.entries.toList(), MenuRole.Help)
    }
}

/**
 * Receiver scope for menu content. Provides [Item], [CheckboxItem],
 * [RadioButtonItem], [Separator], [SectionHeader], and nested [Menu].
 */
class NativeMenuScope internal constructor() {
    @PublishedApi internal val entries = mutableListOf<MenuItemEntry>()

    // ── Item ─────────────────────────────────────────────────────────────

    /**
     * Adds a regular menu item.
     *
     * @param text Item title.
     * @param enabled Whether the item is clickable.
     * @param mnemonic Ignored on macOS.
     * @param shortcut Keyboard shortcut (e.g. `NativeKeyShortcut("s")` for ⌘S).
     * @param icon Native image (SF Symbol, named image, or file path).
     * @param state Checkmark state ([NsMenuItemState.OFF], [NsMenuItemState.ON], [NsMenuItemState.MIXED]).
     * @param tag Numeric identifier for programmatic lookup.
     * @param badge Badge displayed to the right of the item (macOS 14+).
     * @param subtitle Secondary text below the title (macOS 14.4+).
     * @param toolTip Tooltip shown on hover.
     * @param indentationLevel Visual indentation (0–15).
     * @param isAlternate If true, this item is an alternate shown when Option is held.
     *   Must immediately follow the base item and share the same key equivalent.
     * @param isHidden If true, the item is invisible but shortcuts still work.
     * @param onStateImage Image shown when state is ON.
     * @param offStateImage Image shown when state is OFF.
     * @param mixedStateImage Image shown when state is MIXED.
     * @param onClick Callback when clicked (dispatched on Swing EDT). Trailing lambda.
     */
    @Suppress("UnusedParameter")
    fun Item(
        text: String,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        shortcut: NativeKeyShortcut? = null,
        icon: NsMenuItemImage? = null,
        state: NsMenuItemState = NsMenuItemState.OFF,
        tag: Int = 0,
        badge: NsMenuItemBadge? = null,
        subtitle: String? = null,
        toolTip: String? = null,
        indentationLevel: Int = 0,
        isAlternate: Boolean = false,
        isHidden: Boolean = false,
        onStateImage: NsMenuItemImage? = null,
        offStateImage: NsMenuItemImage? = null,
        mixedStateImage: NsMenuItemImage? = null,
        onClick: () -> Unit = {},
    ) {
        entries +=
            MenuItemEntry.Regular(
                text = text,
                onClick = onClick,
                enabled = enabled,
                shortcut = shortcut,
                icon = icon,
                state = state,
                tag = tag,
                badge = badge,
                subtitle = subtitle,
                toolTip = toolTip,
                indentationLevel = indentationLevel,
                isAlternate = isAlternate,
                isHidden = isHidden,
                onStateImage = onStateImage,
                offStateImage = offStateImage,
                mixedStateImage = mixedStateImage,
            )
    }

    // ── CheckboxItem ─────────────────────────────────────────────────────

    /**
     * Adds a menu item that toggles between checked (✓) and unchecked states.
     *
     * Convenience wrapper around [Item] with automatic [NsMenuItemState] mapping.
     */
    @Suppress("UnusedParameter")
    fun CheckboxItem(
        text: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        shortcut: NativeKeyShortcut? = null,
        icon: NsMenuItemImage? = null,
        tag: Int = 0,
        badge: NsMenuItemBadge? = null,
        subtitle: String? = null,
        toolTip: String? = null,
        indentationLevel: Int = 0,
    ) {
        Item(
            text = text,
            onClick = { onCheckedChange(!checked) },
            enabled = enabled,
            shortcut = shortcut,
            icon = icon,
            state = if (checked) NsMenuItemState.ON else NsMenuItemState.OFF,
            tag = tag,
            badge = badge,
            subtitle = subtitle,
            toolTip = toolTip,
            indentationLevel = indentationLevel,
        )
    }

    // ── RadioButtonItem ──────────────────────────────────────────────────

    /**
     * Adds a menu item that represents a radio selection (● when selected).
     *
     * Uses [NsMenuItemState.ON] when [selected] and [NsMenuItemState.OFF] otherwise.
     * Group your radio items together and manage selection state externally.
     */
    @Suppress("UnusedParameter")
    fun RadioButtonItem(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        shortcut: NativeKeyShortcut? = null,
        icon: NsMenuItemImage? = null,
        tag: Int = 0,
        badge: NsMenuItemBadge? = null,
        subtitle: String? = null,
        toolTip: String? = null,
        indentationLevel: Int = 0,
    ) {
        Item(
            text = text,
            onClick = onClick,
            enabled = enabled,
            shortcut = shortcut,
            icon = icon,
            state = if (selected) NsMenuItemState.ON else NsMenuItemState.OFF,
            tag = tag,
            badge = badge,
            subtitle = subtitle,
            toolTip = toolTip,
            indentationLevel = indentationLevel,
        )
    }

    // ── SearchField ──────────────────────────────────────────────────────

    /**
     * Adds a search field to the menu (Chrome-History-style): typing filters the menu's other
     * items natively by title (case/diacritic-insensitive), separators are hidden while a query
     * is active, and everything resets when the menu closes. The field grabs keyboard focus
     * when the menu opens.
     *
     * @param placeholder Placeholder text shown in the empty field.
     * @param width Field width in points.
     */
    fun SearchField(
        placeholder: String = "",
        width: Double = 230.0,
    ) {
        entries += MenuItemEntry.SearchFieldEntry(placeholder, width)
    }

    // ── Separator ────────────────────────────────────────────────────────

    /** Adds a horizontal separator line. */
    fun Separator() {
        entries += MenuItemEntry.SeparatorEntry
    }

    // ── SectionHeader ────────────────────────────────────────────────────

    /** Adds a non-clickable section header (macOS 14+). Falls back to a disabled item on older systems. */
    fun SectionHeader(title: String) {
        entries += MenuItemEntry.SectionHeaderEntry(title)
    }

    // ── Nested Menu ──────────────────────────────────────────────────────

    /**
     * Adds a submenu. Can be nested to arbitrary depth.
     *
     * @param text Title of the submenu item.
     * @param enabled Whether the submenu is clickable.
     * @param icon Native image for the submenu item.
     * @param content Items inside this submenu.
     */
    @Suppress("UnusedParameter")
    fun Menu(
        text: String,
        enabled: Boolean = true,
        mnemonic: Char? = null,
        icon: NsMenuItemImage? = null,
        content: NativeMenuScope.() -> Unit,
    ) {
        val scope = NativeMenuScope()
        scope.content()
        entries += MenuItemEntry.SubmenuEntry(text, enabled, icon, scope.entries.toList())
    }
}

// ─── Description types (internal) ────────────────────────────────────────────

/** Role hint for macOS well-known menus. */
enum class MenuRole { None, Window, Help }

@PublishedApi
internal data class MenuBarEntry(
    val text: String,
    val enabled: Boolean,
    val items: List<MenuItemEntry>,
    val role: MenuRole = MenuRole.None,
)

@PublishedApi
internal sealed class MenuItemEntry {
    data class Regular(
        val text: String,
        val onClick: () -> Unit,
        val enabled: Boolean,
        val shortcut: NativeKeyShortcut?,
        val icon: NsMenuItemImage?,
        val state: NsMenuItemState,
        val tag: Int,
        val badge: NsMenuItemBadge?,
        val subtitle: String?,
        val toolTip: String?,
        val indentationLevel: Int,
        val isAlternate: Boolean,
        val isHidden: Boolean,
        val onStateImage: NsMenuItemImage?,
        val offStateImage: NsMenuItemImage?,
        val mixedStateImage: NsMenuItemImage?,
    ) : MenuItemEntry()

    data class SubmenuEntry(
        val text: String,
        val enabled: Boolean,
        val icon: NsMenuItemImage?,
        val items: List<MenuItemEntry>,
    ) : MenuItemEntry()

    object SeparatorEntry : MenuItemEntry()

    data class SearchFieldEntry(
        val placeholder: String,
        val width: Double,
    ) : MenuItemEntry()

    data class SectionHeaderEntry(
        val title: String,
    ) : MenuItemEntry()
}

// ─── Materialization (description → native NSMenu tree) ──────────────────────

private fun materializeMenuBar(entries: List<MenuBarEntry>): Pair<NsMenu, List<Pair<MenuRole, Long>>> {
    val menuBar = NsMenu("MainMenu")
    val roleHandles = mutableListOf<Pair<MenuRole, Long>>()
    for (entry in entries) {
        val menuItem = NsMenuItem(entry.text)
        if (!entry.enabled) menuItem.isEnabled = false
        val submenu = NsMenu(entry.text)
        submenu.autoenablesItems = false
        materializeItems(submenu, entry.items)
        menuItem.submenu = submenu
        menuBar.addItem(menuItem)

        if (entry.role != MenuRole.None) {
            roleHandles += entry.role to submenu.handle
        }

        menuItem.close()
        submenu.close()
    }
    return menuBar to roleHandles
}

@Suppress("CyclomaticComplexMethod")
private fun materializeItems(
    menu: NsMenu,
    entries: List<MenuItemEntry>,
) {
    for (entry in entries) {
        when (entry) {
            is MenuItemEntry.SeparatorEntry -> {
                val sep = NsMenuItem.separator()
                menu.addItem(sep)
                sep.close()
            }
            is MenuItemEntry.SectionHeaderEntry -> {
                val header = NsMenuItem.sectionHeader(entry.title)
                menu.addItem(header)
                header.close()
            }
            is MenuItemEntry.SearchFieldEntry -> {
                val field = NsMenuItem.searchField(entry.placeholder, entry.width)
                menu.addItem(field)
                field.close()
            }
            is MenuItemEntry.SubmenuEntry -> {
                val menuItem = NsMenuItem(entry.text)
                if (!entry.enabled) menuItem.isEnabled = false
                entry.icon?.let { menuItem.image = it }
                val submenu = NsMenu(entry.text)
                submenu.autoenablesItems = false
                materializeItems(submenu, entry.items)
                menuItem.submenu = submenu
                menu.addItem(menuItem)
                menuItem.close()
                submenu.close()
            }
            is MenuItemEntry.Regular -> {
                val (keyEq, modMask) = mapShortcut(entry.shortcut)
                val item = NsMenuItem(entry.text, keyEquivalent = keyEq)
                if (keyEq.isNotEmpty()) item.keyEquivalentModifierMask = modMask
                if (!entry.enabled) item.isEnabled = false
                if (entry.isHidden) item.isHidden = true
                if (entry.isAlternate) item.isAlternate = true
                if (entry.state != NsMenuItemState.OFF) item.state = entry.state
                if (entry.tag != 0) item.tag = entry.tag
                if (entry.indentationLevel > 0) item.indentationLevel = entry.indentationLevel
                entry.icon?.let { item.image = it }
                entry.badge?.let { item.badge = it }
                entry.subtitle?.let { item.subtitle = it }
                entry.toolTip?.let { item.toolTip = it }
                entry.onStateImage?.let { item.onStateImage = it }
                entry.offStateImage?.let { item.offStateImage = it }
                entry.mixedStateImage?.let { item.mixedStateImage = it }
                item.onAction = entry.onClick
                menu.addItem(item)
                item.close()
            }
        }
    }
}

// ─── NativeKeyShortcut ───────────────────────────────────────────────────────

/**
 * Describes a keyboard shortcut for a native macOS menu item.
 *
 * @param key The key equivalent character (e.g. `"c"`, `","`, `"?"`, [NativeKey.ESCAPE]).
 * @param command Command (⌘) modifier. **Tip:** this is the macOS equivalent of `ctrl` on other platforms.
 * @param shift Shift (⇧) modifier.
 * @param option Option (⌥) modifier.
 * @param control Control (⌃) modifier. Rarely used — prefer [command].
 * @param function Function (Fn) modifier.
 */
data class NativeKeyShortcut(
    val key: String,
    val command: Boolean = true,
    val shift: Boolean = false,
    val option: Boolean = false,
    val control: Boolean = false,
    val function: Boolean = false,
)

/** Well-known non-printable key equivalent characters for [NativeKeyShortcut]. */
object NativeKey {
    const val ESCAPE: String = "\u001B"
    const val RETURN: String = "\r"
    const val TAB: String = "\t"
    const val DELETE: String = "\u007F"
    const val BACKSPACE: String = "\u0008"
    const val UP: String = "\uF700"
    const val DOWN: String = "\uF701"
    const val LEFT: String = "\uF702"
    const val RIGHT: String = "\uF703"
    const val F1: String = "\uF704"
    const val F2: String = "\uF705"
    const val F3: String = "\uF706"
    const val F4: String = "\uF707"
    const val F5: String = "\uF708"
    const val F6: String = "\uF709"
    const val F7: String = "\uF70A"
    const val F8: String = "\uF70B"
    const val F9: String = "\uF70C"
    const val F10: String = "\uF70D"
    const val F11: String = "\uF70E"
    const val F12: String = "\uF70F"
    const val HOME: String = "\uF729"
    const val END: String = "\uF72B"
    const val PAGE_UP: String = "\uF72C"
    const val PAGE_DOWN: String = "\uF72D"
}

// ─── Shortcut → NSMenu key equivalent mapping ───────────────────────────────

private fun mapShortcut(shortcut: NativeKeyShortcut?): Pair<String, Int> {
    if (shortcut == null) return "" to 0
    val keyEq = shortcut.key
    if (keyEq.isEmpty()) return "" to 0
    var modifiers = 0
    if (shortcut.command) modifiers = modifiers or NsEventModifierFlags.COMMAND
    if (shortcut.shift) modifiers = modifiers or NsEventModifierFlags.SHIFT
    if (shortcut.option) modifiers = modifiers or NsEventModifierFlags.OPTION
    if (shortcut.control) modifiers = modifiers or NsEventModifierFlags.CONTROL
    if (shortcut.function) modifiers = modifiers or NsEventModifierFlags.FUNCTION
    return keyEq to modifiers
}
