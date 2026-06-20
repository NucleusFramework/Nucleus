/**
 * Regression test for the Linux portal preferred_trigger format.
 *
 * Covers issue #264: triggers were emitted in GTK accelerator syntax
 * ("<Control><Alt>a") instead of the Freedesktop Shortcuts spec syntax
 * ("CTRL+ALT+a"), and punctuation/bracket/numpad keys produced a malformed
 * trigger with no key name because awtToKeyName() returned NULL for them.
 *
 * Build & run (Linux, requires libX11):
 *   cc -I src/main/native/linux \
 *      src/test/native/linux/nucleus_hotkey_keys_test.c -lX11 -o /tmp/hk_test \
 *   && /tmp/hk_test
 */

#include <stdio.h>
#include <string.h>

#include "nucleus_hotkey_keys.h"

/* AWT VK_* codes referenced by the test cases. */
#define VK_A           0x41
#define VK_F1          0x70
#define VK_BRACKETLEFT 0x5B
#define VK_BRACKETRIGHT 0x5D
#define VK_SEMICOLON   0x3B
#define VK_KP0         0x60
#define VK_PLAY        0xB3

static int failures = 0;

static void expect(int mods, int vk, const char *want) {
    char got[128];
    buildTrigger(got, sizeof(got), mods, vk);
    if (strcmp(got, want) != 0) {
        fprintf(stderr, "FAIL: buildTrigger(mods=0x%x, vk=0x%x) = \"%s\", want \"%s\"\n",
                mods, vk, got, want);
        failures++;
    } else {
        printf("ok: \"%s\"\n", got);
    }
}

int main(void) {
    /* Spec format: modifiers and key joined with '+'. */
    expect(MOD_CONTROL | MOD_ALT, VK_A, "CTRL+ALT+a");
    expect(MOD_CONTROL | MOD_SHIFT, VK_A, "CTRL+SHIFT+a");
    expect(MOD_META, VK_A, "LOGO+a");
    expect(0, VK_F1, "F1");

    /* Punctuation / bracket keys previously yielded a key-less trigger. */
    expect(MOD_CONTROL | MOD_SHIFT, VK_BRACKETLEFT, "CTRL+SHIFT+bracketleft");
    expect(MOD_CONTROL, VK_BRACKETRIGHT, "CTRL+bracketright");
    expect(MOD_CONTROL, VK_SEMICOLON, "CTRL+semicolon");

    /* Numpad and media keys. */
    expect(MOD_CONTROL, VK_KP0, "CTRL+KP_0");
    expect(0, VK_PLAY, "XF86AudioPlay");

    /* Canonical modifier ordering regardless of input bit order. */
    expect(MOD_SHIFT | MOD_CONTROL | MOD_ALT, VK_A, "CTRL+ALT+SHIFT+a");

    if (failures) {
        fprintf(stderr, "%d test(s) failed\n", failures);
        return 1;
    }
    printf("all trigger-format tests passed\n");
    return 0;
}
