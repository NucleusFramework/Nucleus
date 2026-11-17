/*
 * sni.h – StatusNotifierItem + DBusMenu over sd-bus for Linux system tray.
 *
 * Single-header API: the JNI bridge only talks to this.
 */

#ifndef SNI_H
#define SNI_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

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

/* Opaque tray handle */
typedef struct sni_tray sni_tray;

/* Callback types */
typedef void (*sni_click_cb)(int32_t x, int32_t y, void *userdata);
typedef void (*sni_menu_item_cb)(uint32_t id, void *userdata);
typedef void (*sni_menu_opened_cb)(void *userdata);

/* ── Lifecycle ─────────────────────────────────────────────────────── */

/* Create a new tray instance. Returns NULL on failure.
 * icon_data/icon_len: PNG/JPG bytes for the tray icon.
 * tooltip: tooltip text (UTF-8). */
sni_tray *sni_tray_create(const uint8_t *icon_data, size_t icon_len,
                           const char *tooltip);

/* Start the D-Bus event loop in the current thread (blocks).
 * Call sni_tray_quit() from another thread to unblock. */
int sni_tray_run(sni_tray *tray);

/* Signal the event loop to stop. Thread-safe. */
void sni_tray_quit(sni_tray *tray);

/* Destroy the tray and release all resources.
 * Must be called after sni_tray_run() returns. */
void sni_tray_destroy(sni_tray *tray);

/* ── Tray properties ───────────────────────────────────────────────── */

void sni_tray_set_icon(sni_tray *tray, const uint8_t *icon_data, size_t icon_len);
void sni_tray_set_title(sni_tray *tray, const char *title);
void sni_tray_set_tooltip(sni_tray *tray, const char *tooltip);

/* ── Click callbacks ───────────────────────────────────────────────── */

void sni_tray_set_click_callback(sni_tray *tray, sni_click_cb cb, void *userdata);
void sni_tray_set_rclick_callback(sni_tray *tray, sni_click_cb cb, void *userdata);
void sni_tray_set_menu_callback(sni_tray *tray, sni_menu_item_cb cb, void *userdata);
void sni_tray_set_menu_opened_callback(sni_tray *tray, sni_menu_opened_cb cb, void *userdata);

/* Get last click coordinates (from Activate/ContextMenu). */
void sni_tray_get_last_click_xy(sni_tray *tray, int32_t *x, int32_t *y);

/* ── Menu management ───────────────────────────────────────────────── */

/* Clear all menu items. */
void sni_tray_reset_menu(sni_tray *tray);

/* Add a top-level menu item. Returns the item ID (>0), or 0 on failure. */
uint32_t sni_tray_add_menu_item(sni_tray *tray, const char *title,
                                 const char *tooltip);

/* Add a top-level checkable menu item. */
uint32_t sni_tray_add_menu_item_checkbox(sni_tray *tray, const char *title,
                                          const char *tooltip, int checked);

/* Add a top-level separator. */
void sni_tray_add_separator(sni_tray *tray);

/* Add a child item under parent_id. */
uint32_t sni_tray_add_sub_menu_item(sni_tray *tray, uint32_t parent_id,
                                     const char *title, const char *tooltip);

/* Add a checkable child item under parent_id. */
uint32_t sni_tray_add_sub_menu_item_checkbox(sni_tray *tray, uint32_t parent_id,
                                              const char *title, const char *tooltip,
                                              int checked);

/* Add a separator under parent_id. */
void sni_tray_add_sub_separator(sni_tray *tray, uint32_t parent_id);

/* ── Per-item operations ───────────────────────────────────────────── */

int  sni_tray_item_set_title(sni_tray *tray, uint32_t id, const char *title);
void sni_tray_item_enable(sni_tray *tray, uint32_t id);
void sni_tray_item_disable(sni_tray *tray, uint32_t id);
void sni_tray_item_show(sni_tray *tray, uint32_t id);
void sni_tray_item_hide(sni_tray *tray, uint32_t id);
void sni_tray_item_check(sni_tray *tray, uint32_t id);
void sni_tray_item_uncheck(sni_tray *tray, uint32_t id);
void sni_tray_item_set_icon(sni_tray *tray, uint32_t id,
                             const uint8_t *icon_data, size_t icon_len);

/* Set a display-only keyboard shortcut hint on a menu item.
 * key: DBusMenu key name (e.g. "s", "F1", "Delete").
 * Modifier flags: 1 = active, 0 = inactive. */
void sni_tray_item_set_shortcut(sni_tray *tray, uint32_t id,
                                 const char *key,
                                 int ctrl, int shift, int alt, int super_mod);

#ifdef __cplusplus
}
#endif

#endif /* SNI_H */
