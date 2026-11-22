/*
 * test_sni_pixmap.c – regression test for issue #436.
 *
 * The SNI IconPixmap pyramid must never upscale past the source bitmap.
 * A 24px JVM master used to make every 32–128 level an interpolation; the
 * JVM now ships the 192px scene master and this helper must cap the pyramid.
 *
 * Expected:
 *   - BEFORE the fix: a 24px source still produced 7 levels (16..128).
 *   - AFTER the fix:  24px → 3 levels (16,22,24); 192px → all 7; 8px → 1.
 */

#include "sni.h"

#include <stdio.h>

int main(void) {
    int n24 = sni_pixmap_level_count_for_source(24, 24);
    int n192 = sni_pixmap_level_count_for_source(192, 192);
    int n8 = sni_pixmap_level_count_for_source(8, 8);
    int n0 = sni_pixmap_level_count_for_source(0, 0);

    if (n24 != 3) {
        fprintf(stderr, "FAIL: 24px source produced %d levels, expected 3 (16,22,24)\n", n24);
        return 1;
    }
    if (n192 != (int)SNI_NUM_ICON_SIZES) {
        fprintf(stderr, "FAIL: 192px source produced %d levels, expected %d\n",
                n192, (int)SNI_NUM_ICON_SIZES);
        return 1;
    }
    if (n8 != 1) {
        fprintf(stderr, "FAIL: 8px source produced %d levels, expected 1 (native fallback)\n", n8);
        return 1;
    }
    if (n0 != 0) {
        fprintf(stderr, "FAIL: 0px source produced %d levels, expected 0\n", n0);
        return 1;
    }

    printf("PASS: pixmap pyramid 24→%d 192→%d 8→%d 0→%d\n", n24, n192, n8, n0);
    return 0;
}
