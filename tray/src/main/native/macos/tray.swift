import Cocoa
import Foundation

// Types for C callbacks
public typealias TrayCallback = @convention(c) (UnsafeMutableRawPointer?) -> Void
public typealias MenuItemCallback = @convention(c) (UnsafeMutableRawPointer?) -> Void
public typealias ThemeCallback = @convention(c) (Int32) -> Void

// MARK: - Static globals (kept for C interop)
private var loopStatus: Int32 = 0
private var trayInstance: UnsafeMutableRawPointer? = nil
private var app: NSApplication? = nil
private var statusBar: NSStatusBar? = nil
private var themeCallback: ThemeCallback? = nil

// Per-instance context keyed by the raw tray pointer (struct tray*)
private class TrayContext {
    let statusItem: NSStatusItem
    let clickHandler: InstanceButtonClickHandler
    var contextMenu: NSMenu?
    let appearanceObserver: MenuBarAppearanceObserver
    var lightImage: NSImage?
    var darkImage: NSImage?
    var menuOpenedCallback: TrayCallback?
    init(statusItem: NSStatusItem, clickHandler: InstanceButtonClickHandler, appearanceObserver: MenuBarAppearanceObserver) {
        self.statusItem = statusItem
        self.clickHandler = clickHandler
        self.appearanceObserver = appearanceObserver
    }
}

private var contexts: [UnsafeMutableRawPointer: TrayContext] = [:]

// MARK: - Menu delegate
private class MenuDelegate: NSObject, NSMenuDelegate {
    @objc func menuItemClicked(_ sender: NSMenuItem) {
        guard let menuItemPtr = sender.representedObject as? UnsafeMutableRawPointer else { return }
        let callbackPtr = menuItemPtr.advanced(by: 24)
            .assumingMemoryBound(to: MenuItemCallback?.self)
        callbackPtr.pointee?(menuItemPtr)
    }
}

// MARK: - Unified click handler per instance
@objc private class InstanceButtonClickHandler: NSObject {
    private let trayPtr: UnsafeMutableRawPointer
    init(trayPtr: UnsafeMutableRawPointer) { self.trayPtr = trayPtr }

    @objc func handleClick(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else { return }
        guard let ctx = contexts[trayPtr] else { return }

        if event.type == .rightMouseUp || event.modifierFlags.contains(.control) {
            if let menu = ctx.statusItem.menu != nil ? ctx.statusItem.menu : ctx.contextMenu {
                ctx.menuOpenedCallback?(trayPtr)
                ctx.statusItem.menu = menu
                sender.performClick(nil)
                ctx.statusItem.menu = nil
            }
        } else if event.type == .leftMouseUp {
            let callbackPtr = trayPtr.advanced(by: 24)
                .assumingMemoryBound(to: TrayCallback?.self)
            callbackPtr.pointee?(trayPtr)
        }
    }
}

// MARK: - Appearance observer with ultra‑low latency
/// Detects menu‑bar theme changes in <10 ms using KVO + GCD debouncing.
private class MenuBarAppearanceObserver {
    private var observation: NSKeyValueObservation?
    private var workItem: DispatchWorkItem?
    private var settleItem: DispatchWorkItem?
    private var lastAppearance: NSAppearance.Name?
    private var lastSystemTheme: Bool?  // Track system theme separately
    private let trayPtr: UnsafeMutableRawPointer?

    /// Debounce delay before first evaluation (keep tiny but non‑zero).
    private let debounce: TimeInterval = 0.01   // 10 ms
    /// Settling delay to avoid reporting intermediate states.
    private let settle: TimeInterval  = 0.005   // 5 ms

    init(trayPtr: UnsafeMutableRawPointer? = nil) {
        self.trayPtr = trayPtr
    }

    func startObserving(_ statusItem: NSStatusItem) {
        // Observe menu bar appearance changes
        observation = statusItem.button?.observe(
            \.effectiveAppearance,
            options: [.initial, .new]
        ) { [weak self] button, _ in
            self?.scheduleCheck(for: button.effectiveAppearance)
        }

        // Observe system-wide theme changes via DistributedNotificationCenter
        DistributedNotificationCenter.default().addObserver(
            forName: NSNotification.Name("AppleInterfaceThemeChangedNotification"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleSystemThemeChange()
        }

        // Initial update of menu appearance
        handleSystemThemeChange()
    }

    private func handleSystemThemeChange() {
        let isDark = UserDefaults.standard.string(forKey: "AppleInterfaceStyle") == "Dark"

        // Only update if theme actually changed
        if lastSystemTheme != isDark {
            lastSystemTheme = isDark

            // Update menu appearance for all contexts
            if let ptr = trayPtr, let ctx = contexts[ptr] {
                if let menu = ctx.contextMenu {
                    updateMenuAppearance(menu, to: systemAppearance())
                }
            }
        }
    }

    private func scheduleCheck(for appearance: NSAppearance) {
        workItem?.cancel()

        let item = DispatchWorkItem { [weak self] in
            self?.evaluate(appearance)
        }
        workItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + debounce, execute: item)
    }

    private func evaluate(_ appearance: NSAppearance) {
        guard let matched = appearance.bestMatch(from: [.darkAqua, .aqua]),
              matched != lastAppearance else { return }
        lastAppearance = matched

        // Swap cached icon instantly if available
        if let ptr = trayPtr, let ctx = contexts[ptr] {
            let isDark = matched == .darkAqua
            if let img = isDark ? ctx.darkImage : ctx.lightImage {
                ctx.statusItem.button?.image = img
            }

            // Update menu appearance to match the system theme (not menu bar)
            if let menu = ctx.contextMenu {
                updateMenuAppearance(menu, to: systemAppearance())
            }
        }

        // Cancel any pending settle callback before scheduling a new one.
        settleItem?.cancel()

        // Allow the system a single run‑loop to settle, then notify.
        let item = DispatchWorkItem {
            themeCallback?(matched == .darkAqua ? 1 : 0)
        }
        settleItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + settle, execute: item)
    }

    func invalidate() {
        observation?.invalidate()
        observation = nil
        workItem?.cancel()
        settleItem?.cancel()

        // Remove system theme observer
        DistributedNotificationCenter.default().removeObserver(
            self,
            name: NSNotification.Name("AppleInterfaceThemeChangedNotification"),
            object: nil
        )
    }
}

// MARK: - Globals that need to live for app lifetime
private var menuDelegate: MenuDelegate?

// MARK: - Helpers

/// Returns the system-wide appearance (not the menu bar appearance)
private func systemAppearance() -> NSAppearance {
    // Check if system is in dark mode via UserDefaults
    let isDarkMode = UserDefaults.standard.string(forKey: "AppleInterfaceStyle") == "Dark"
    let appearanceName: NSAppearance.Name = isDarkMode ? .darkAqua : .aqua
    return NSAppearance(named: appearanceName) ?? NSApp.effectiveAppearance
}

/// Updates the appearance of a menu and all its submenus recursively
private func updateMenuAppearance(_ menu: NSMenu, to appearance: NSAppearance) {
    menu.appearance = appearance

    // Recursively update all submenus
    for item in menu.items {
        if let submenu = item.submenu {
            updateMenuAppearance(submenu, to: appearance)
        }
    }
}
private func nativeMenu(from menuPtr: UnsafeMutableRawPointer, statusItem: NSStatusItem? = nil) -> NSMenu {
    let menu = NSMenu()
    menu.autoenablesItems = false
    menu.delegate = menuDelegate

    // Set menu appearance to match the system theme (not menu bar)
    menu.appearance = systemAppearance()

    var currentPtr = menuPtr
    while true {
        guard let textPtr = currentPtr.load(as: UnsafePointer<CChar>?.self) else { break }
        let title = String(cString: textPtr)

        if title == "-" {
            menu.addItem(NSMenuItem.separator())
        } else {
            // Charger les champs avec les nouveaux offsets
            let iconPathPtr = currentPtr.advanced(by: 8).load(as: UnsafePointer<CChar>?.self)
            let disabled = currentPtr.advanced(by: 16).load(as: Int32.self) == 1
            let checked  = currentPtr.advanced(by: 20).load(as: Int32.self) == 1
            let callback = currentPtr.advanced(by: 24).load(as: MenuItemCallback?.self)
            let submenu  = currentPtr.advanced(by: 32).load(as: UnsafeMutableRawPointer?.self)

            let keyEquivPtr = currentPtr.advanced(by: 40).load(as: UnsafePointer<CChar>?.self)
            let keyEquivModMask = currentPtr.advanced(by: 48).load(as: UInt.self)

            let keyEquiv = keyEquivPtr.flatMap({ String(cString: $0) }) ?? ""
            let item = NSMenuItem(title: title, action: nil, keyEquivalent: keyEquiv)
            if keyEquivPtr != nil {
                item.keyEquivalentModifierMask = NSEvent.ModifierFlags(rawValue: keyEquivModMask)
            }
            item.isEnabled = !disabled
            item.state = checked ? .on : .off
            item.representedObject = currentPtr

            // Menu icons are 16pt; the JVM ships a ≥3x master (see forMenuItem).
            if let iconPath = iconPathPtr.flatMap({ String(cString: $0) }),
               let image = NSImage(contentsOfFile: iconPath) {
                image.size = NSSize(width: 16, height: 16)
                item.image = image
            }

            if callback != nil {
                item.target = menuDelegate
                item.action = #selector(MenuDelegate.menuItemClicked(_:))
            }
            menu.addItem(item)

            if let submenuPtr = submenu {
                menu.setSubmenu(nativeMenu(from: submenuPtr, statusItem: statusItem), for: item)
            }
        }

        currentPtr = currentPtr.advanced(by: 56)  // Stride includes key_equivalent + mod_mask fields
    }
    return menu
}

// MARK: - C shim
@_cdecl("tray_get_instance")
public func tray_get_instance() -> UnsafeMutableRawPointer? { trayInstance }

@_cdecl("tray_init")
public func tray_init(_ tray: UnsafeMutableRawPointer) -> Int32 {
    if !Thread.isMainThread {
        return DispatchQueue.main.sync { tray_init(tray) }
    }

    loopStatus = 0

    if app == nil { app = NSApplication.shared }
    if statusBar == nil { statusBar = NSStatusBar.system }
    if menuDelegate == nil { menuDelegate = MenuDelegate() }

    // Create a new status item for this tray instance
    guard let bar = statusBar else { return -1 }
    let statusItem = bar.statusItem(withLength: NSStatusItem.variableLength)

    let observer = MenuBarAppearanceObserver(trayPtr: tray)
    let clickHandler = InstanceButtonClickHandler(trayPtr: tray)

    let ctx = TrayContext(statusItem: statusItem, clickHandler: clickHandler, appearanceObserver: observer)
    // Register context BEFORE starting observation so the .initial KVO fires with context available
    contexts[tray] = ctx

    observer.startObserving(statusItem)

    // First-time update sets image/tooltip/menu and target/action
    tray_update(tray)
    return 0
}

@_cdecl("tray_loop")
public func tray_loop(_ blocking: Int32) -> Int32 {
    if !Thread.isMainThread {
        return DispatchQueue.main.sync { tray_loop(blocking) }
    }

    let until = blocking != 0 ? Date.distantFuture : Date.distantPast
    if let event = app?.nextEvent(matching: .any, until: until, inMode: .default, dequeue: true) {
        app?.sendEvent(event)
    }
    return loopStatus
}

@_cdecl("tray_update")
public func tray_update(_ tray: UnsafeMutableRawPointer) {
    if !Thread.isMainThread {
        return DispatchQueue.main.async { tray_update(tray) }
    }

    trayInstance = tray

    guard let ctx = contexts[tray] else { return }
    let statusItem = ctx.statusItem

    let iconPathPtr  = tray.load(as: UnsafePointer<CChar>?.self)
    let tooltipPtr   = tray.advanced(by: 8).load(as: UnsafePointer<CChar>?.self)
    let menuPtr      = tray.advanced(by: 16).load(as: UnsafeMutableRawPointer?.self)
    let callbackPtr  = tray.advanced(by: 24).load(as: TrayCallback?.self)

    // Update icon
    if let iconPath = iconPathPtr.flatMap({ String(cString: $0) }),
       let image = NSImage(contentsOfFile: iconPath) {
        let height = min(NSStatusBar.system.thickness - 4, 18)
        let width  = image.size.width * (height / image.size.height)
        image.size = NSSize(width: width, height: height)
        statusItem.button?.image = image
    }

    // Update tooltip
    statusItem.button?.toolTip = tooltipPtr.flatMap { String(cString: $0) }

    // Menu and actions configuration
    statusItem.menu = nil

    if let menuPtr = menuPtr {
        ctx.contextMenu = nativeMenu(from: menuPtr, statusItem: statusItem)
    } else {
        ctx.contextMenu = nil
    }

    if callbackPtr != nil || ctx.contextMenu != nil {
        statusItem.button?.target = ctx.clickHandler
        statusItem.button?.action = #selector(InstanceButtonClickHandler.handleClick(_:))
        statusItem.button?.sendAction(on: NSEvent.EventTypeMask.leftMouseUp.union(.rightMouseUp))
    } else {
        statusItem.button?.target = nil
        statusItem.button?.action = nil
    }
}

@_cdecl("tray_dispose")
public func tray_dispose(_ tray: UnsafeMutableRawPointer) {
    if !Thread.isMainThread {
        return DispatchQueue.main.async { tray_dispose(tray) }
    }
    if let ctx = contexts.removeValue(forKey: tray) {
        ctx.appearanceObserver.invalidate()
        NSStatusBar.system.removeStatusItem(ctx.statusItem)
        if trayInstance == tray { trayInstance = nil }
    }
}

@_cdecl("tray_exit")
public func tray_exit() {
    if !Thread.isMainThread {
        return DispatchQueue.main.async { tray_exit() }
    }

    loopStatus = -1

    // Dispose all existing status items
    for (ptr, ctx) in contexts {
        ctx.appearanceObserver.invalidate()
        NSStatusBar.system.removeStatusItem(ctx.statusItem)
        if trayInstance == ptr { trayInstance = nil }
    }
    contexts.removeAll()

    // Optionally release delegates
    menuDelegate = nil
}

@_cdecl("tray_set_menu_opened_callback")
public func tray_set_menu_opened_callback(
    _ tray: UnsafeMutableRawPointer?,
    _ cb: TrayCallback?
) {
    let doWork = {
        guard let tray = tray, let ctx = contexts[tray] else { return }
        ctx.menuOpenedCallback = cb
    }
    if Thread.isMainThread { doWork() }
    else { DispatchQueue.main.sync { doWork() } }
}

@_cdecl("tray_set_theme_callback")
public func tray_set_theme_callback(_ cb: @escaping ThemeCallback) {
    themeCallback = cb
}

@_cdecl("tray_is_menu_dark")
public func tray_is_menu_dark() -> Int32 {
    if !Thread.isMainThread {
        return DispatchQueue.main.sync { tray_is_menu_dark() }
    }
    let ctx = trayInstance.flatMap { contexts[$0] } ?? contexts.values.first
    let appearance: NSAppearance
    if let button = ctx?.statusItem.button {
        appearance = button.effectiveAppearance
    } else {
        appearance = NSApp.effectiveAppearance
    }
    guard let name = appearance.bestMatch(from: [.darkAqua, .aqua]) else {
        return 1
    }
    return name == .darkAqua ? 1 : 0
}

// MARK: - Status‑item geometry (exported to C)

/// Laid-out status-item rect in screen coordinates, or `nil` if AppKit has not
/// assigned the item a menu-bar slot yet. A brand-new `NSStatusItem` is parked
/// at the menu bar's left edge (relative X ≈ 0); reporting that as precise
/// lands the TrayApp popup at the top-left of the screen.
private struct StatusItemGeometry {
    let rect: NSRect
    let screen: NSScreen
}

private func laidOutGeometry(for ctx: TrayContext) -> StatusItemGeometry? {
    guard
        let button = ctx.statusItem.button,
        let window = button.window,
        let screen = window.screen
    else { return nil }

    var rect = button.convert(button.bounds, to: nil as NSView?)
    rect = window.convertToScreen(rect)
    guard rect.width >= 1, rect.height >= 1 else { return nil }

    // Menu extras always live on the right of the menu bar. Relative X is
    // measured against the screen that owns the item, so a left-arranged
    // external display (origin.x < 0) still reports a large relative X.
    let relativeX = rect.minX - screen.frame.minX
    guard relativeX >= 8 else { return nil }
    return StatusItemGeometry(rect: rect, screen: screen)
}

private func writeFlippedPosition(_ geometry: StatusItemGeometry,
                                  x: UnsafeMutablePointer<Int32>?,
                                  y: UnsafeMutablePointer<Int32>?) {
    x?.pointee = Int32(lround(geometry.rect.midX))
    let primaryHeight = NSScreen.screens.first?.frame.height ?? geometry.screen.frame.height
    y?.pointee = Int32(primaryHeight - geometry.rect.maxY)
}

// Returns 1 if the coordinate is precise, 0 if we had to use a fallback.
@_cdecl("tray_get_status_item_position")
public func tray_get_status_item_position(
    _ x: UnsafeMutablePointer<Int32>?,
    _ y: UnsafeMutablePointer<Int32>?
) -> Int32
{
    let ctx = trayInstance.flatMap { contexts[$0] } ?? contexts.values.first
    guard let ctx, let geometry = laidOutGeometry(for: ctx) else {
        x?.pointee = 0
        y?.pointee = 0
        return 0        // unreliable coordinates
    }
    writeFlippedPosition(geometry, x: x, y: y)
    return 1            // precise coordinates
}

/// Returns "top-left" or "top-right" (menu-bar always at top).
@_cdecl("tray_get_status_item_region")
public func tray_get_status_item_region() -> UnsafeMutablePointer<CChar>? {
    let ctx = trayInstance.flatMap { contexts[$0] } ?? contexts.values.first
    guard let ctx, let geometry = laidOutGeometry(for: ctx) else {
        return strdup("top-right")          // default until the item is laid out
    }
    let midX   = geometry.screen.frame.midX
    let region = geometry.rect.minX < midX ? "top-left" : "top-right"
    return strdup(region)                   // to be freed on JVM/JNI side
}

// Per-instance geometry exports
@_cdecl("tray_get_status_item_position_for")
public func tray_get_status_item_position_for(
    _ tray: UnsafeMutableRawPointer?,
    _ x: UnsafeMutablePointer<Int32>?,
    _ y: UnsafeMutablePointer<Int32>?
) -> Int32 {
    guard let tray = tray, let ctx = contexts[tray],
          let geometry = laidOutGeometry(for: ctx) else {
        x?.pointee = 0
        y?.pointee = 0
        return 0
    }
    writeFlippedPosition(geometry, x: x, y: y)
    return 1
}

@_cdecl("tray_get_status_item_region_for")
public func tray_get_status_item_region_for(
    _ tray: UnsafeMutableRawPointer?
) -> UnsafeMutablePointer<CChar>? {
    guard let tray = tray, let ctx = contexts[tray],
          let geometry = laidOutGeometry(for: ctx) else {
        return strdup("top-right")
    }
    let midX   = geometry.screen.frame.midX
    let region = geometry.rect.minX < midX ? "top-left" : "top-right"
    return strdup(region)
}

// MARK: - Pre-rendered appearance icons

@_cdecl("tray_set_icons_for_appearance")
public func tray_set_icons_for_appearance(
    _ tray: UnsafeMutableRawPointer?,
    _ lightIconPath: UnsafePointer<CChar>?,
    _ darkIconPath: UnsafePointer<CChar>?
) {
    let doWork = {
        guard let tray = tray, let ctx = contexts[tray] else { return }
        let height = min(NSStatusBar.system.thickness - 4, 18)
        if let path = lightIconPath.flatMap({ String(cString: $0) }),
           let img = NSImage(contentsOfFile: path) {
            let w = img.size.width * (height / img.size.height)
            img.size = NSSize(width: w, height: height)
            ctx.lightImage = img
        }
        if let path = darkIconPath.flatMap({ String(cString: $0) }),
           let img = NSImage(contentsOfFile: path) {
            let w = img.size.width * (height / img.size.height)
            img.size = NSSize(width: w, height: height)
            ctx.darkImage = img
        }
        // Apply the correct variant for the current appearance
        if let button = ctx.statusItem.button {
            let isDark = button.effectiveAppearance
                .bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            if let img = isDark ? ctx.darkImage : ctx.lightImage {
                button.image = img
            }
        }
    }
    if Thread.isMainThread { doWork() }
    else { DispatchQueue.main.async { doWork() } }
}

// MARK: - Spaces / Virtual Desktop support

/// Sets NSWindowCollectionBehavior.moveToActiveSpace on all app windows
/// so that showing a window moves it to the current Space instead of
/// switching back to the Space where it was originally created.
@_cdecl("tray_set_windows_move_to_active_space")
public func tray_set_windows_move_to_active_space() {
    let doWork = {
        for window in NSApp.windows {
            window.collectionBehavior.insert(.moveToActiveSpace)
        }
    }
    if Thread.isMainThread { doWork() }
    else { DispatchQueue.main.sync { doWork() } }
}

// MARK: - Dock visibility

@_cdecl("tray_show_in_dock")
public func tray_show_in_dock() -> Int32 {
    let doWork = { () -> Int32 in
        NSApp.setActivationPolicy(.regular)
        return 0
    }
    if Thread.isMainThread { return doWork() }
    return DispatchQueue.main.sync { doWork() }
}

@_cdecl("tray_hide_from_dock")
public func tray_hide_from_dock() -> Int32 {
    let doWork = { () -> Int32 in
        NSApp.setActivationPolicy(.accessory)
        return 0
    }
    if Thread.isMainThread { return doWork() }
    return DispatchQueue.main.sync { doWork() }
}

// MARK: - Floating window / Space management

@_cdecl("tray_is_floating_window_on_active_space")
public func tray_is_floating_window_on_active_space() -> Int32 {
    let doWork = { () -> Int32 in
        for window in NSApp.windows {
            if window.level.rawValue > 0 {
                return window.isOnActiveSpace ? 1 : 0
            }
        }
        return 1 // No floating window found, assume on active Space
    }
    if Thread.isMainThread { return doWork() }
    return DispatchQueue.main.sync { doWork() }
}

@_cdecl("tray_bring_floating_window_to_front")
public func tray_bring_floating_window_to_front() -> Int32 {
    let doWork = { () -> Int32 in
        NSApp.activate(ignoringOtherApps: true)
        for window in NSApp.windows {
            if window.level.rawValue > 0 {
                window.makeKeyAndOrderFront(nil)
                return 0
            }
        }
        return -1 // No floating window found
    }
    if Thread.isMainThread { return doWork() }
    return DispatchQueue.main.sync { doWork() }
}

@_cdecl("tray_set_move_to_active_space_for_view")
public func tray_set_move_to_active_space_for_view(_ nsViewPtr: UnsafeMutableRawPointer?) -> Int32 {
    let doWork = { () -> Int32 in
        guard let viewPtr = nsViewPtr else { return -1 }
        let nsView = Unmanaged<NSView>.fromOpaque(viewPtr).takeUnretainedValue()
        guard let nsWindow = nsView.window else { return -1 }
        var behavior = nsWindow.collectionBehavior
        behavior.remove(.canJoinAllSpaces)
        behavior.insert(.moveToActiveSpace)
        nsWindow.collectionBehavior = behavior
        return 0
    }
    if Thread.isMainThread { return doWork() }
    return DispatchQueue.main.sync { doWork() }
}

@_cdecl("tray_is_on_active_space_for_view")
public func tray_is_on_active_space_for_view(_ nsViewPtr: UnsafeMutableRawPointer?) -> Int32 {
    let doWork = { () -> Int32 in
        guard let viewPtr = nsViewPtr else { return 1 } // fail-open
        let nsView = Unmanaged<NSView>.fromOpaque(viewPtr).takeUnretainedValue()
        guard let nsWindow = nsView.window else { return 1 }
        return nsWindow.isOnActiveSpace ? 1 : 0
    }
    if Thread.isMainThread { return doWork() }
    return DispatchQueue.main.sync { doWork() }
}

// MARK: - Mouse button state

@_cdecl("tray_get_mouse_button_state")
public func tray_get_mouse_button_state(_ button: Int32) -> Int32 {
    let cgButton = CGMouseButton(rawValue: UInt32(button))!
    let state = CGEventSource.buttonState(.combinedSessionState, button: cgButton)
    return state ? 1 : 0
}
