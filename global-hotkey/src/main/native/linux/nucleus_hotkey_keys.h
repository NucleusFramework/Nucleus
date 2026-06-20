/**
 * Shared key/modifier mapping for the Linux global hotkey backends.
 *
 * Kept header-only (static inline) so the JNI implementation and the native
 * unit test can share a single source of truth for the trigger-string format.
 *
 * Depends only on X11 keysym definitions and XKeysymToString(), which is a pure
 * lookup that needs no open Display — safe to use from the Wayland/portal path.
 */

#ifndef NUCLEUS_HOTKEY_KEYS_H
#define NUCLEUS_HOTKEY_KEYS_H

#include <string.h>

#include <X11/Xlib.h>
#include <X11/keysym.h>

#define MOD_ALT     0x0001
#define MOD_CONTROL 0x0002
#define MOD_SHIFT   0x0004
#define MOD_META    0x0008

/* AWT VK_* → X11 KeySym. The X11 keysym names returned by XKeysymToString()
 * are identical to the xkbcommon-keysyms.h identifiers (minus XKB_KEY_) that
 * the Freedesktop Shortcuts spec requires, so this single table also drives
 * the portal preferred_trigger key names. */
static inline KeySym awtToKeySym(int vk) {
    if (vk >= 0x41 && vk <= 0x5A) return XK_a + (vk - 0x41);
    if (vk >= 0x30 && vk <= 0x39) return XK_0 + (vk - 0x30);
    if (vk >= 0x70 && vk <= 0x7B) return XK_F1 + (vk - 0x70);
    if (vk >= 0x60 && vk <= 0x69) return XK_KP_0 + (vk - 0x60);
    switch (vk) {
        case 0x0A: return XK_Return;      case 0x1B: return XK_Escape;
        case 0x08: return XK_BackSpace;    case 0x09: return XK_Tab;
        case 0x20: return XK_space;        case 0x7F: return XK_Delete;
        case 0x14: return XK_Caps_Lock;
        case 0x26: return XK_Up;           case 0x28: return XK_Down;
        case 0x25: return XK_Left;         case 0x27: return XK_Right;
        case 0x24: return XK_Home;         case 0x23: return XK_End;
        case 0x21: return XK_Page_Up;      case 0x22: return XK_Page_Down;
        case 0x9B: return XK_Insert;
        case 0xC0: return XK_grave;        case 0x2D: return XK_minus;
        case 0x3D: return XK_equal;        case 0x5B: return XK_bracketleft;
        case 0x5D: return XK_bracketright; case 0x5C: return XK_backslash;
        case 0x3B: return XK_semicolon;    case 0xDE: return XK_apostrophe;
        case 0x2C: return XK_comma;        case 0x2E: return XK_period;
        case 0x2F: return XK_slash;
        case 0x6A: return XK_KP_Multiply;  case 0x6B: return XK_KP_Add;
        case 0x6D: return XK_KP_Subtract;  case 0x6E: return XK_KP_Decimal;
        case 0x6F: return XK_KP_Divide;
        case 0xB3: return 0x1008FF14;      case 0xB2: return 0x1008FF15;
        case 0xB0: return 0x1008FF17;      case 0xB1: return 0x1008FF16;
        default:   return NoSymbol;
    }
}

/* AWT modifier bit → Freedesktop Shortcuts spec modifier name. */
static inline const char *modName(int mod) {
    switch (mod) {
        case MOD_CONTROL: return "CTRL";
        case MOD_ALT:     return "ALT";
        case MOD_SHIFT:   return "SHIFT";
        case MOD_META:    return "LOGO";
        default:          return NULL;
    }
}

/* Build a preferred_trigger string in Freedesktop Shortcuts spec syntax:
 *   modifiers and key joined with '+', e.g. "CTRL+ALT+a", "CTRL+SHIFT+bracketleft".
 * Modifier names: CTRL, ALT, SHIFT, LOGO. Key names come from XKeysymToString()
 * (xkbcommon identifier without the XKB_KEY_ prefix).
 *
 * Writes an empty string if the key has no known keysym. */
static inline void buildTrigger(char *buf, int sz, int mods, int vk) {
    static const int ORDER[] = { MOD_CONTROL, MOD_ALT, MOD_SHIFT, MOD_META };

    buf[0] = '\0';
    KeySym ks = awtToKeySym(vk);
    if (ks == NoSymbol) return;

    const char *keyName = XKeysymToString(ks);
    if (!keyName) return;

    for (unsigned i = 0; i < sizeof(ORDER) / sizeof(ORDER[0]); i++) {
        if (mods & ORDER[i]) {
            strncat(buf, modName(ORDER[i]), sz - strlen(buf) - 1);
            strncat(buf, "+", sz - strlen(buf) - 1);
        }
    }
    strncat(buf, keyName, sz - strlen(buf) - 1);
}

#endif /* NUCLEUS_HOTKEY_KEYS_H */
