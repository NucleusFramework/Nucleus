// Maps Tao W3C-style `KeyCode` to AWT's `java.awt.event.KeyEvent.VK_*` integer
// codes plus `KEY_LOCATION_*`. Compose Multiplatform's `Key` constructor
// (cf. `compose-multiplatform-core/.../input/key/Key.desktop.kt`) wraps the
// AWT VK code 1:1, so producing AWT-shaped values here lets the Kotlin side
// build a `Key(nativeKeyCode, nativeKeyLocation)` that matches Compose Desktop
// exactly.

use tao::keyboard::KeyCode;

// AWT KEY_LOCATION_*
pub const LOC_STANDARD: i32 = 1;
pub const LOC_LEFT: i32 = 2;
pub const LOC_RIGHT: i32 = 3;
pub const LOC_NUMPAD: i32 = 4;

// AWT VK_F13..VK_F24 are in the high range (extended virtual keys).
const VK_F13: i32 = 0xF000;
const VK_F14: i32 = 0xF001;
const VK_F15: i32 = 0xF002;
const VK_F16: i32 = 0xF003;
const VK_F17: i32 = 0xF004;
const VK_F18: i32 = 0xF005;
const VK_F19: i32 = 0xF006;
const VK_F20: i32 = 0xF007;
const VK_F21: i32 = 0xF008;
const VK_F22: i32 = 0xF009;
const VK_F23: i32 = 0xF00A;
const VK_F24: i32 = 0xF00B;

/// Returns `(vk_code, key_location)` for the given Tao key code, or
/// `(0, LOC_STANDARD)` for unmapped keys (Compose treats VK 0 as `Key.Unknown`).
pub fn map(code: KeyCode) -> (i32, i32) {
    use KeyCode::*;
    match code {
        // Letters (VK_A..VK_Z = 65..90)
        KeyA => (65, LOC_STANDARD),
        KeyB => (66, LOC_STANDARD),
        KeyC => (67, LOC_STANDARD),
        KeyD => (68, LOC_STANDARD),
        KeyE => (69, LOC_STANDARD),
        KeyF => (70, LOC_STANDARD),
        KeyG => (71, LOC_STANDARD),
        KeyH => (72, LOC_STANDARD),
        KeyI => (73, LOC_STANDARD),
        KeyJ => (74, LOC_STANDARD),
        KeyK => (75, LOC_STANDARD),
        KeyL => (76, LOC_STANDARD),
        KeyM => (77, LOC_STANDARD),
        KeyN => (78, LOC_STANDARD),
        KeyO => (79, LOC_STANDARD),
        KeyP => (80, LOC_STANDARD),
        KeyQ => (81, LOC_STANDARD),
        KeyR => (82, LOC_STANDARD),
        KeyS => (83, LOC_STANDARD),
        KeyT => (84, LOC_STANDARD),
        KeyU => (85, LOC_STANDARD),
        KeyV => (86, LOC_STANDARD),
        KeyW => (87, LOC_STANDARD),
        KeyX => (88, LOC_STANDARD),
        KeyY => (89, LOC_STANDARD),
        KeyZ => (90, LOC_STANDARD),

        // Digits (VK_0..VK_9 = 48..57)
        Digit0 => (48, LOC_STANDARD),
        Digit1 => (49, LOC_STANDARD),
        Digit2 => (50, LOC_STANDARD),
        Digit3 => (51, LOC_STANDARD),
        Digit4 => (52, LOC_STANDARD),
        Digit5 => (53, LOC_STANDARD),
        Digit6 => (54, LOC_STANDARD),
        Digit7 => (55, LOC_STANDARD),
        Digit8 => (56, LOC_STANDARD),
        Digit9 => (57, LOC_STANDARD),

        // Punctuation
        Backquote => (192, LOC_STANDARD),     // VK_BACK_QUOTE
        Minus => (45, LOC_STANDARD),          // VK_MINUS
        Equal => (61, LOC_STANDARD),          // VK_EQUALS
        BracketLeft => (91, LOC_STANDARD),    // VK_OPEN_BRACKET
        BracketRight => (93, LOC_STANDARD),   // VK_CLOSE_BRACKET
        Backslash => (92, LOC_STANDARD),      // VK_BACK_SLASH
        Semicolon => (59, LOC_STANDARD),      // VK_SEMICOLON
        Quote => (222, LOC_STANDARD),         // VK_QUOTE
        Comma => (44, LOC_STANDARD),          // VK_COMMA
        Period => (46, LOC_STANDARD),         // VK_PERIOD
        Slash => (47, LOC_STANDARD),          // VK_SLASH
        IntlBackslash => (92, LOC_STANDARD),
        Plus => (521, LOC_STANDARD),          // VK_PLUS

        // Whitespace / editing
        Enter => (10, LOC_STANDARD),
        Tab => (9, LOC_STANDARD),
        Space => (32, LOC_STANDARD),
        Backspace => (8, LOC_STANDARD),
        Delete => (127, LOC_STANDARD),
        Escape => (27, LOC_STANDARD),

        // Modifiers (have left/right variants)
        ShiftLeft => (16, LOC_LEFT),          // VK_SHIFT
        ShiftRight => (16, LOC_RIGHT),
        ControlLeft => (17, LOC_LEFT),        // VK_CONTROL
        ControlRight => (17, LOC_RIGHT),
        AltLeft => (18, LOC_LEFT),            // VK_ALT
        AltRight => (18, LOC_RIGHT),
        SuperLeft => (157, LOC_LEFT),         // VK_META
        SuperRight => (157, LOC_RIGHT),
        CapsLock => (20, LOC_STANDARD),

        // Navigation
        ArrowUp => (38, LOC_STANDARD),
        ArrowDown => (40, LOC_STANDARD),
        ArrowLeft => (37, LOC_STANDARD),
        ArrowRight => (39, LOC_STANDARD),
        Home => (36, LOC_STANDARD),
        End => (35, LOC_STANDARD),
        PageUp => (33, LOC_STANDARD),
        PageDown => (34, LOC_STANDARD),
        Insert => (155, LOC_STANDARD),

        // Function row (VK_F1..VK_F12 = 112..123)
        F1 => (112, LOC_STANDARD),
        F2 => (113, LOC_STANDARD),
        F3 => (114, LOC_STANDARD),
        F4 => (115, LOC_STANDARD),
        F5 => (116, LOC_STANDARD),
        F6 => (117, LOC_STANDARD),
        F7 => (118, LOC_STANDARD),
        F8 => (119, LOC_STANDARD),
        F9 => (120, LOC_STANDARD),
        F10 => (121, LOC_STANDARD),
        F11 => (122, LOC_STANDARD),
        F12 => (123, LOC_STANDARD),
        F13 => (VK_F13, LOC_STANDARD),
        F14 => (VK_F14, LOC_STANDARD),
        F15 => (VK_F15, LOC_STANDARD),
        F16 => (VK_F16, LOC_STANDARD),
        F17 => (VK_F17, LOC_STANDARD),
        F18 => (VK_F18, LOC_STANDARD),
        F19 => (VK_F19, LOC_STANDARD),
        F20 => (VK_F20, LOC_STANDARD),
        F21 => (VK_F21, LOC_STANDARD),
        F22 => (VK_F22, LOC_STANDARD),
        F23 => (VK_F23, LOC_STANDARD),
        F24 => (VK_F24, LOC_STANDARD),

        // Numpad (VK_NUMPAD0..VK_NUMPAD9 = 96..105)
        Numpad0 => (96, LOC_NUMPAD),
        Numpad1 => (97, LOC_NUMPAD),
        Numpad2 => (98, LOC_NUMPAD),
        Numpad3 => (99, LOC_NUMPAD),
        Numpad4 => (100, LOC_NUMPAD),
        Numpad5 => (101, LOC_NUMPAD),
        Numpad6 => (102, LOC_NUMPAD),
        Numpad7 => (103, LOC_NUMPAD),
        Numpad8 => (104, LOC_NUMPAD),
        Numpad9 => (105, LOC_NUMPAD),
        NumpadMultiply | NumpadStar => (106, LOC_NUMPAD), // VK_MULTIPLY
        NumpadAdd => (107, LOC_NUMPAD),                   // VK_ADD
        NumpadComma => (108, LOC_NUMPAD),                 // VK_SEPARATOR
        NumpadSubtract => (109, LOC_NUMPAD),              // VK_SUBTRACT
        NumpadDecimal => (110, LOC_NUMPAD),               // VK_DECIMAL
        NumpadDivide => (111, LOC_NUMPAD),                // VK_DIVIDE
        NumpadEnter => (10, LOC_NUMPAD),                  // VK_ENTER (numpad)
        NumpadEqual => (61, LOC_NUMPAD),                  // VK_EQUALS (numpad)
        NumLock => (144, LOC_NUMPAD),                     // VK_NUM_LOCK

        // Misc
        ContextMenu => (525, LOC_STANDARD),    // VK_CONTEXT_MENU
        PrintScreen => (154, LOC_STANDARD),    // VK_PRINTSCREEN
        ScrollLock => (145, LOC_STANDARD),     // VK_SCROLL_LOCK
        Pause => (19, LOC_STANDARD),           // VK_PAUSE
        Help => (156, LOC_STANDARD),           // VK_HELP

        _ => (0, LOC_STANDARD),
    }
}
