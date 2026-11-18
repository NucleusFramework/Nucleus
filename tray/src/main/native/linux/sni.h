/*
 * sni.h – IconPixmap pyramid helper of the Linux tray (nucleus_tray_linux.c).
 *
 * Kept in its own header so test_sni_pixmap.c can check it without a bus.
 */

#ifndef SNI_H
#define SNI_H

#include <stddef.h>

/* IconPixmap pyramid sizes (SNI). Levels larger than the source bitmap are
 * omitted so the panel never receives an upscaled frame. */
static const int SNI_ICON_SIZES[] = {16, 22, 24, 32, 48, 64, 128};
#define SNI_NUM_ICON_SIZES (sizeof(SNI_ICON_SIZES) / sizeof(SNI_ICON_SIZES[0]))

/* How many pyramid levels [src_w]×[src_h] can fill without upscaling.
 * Returns 1 when the source is smaller than every catalog size (emit native). */
static inline int sni_pixmap_level_count_for_source(int src_w, int src_h) {
    int src_min = src_w < src_h ? src_w : src_h;
    int n = 0;
    size_t i;
    if (src_min <= 0) return 0;
    for (i = 0; i < SNI_NUM_ICON_SIZES; i++) {
        if (SNI_ICON_SIZES[i] <= src_min) n++;
    }
    return n > 0 ? n : 1;
}

#endif /* SNI_H */
