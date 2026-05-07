package io.github.kdroidfilter.nucleus.window.tao.render

/**
 * Wire-format codes shared between the Kotlin side and the native
 * `popup_panel.m` / `native_view.m` event-forwarding callbacks. Must
 * stay in sync with the corresponding `#define`s in those files.
 */
internal object TaoNativeWireFormat {
    // Pointer event types (matches EVT_PTR_* in popup_panel.m / native_view.m).
    const val PTR_DOWN: Int = 1
    const val PTR_UP: Int = 2
    const val PTR_MOVE: Int = 3

    // Pointer button codes (matches PointerEvent button payloads).
    const val BUTTON_NONE: Int = 0
    const val BUTTON_PRIMARY: Int = 1
    const val BUTTON_SECONDARY: Int = 2

    // Key event types (matches EVT_KEY_* on the native side).
    const val KEY_DOWN: Int = 1
    const val KEY_UP: Int = 2

    // Modifier bit flags (matches modifierMaskFor: in the .m files).
    const val MOD_SHIFT: Int = 0x1
    const val MOD_CTRL: Int = 0x2
    const val MOD_ALT: Int = 0x4
    const val MOD_META: Int = 0x8
}
