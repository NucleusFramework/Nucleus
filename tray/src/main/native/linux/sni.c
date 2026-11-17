/*
 * sni.c – StatusNotifierItem + DBusMenu implementation via sd-bus.
 *
 * Implements both org.kde.StatusNotifierItem and com.canonical.dbusmenu
 * interfaces required for Linux system tray support.
 *
 * Desktop-environment quirks (GNOME/KDE) are handled inline with comments.
 */

#include "sni.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>

#include <time.h>
#include <systemd/sd-bus.h>

/* stb_image for PNG/JPG decoding */
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#include "stb_image.h"

/* stb_image_resize2 for multi-resolution scaling */
#define STB_IMAGE_RESIZE_IMPLEMENTATION
#include "stb_image_resize2.h"

/* ========================================================================== */
/*  Constants                                                                 */
/* ========================================================================== */

#define SNI_PATH          "/StatusNotifierItem"
#define MENU_PATH         "/StatusNotifierMenu"
#define SNI_IFACE         "org.kde.StatusNotifierItem"
#define MENU_IFACE        "com.canonical.dbusmenu"
#define WATCHER_BUS       "org.kde.StatusNotifierWatcher"
#define WATCHER_PATH      "/StatusNotifierWatcher"
#define WATCHER_IFACE     "org.kde.StatusNotifierWatcher"

#define MAX_MENU_ITEMS    512
#define DCLICK_INTERVAL   500  /* ms */



/* ========================================================================== */
/*  Menu item                                                                 */
/* ========================================================================== */

typedef struct menu_item {
    int32_t  id;
    char    *label;
    char    *tooltip;
    int      disabled;
    int      checked;
    int      checkable;
    int      visible;
    int      is_separator;

    /* Per-item icon raw PNG/JPG data */
    uint8_t *icon_data;
    size_t   icon_len;

    /* Keyboard shortcut hint (display-only, DBusMenu "shortcut" property) */
    char    *shortcut_key;      /* e.g. "s", "F1", "Delete" */
    int      shortcut_ctrl;
    int      shortcut_shift;
    int      shortcut_alt;
    int      shortcut_super;    /* Meta key */

    /* Tree structure */
    int32_t  parent_id;  /* 0 = root */
    struct menu_item *children;
    int      child_count;
    int      child_capacity;
} menu_item;

/* ========================================================================== */
/*  Pixmap (ARGB32 big-endian for SNI IconPixmap)                             */
/* ========================================================================== */

typedef struct {
    int      width;
    int      height;
    uint8_t *data;     /* ARGB32 big-endian: [A][R][G][B] per pixel */
    size_t   data_len;
} pixmap;

typedef struct {
    pixmap  *entries;
    int      count;
} pixmap_list;

/* ========================================================================== */
/*  Desktop environment detection                                             */
/* ========================================================================== */

typedef enum {
    DE_UNKNOWN = 0,
    DE_GNOME,
    DE_KDE,
} desktop_env;

static desktop_env detect_desktop(void) {
    const char *xdg = getenv("XDG_CURRENT_DESKTOP");
    const char *sess = getenv("DESKTOP_SESSION");

    if (xdg) {
        if (strcasestr(xdg, "gnome")) return DE_GNOME;
        if (strcasestr(xdg, "kde") || strcasestr(xdg, "plasma")) return DE_KDE;
    }
    if (sess) {
        if (strcasestr(sess, "gnome")) return DE_GNOME;
        if (strcasestr(sess, "kde") || strcasestr(sess, "plasma")) return DE_KDE;
    }
    return DE_UNKNOWN;
}

/* ========================================================================== */
/*  Deferred user callbacks                                                   */
/* ========================================================================== */

/* User callbacks (click / menu) are invoked from D-Bus method handlers that
 * run inside sd_bus_process() under bus_lock. Invoking them there would hold
 * bus_lock across an upcall into JVM/Kotlin code, which can re-enter the
 * native mutators and deadlock against the caller's own lock. Instead the
 * handlers enqueue the invocation here and the event loop drains the queue
 * after releasing bus_lock. */
typedef enum {
    PCB_CLICK,
    PCB_RCLICK,
    PCB_MENU_ITEM,
    PCB_MENU_OPENED,
} pending_cb_type;

typedef struct {
    pending_cb_type type;
    int32_t  x, y;
    uint32_t id;
} pending_cb;

/* ========================================================================== */
/*  Tray state                                                                */
/* ========================================================================== */

struct sni_tray {
    sd_bus      *bus;
    sd_bus_slot *sni_slot;
    sd_bus_slot *menu_slot;
    sd_bus_slot *sni_prop_slot;
    sd_bus_slot *menu_prop_slot;
    char        *bus_name;     /* org.kde.StatusNotifierItem-{PID}-1 */
    int          running;
    int          quit_pipe[2]; /* write to [1] to wake the event loop */

    /* Serializes every sd_bus_* access and the tray state it reads/writes.
     * sd-bus objects are NOT thread-safe: the event loop thread (sd_bus_process)
     * and the foreign threads driving the mutators below must never touch the
     * bus concurrently. See issue #405. */
    pthread_mutex_t bus_lock;

    /* Deferred user callbacks, flushed by the event loop outside bus_lock. */
    pending_cb  *pending;
    int          pending_count;
    int          pending_capacity;

    /* SNI properties */
    char        *title;
    char        *tooltip_text;

    /* Icon: decoded pixmap list */
    pixmap_list  icon_pixmaps;

    /* Menu state */
    menu_item   *items;        /* flat array of all items */
    int          item_count;
    int          item_capacity;
    uint32_t     next_id;
    uint32_t     menu_version;

    /* Click state */
    pthread_mutex_t click_lock;
    int32_t      last_click_x;
    int32_t      last_click_y;
    int64_t      last_activate_ms;

    /* Callbacks */
    sni_click_cb      on_click;
    void             *on_click_data;
    sni_click_cb      on_rclick;
    void             *on_rclick_data;
    sni_menu_item_cb  on_menu_item;
    void             *on_menu_item_data;
    sni_menu_opened_cb on_menu_opened;
    void              *on_menu_opened_data;
    int64_t            last_layout_updated_ms; /* suppress AboutToShow triggered by LayoutUpdated */

    /* Desktop environment */
    desktop_env  de;

    /* Menu path currently advertised in SNI Menu property.
     * GNOME quirk: "/" when no menu, "/StatusNotifierMenu" when items exist. */
    const char  *current_menu_path;
};

/* ========================================================================== */
/*  Time helpers                                                              */
/* ========================================================================== */

#include <time.h>

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ========================================================================== */
/*  Bus lock helpers                                                          */
/* ========================================================================== */

static inline void bus_lock(sni_tray *tray)   { pthread_mutex_lock(&tray->bus_lock); }
static inline void bus_unlock(sni_tray *tray) { pthread_mutex_unlock(&tray->bus_lock); }

/* Enqueue a user callback to be fired by the event loop after bus_lock is
 * released. Called only from D-Bus handlers, i.e. with bus_lock already held
 * on the event loop thread, so no additional locking is required. */
static void enqueue_callback(sni_tray *tray, pending_cb cb) {
    if (tray->pending_count >= tray->pending_capacity) {
        int new_cap = tray->pending_capacity ? tray->pending_capacity * 2 : 16;
        pending_cb *p = realloc(tray->pending, (size_t)new_cap * sizeof(pending_cb));
        if (!p) return; /* drop on OOM rather than risk an unbounded grow */
        tray->pending = p;
        tray->pending_capacity = new_cap;
    }
    tray->pending[tray->pending_count++] = cb;
}

/* Drain and invoke queued callbacks. Must run on the event loop thread with
 * bus_lock NOT held, so user code is free to call back into the mutators. */
static void flush_callbacks(sni_tray *tray) {
    for (int i = 0; i < tray->pending_count; i++) {
        pending_cb *c = &tray->pending[i];
        switch (c->type) {
            case PCB_CLICK:
                if (tray->on_click)  tray->on_click(c->x, c->y, tray->on_click_data);
                break;
            case PCB_RCLICK:
                if (tray->on_rclick) tray->on_rclick(c->x, c->y, tray->on_rclick_data);
                break;
            case PCB_MENU_ITEM:
                if (tray->on_menu_item) tray->on_menu_item(c->id, tray->on_menu_item_data);
                break;
            case PCB_MENU_OPENED:
                if (tray->on_menu_opened) tray->on_menu_opened(tray->on_menu_opened_data);
                break;
        }
    }
    tray->pending_count = 0;
}

/* ========================================================================== */
/*  Icon / Pixmap helpers                                                     */
/* ========================================================================== */

/* Convert RGBA (stb_image output) to ARGB32 big-endian as required by SNI. */
static uint8_t *rgba_to_argb32_be(const uint8_t *rgba, int w, int h) {
    size_t len = (size_t)w * h * 4;
    uint8_t *out = malloc(len);
    if (!out) return NULL;
    for (int i = 0; i < w * h; i++) {
        uint8_t r = rgba[i * 4 + 0];
        uint8_t g = rgba[i * 4 + 1];
        uint8_t b = rgba[i * 4 + 2];
        uint8_t a = rgba[i * 4 + 3];
        out[i * 4 + 0] = a;
        out[i * 4 + 1] = r;
        out[i * 4 + 2] = g;
        out[i * 4 + 3] = b;
    }
    return out;
}

static void free_pixmap_list(pixmap_list *pl) {
    if (!pl->entries) return;
    for (int i = 0; i < pl->count; i++) {
        free(pl->entries[i].data);
    }
    free(pl->entries);
    pl->entries = NULL;
    pl->count = 0;
}

static int append_pixmap(pixmap_list *pl, const uint8_t *src, int src_w, int src_h, int s) {
    uint8_t *resized = malloc((size_t)s * s * 4);
    if (!resized) return 0;

    stbir_resize_uint8_linear(src, src_w, src_h, src_w * 4,
                              resized, s, s, s * 4, STBIR_RGBA);

    uint8_t *argb = rgba_to_argb32_be(resized, s, s);
    free(resized);
    if (!argb) return 0;

    pl->entries[pl->count].width = s;
    pl->entries[pl->count].height = s;
    pl->entries[pl->count].data = argb;
    pl->entries[pl->count].data_len = (size_t)s * s * 4;
    pl->count++;
    return 1;
}

/* Build multi-resolution pixmaps from raw PNG/JPG data.
 * Never upscales past the source: a 24px JVM master used to make every
 * 32–128 level an interpolation. The JVM now ships the scene master. */
static pixmap_list build_pixmaps(const uint8_t *data, size_t len) {
    pixmap_list pl = {NULL, 0};
    if (!data || len == 0) return pl;

    int src_w, src_h, channels;
    uint8_t *src = stbi_load_from_memory(data, (int)len, &src_w, &src_h, &channels, 4);
    if (!src) return pl;

    int src_min = src_w < src_h ? src_w : src_h;
    int levels = sni_pixmap_level_count_for_source(src_w, src_h);
    if (levels <= 0) { stbi_image_free(src); return pl; }

    pl.entries = calloc((size_t)levels, sizeof(pixmap));
    if (!pl.entries) { stbi_image_free(src); return pl; }

    for (size_t i = 0; i < SNI_NUM_ICON_SIZES; i++) {
        int s = SNI_ICON_SIZES[i];
        if (s > src_min) continue;
        if (pl.count >= levels) break;
        append_pixmap(&pl, src, src_w, src_h, s);
    }

    if (pl.count == 0 && src_min > 0) {
        append_pixmap(&pl, src, src_w, src_h, src_min);
    }

    stbi_image_free(src);
    return pl;
}

/* ========================================================================== */
/*  Menu item helpers                                                         */
/* ========================================================================== */

static menu_item *find_item(sni_tray *tray, int32_t id) {
    for (int i = 0; i < tray->item_count; i++) {
        if (tray->items[i].id == id) return &tray->items[i];
    }
    return NULL;
}

static menu_item *alloc_item(sni_tray *tray) {
    if (tray->item_count >= tray->item_capacity) {
        int new_cap = tray->item_capacity ? tray->item_capacity * 2 : 32;
        menu_item *new_items = realloc(tray->items, (size_t)new_cap * sizeof(menu_item));
        if (!new_items) return NULL;
        tray->items = new_items;
        tray->item_capacity = new_cap;
    }
    menu_item *item = &tray->items[tray->item_count++];
    memset(item, 0, sizeof(menu_item));
    item->visible = 1;
    return item;
}

static void free_menu_items(sni_tray *tray) {
    for (int i = 0; i < tray->item_count; i++) {
        free(tray->items[i].label);
        free(tray->items[i].tooltip);
        free(tray->items[i].icon_data);
        free(tray->items[i].shortcut_key);
        free(tray->items[i].children);
    }
    free(tray->items);
    tray->items = NULL;
    tray->item_count = 0;
    tray->item_capacity = 0;
}

/* ========================================================================== */
/*  Menu path quirks                                                          */
/* ========================================================================== */

/* GNOME: advertise "/" when no menu items exist, "/StatusNotifierMenu" otherwise.
 * KDE/others: always advertise "/StatusNotifierMenu" (or "/NO_DBUSMENU" when empty,
 * but KDE needs at least a dummy separator — handled by Kotlin side). */
static const char *no_menu_path(desktop_env de) {
    return (de == DE_GNOME) ? "/" : MENU_PATH;
}

/* ========================================================================== */
/*  D-Bus: emit signals                                                       */
/* ========================================================================== */

static void emit_new_icon(sni_tray *tray) {
    if (!tray->bus) return;
    sd_bus_emit_signal(tray->bus, SNI_PATH, SNI_IFACE, "NewIcon", "");
}

static void emit_new_title(sni_tray *tray) {
    if (!tray->bus) return;
    sd_bus_emit_signal(tray->bus, SNI_PATH, SNI_IFACE, "NewTitle", "");
}

static void emit_layout_updated(sni_tray *tray) {
    if (!tray->bus) return;
    tray->menu_version++;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    tray->last_layout_updated_ms = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    sd_bus_emit_signal(tray->bus, MENU_PATH, MENU_IFACE, "LayoutUpdated",
                       "ui", tray->menu_version, (int32_t)0);
    /* Also emit properties changed for Version */
    sd_bus_emit_properties_changed(tray->bus, MENU_PATH, MENU_IFACE,
                                   "Version", NULL);
}

static void emit_sni_properties_changed(sni_tray *tray, const char *prop) {
    if (!tray->bus) return;
    sd_bus_emit_properties_changed(tray->bus, SNI_PATH, SNI_IFACE, prop, NULL);
}

/* ========================================================================== */
/*  D-Bus: write IconPixmap (a(iiay)) into message                            */
/* ========================================================================== */

static int append_pixmap_list(sd_bus_message *reply, const pixmap_list *pl) {
    int r;
    r = sd_bus_message_open_container(reply, 'a', "(iiay)");
    if (r < 0) return r;

    for (int i = 0; i < pl->count; i++) {
        r = sd_bus_message_open_container(reply, 'r', "iiay");
        if (r < 0) return r;
        r = sd_bus_message_append(reply, "ii", pl->entries[i].width, pl->entries[i].height);
        if (r < 0) return r;
        r = sd_bus_message_append_array(reply, 'y',
                                        pl->entries[i].data, pl->entries[i].data_len);
        if (r < 0) return r;
        r = sd_bus_message_close_container(reply);
        if (r < 0) return r;
    }

    return sd_bus_message_close_container(reply);
}

/* Write empty pixmap array a(iiay) */
static int append_empty_pixmap_list(sd_bus_message *reply) {
    int r;
    r = sd_bus_message_open_container(reply, 'a', "(iiay)");
    if (r < 0) return r;
    return sd_bus_message_close_container(reply);
}

/* ========================================================================== */
/*  D-Bus: write ToolTip (sa(iiay)ss) into message                            */
/* ========================================================================== */

static int append_tooltip(sd_bus_message *reply, sni_tray *tray) {
    int r;
    /* ToolTip is a struct (sa(iiay)ss) = (name, icon_pixmaps, title, description) */
    r = sd_bus_message_open_container(reply, 'r', "sa(iiay)ss");
    if (r < 0) return r;

    r = sd_bus_message_append(reply, "s", ""); /* name */
    if (r < 0) return r;

    r = append_pixmap_list(reply, &tray->icon_pixmaps);
    if (r < 0) return r;

    r = sd_bus_message_append(reply, "ss",
                              tray->tooltip_text ? tray->tooltip_text : "",
                              ""); /* description */
    if (r < 0) return r;

    return sd_bus_message_close_container(reply);
}

/* ========================================================================== */
/*  D-Bus: SNI property getter                                                */
/* ========================================================================== */

static int sni_get_property(sd_bus *bus, const char *path, const char *interface,
                            const char *property, sd_bus_message *reply,
                            void *userdata, sd_bus_error *error) {
    (void)bus; (void)path; (void)interface; (void)error;
    sni_tray *tray = userdata;

    if (strcmp(property, "Category") == 0)
        return sd_bus_message_append(reply, "s", "ApplicationStatus");
    if (strcmp(property, "Id") == 0)
        return sd_bus_message_append(reply, "s", "1");
    if (strcmp(property, "Title") == 0)
        return sd_bus_message_append(reply, "s", tray->title ? tray->title : "");
    if (strcmp(property, "Status") == 0)
        return sd_bus_message_append(reply, "s", "Active");
    if (strcmp(property, "WindowId") == 0)
        return sd_bus_message_append(reply, "i", 0);
    if (strcmp(property, "IconThemePath") == 0)
        return sd_bus_message_append(reply, "s", "");
    if (strcmp(property, "Menu") == 0)
        return sd_bus_message_append(reply, "o", tray->current_menu_path);
    if (strcmp(property, "ItemIsMenu") == 0)
        return sd_bus_message_append(reply, "b", 1);
    if (strcmp(property, "IconName") == 0)
        return sd_bus_message_append(reply, "s", "");
    if (strcmp(property, "IconPixmap") == 0)
        return append_pixmap_list(reply, &tray->icon_pixmaps);
    if (strcmp(property, "OverlayIconName") == 0)
        return sd_bus_message_append(reply, "s", "");
    if (strcmp(property, "OverlayIconPixmap") == 0)
        return append_empty_pixmap_list(reply);
    if (strcmp(property, "AttentionIconName") == 0)
        return sd_bus_message_append(reply, "s", "");
    if (strcmp(property, "AttentionIconPixmap") == 0)
        return append_empty_pixmap_list(reply);
    if (strcmp(property, "AttentionMovieName") == 0)
        return sd_bus_message_append(reply, "s", "");
    if (strcmp(property, "ToolTip") == 0)
        return append_tooltip(reply, tray);

    return -ENOENT;
}

/* ========================================================================== */
/*  D-Bus: SNI methods                                                        */
/* ========================================================================== */

static int sni_activate(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    int32_t x, y;
    sd_bus_message_read(msg, "ii", &x, &y);

    pthread_mutex_lock(&tray->click_lock);
    tray->last_click_x = x;
    tray->last_click_y = y;
    pthread_mutex_unlock(&tray->click_lock);

    /* Double-click detection */
    int64_t now = now_ms();
    if (tray->last_activate_ms > 0 && (now - tray->last_activate_ms) < DCLICK_INTERVAL) {
        tray->last_activate_ms = 0;
        /* Double click — still invoke on_click */
    } else {
        tray->last_activate_ms = now;
    }

    enqueue_callback(tray, (pending_cb){.type = PCB_CLICK, .x = x, .y = y});

    return sd_bus_reply_method_return(msg, "");
}

static int sni_context_menu(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    int32_t x, y;
    sd_bus_message_read(msg, "ii", &x, &y);

    pthread_mutex_lock(&tray->click_lock);
    tray->last_click_x = x;
    tray->last_click_y = y;
    pthread_mutex_unlock(&tray->click_lock);

    enqueue_callback(tray, (pending_cb){.type = PCB_RCLICK, .x = x, .y = y});

    return sd_bus_reply_method_return(msg, "");
}

static int sni_secondary_activate(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    int32_t x, y;
    sd_bus_message_read(msg, "ii", &x, &y);

    pthread_mutex_lock(&tray->click_lock);
    tray->last_click_x = x;
    tray->last_click_y = y;
    pthread_mutex_unlock(&tray->click_lock);

    return sd_bus_reply_method_return(msg, "");
}

static int sni_scroll(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error; (void)userdata;
    return sd_bus_reply_method_return(msg, "");
}

/* ========================================================================== */
/*  D-Bus: SNI vtable                                                         */
/* ========================================================================== */

static const sd_bus_vtable sni_vtable[] = {
    SD_BUS_VTABLE_START(0),

    /* Properties */
    SD_BUS_PROPERTY("Category",           "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("Id",                 "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("Title",              "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("Status",             "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("WindowId",           "i",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("IconThemePath",      "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("Menu",               "o",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("ItemIsMenu",         "b",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("IconName",           "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("IconPixmap",         "a(iiay)",    sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("OverlayIconName",    "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("OverlayIconPixmap",  "a(iiay)",    sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("AttentionIconName",  "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("AttentionIconPixmap","a(iiay)",    sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("AttentionMovieName", "s",          sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("ToolTip",            "(sa(iiay)ss)", sni_get_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),

    /* Methods */
    SD_BUS_METHOD("Activate",           "ii", "", sni_activate,           SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("ContextMenu",        "ii", "", sni_context_menu,       SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("SecondaryActivate",  "ii", "", sni_secondary_activate, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("Scroll",             "is", "", sni_scroll,             SD_BUS_VTABLE_UNPRIVILEGED),

    /* Signals */
    SD_BUS_SIGNAL("NewTitle",         "",  0),
    SD_BUS_SIGNAL("NewIcon",          "",  0),
    SD_BUS_SIGNAL("NewAttentionIcon", "",  0),
    SD_BUS_SIGNAL("NewOverlayIcon",   "",  0),
    SD_BUS_SIGNAL("NewStatus",        "s", 0),
    SD_BUS_SIGNAL("NewIconThemePath", "s", 0),
    SD_BUS_SIGNAL("NewMenu",          "",  0),

    SD_BUS_VTABLE_END
};

/* ========================================================================== */
/*  D-Bus: DBusMenu – write layout recursively                                */
/* ========================================================================== */

/* Write a single menu item layout: (ia{sv}av) */
static int append_menu_layout(sd_bus_message *reply, sni_tray *tray,
                              int32_t item_id, int32_t depth);

/* Get children IDs for a given parent */
static int get_children(sni_tray *tray, int32_t parent_id,
                        int32_t *out, int max) {
    int count = 0;
    for (int i = 0; i < tray->item_count && count < max; i++) {
        if (tray->items[i].parent_id == parent_id) {
            out[count++] = tray->items[i].id;
        }
    }
    return count;
}

static int append_menu_layout(sd_bus_message *reply, sni_tray *tray,
                              int32_t item_id, int32_t depth) {
    int r;

    /* Open struct (ia{sv}av) */
    r = sd_bus_message_open_container(reply, 'r', "ia{sv}av");
    if (r < 0) return r;

    /* id */
    r = sd_bus_message_append(reply, "i", item_id);
    if (r < 0) return r;

    /* properties: a{sv} */
    r = sd_bus_message_open_container(reply, 'a', "{sv}");
    if (r < 0) return r;

    if (item_id == 0) {
        /* Root item: "children-display" = "submenu" */
        r = sd_bus_message_append(reply, "{sv}", "children-display",
                                  "s", "submenu");
        if (r < 0) return r;
    } else {
        menu_item *item = find_item(tray, item_id);
        if (item) {
            if (item->is_separator) {
                r = sd_bus_message_append(reply, "{sv}", "type", "s", "separator");
                if (r < 0) return r;
            } else {
                r = sd_bus_message_append(reply, "{sv}", "label",
                                          "s", item->label ? item->label : "");
                if (r < 0) return r;
                r = sd_bus_message_append(reply, "{sv}", "enabled",
                                          "b", !item->disabled);
                if (r < 0) return r;

                if (item->checkable) {
                    r = sd_bus_message_append(reply, "{sv}", "toggle-type",
                                              "s", "checkmark");
                    if (r < 0) return r;
                    r = sd_bus_message_append(reply, "{sv}", "toggle-state",
                                              "i", item->checked ? 1 : 0);
                    if (r < 0) return r;
                }

                if (!item->visible) {
                    r = sd_bus_message_append(reply, "{sv}", "visible",
                                              "b", 0);
                    if (r < 0) return r;
                }

                /* Per-item icon: raw PNG/JPG data as icon-data */
                if (item->icon_data && item->icon_len > 0) {
                    r = sd_bus_message_open_container(reply, 'e', "sv");
                    if (r < 0) return r;
                    r = sd_bus_message_append(reply, "s", "icon-data");
                    if (r < 0) return r;
                    r = sd_bus_message_open_container(reply, 'v', "ay");
                    if (r < 0) return r;
                    r = sd_bus_message_append_array(reply, 'y',
                                                    item->icon_data, item->icon_len);
                    if (r < 0) return r;
                    r = sd_bus_message_close_container(reply); /* v */
                    if (r < 0) return r;
                    r = sd_bus_message_close_container(reply); /* e */
                    if (r < 0) return r;
                }

                /* Keyboard shortcut hint: DBusMenu "shortcut" property (type aas) */
                if (item->shortcut_key) {
                    r = sd_bus_message_open_container(reply, 'e', "sv");
                    if (r < 0) return r;
                    r = sd_bus_message_append(reply, "s", "shortcut");
                    if (r < 0) return r;
                    r = sd_bus_message_open_container(reply, 'v', "aas");
                    if (r < 0) return r;
                    r = sd_bus_message_open_container(reply, 'a', "as");
                    if (r < 0) return r;
                    r = sd_bus_message_open_container(reply, 'a', "s");
                    if (r < 0) return r;
                    if (item->shortcut_ctrl)
                        sd_bus_message_append(reply, "s", "Control");
                    if (item->shortcut_shift)
                        sd_bus_message_append(reply, "s", "Shift");
                    if (item->shortcut_alt)
                        sd_bus_message_append(reply, "s", "Alt");
                    if (item->shortcut_super)
                        sd_bus_message_append(reply, "s", "Super");
                    sd_bus_message_append(reply, "s", item->shortcut_key);
                    r = sd_bus_message_close_container(reply); /* as (inner) */
                    if (r < 0) return r;
                    r = sd_bus_message_close_container(reply); /* a (outer) */
                    if (r < 0) return r;
                    r = sd_bus_message_close_container(reply); /* v */
                    if (r < 0) return r;
                    r = sd_bus_message_close_container(reply); /* e */
                    if (r < 0) return r;
                }

                /* If this item has children, mark as submenu parent */
                int32_t child_ids[MAX_MENU_ITEMS];
                int child_count = get_children(tray, item_id, child_ids, MAX_MENU_ITEMS);
                if (child_count > 0) {
                    r = sd_bus_message_append(reply, "{sv}", "children-display",
                                              "s", "submenu");
                    if (r < 0) return r;
                }
            }
        }
    }

    r = sd_bus_message_close_container(reply); /* a{sv} */
    if (r < 0) return r;

    /* children: av */
    r = sd_bus_message_open_container(reply, 'a', "v");
    if (r < 0) return r;

    if (depth != 0) {
        int32_t child_ids[MAX_MENU_ITEMS];
        int child_count = get_children(tray, item_id, child_ids, MAX_MENU_ITEMS);

        int32_t next_depth = (depth > 0) ? depth - 1 : -1;
        for (int i = 0; i < child_count; i++) {
            r = sd_bus_message_open_container(reply, 'v', "(ia{sv}av)");
            if (r < 0) return r;
            r = append_menu_layout(reply, tray, child_ids[i], next_depth);
            if (r < 0) return r;
            r = sd_bus_message_close_container(reply); /* v */
            if (r < 0) return r;
        }
    }

    r = sd_bus_message_close_container(reply); /* av */
    if (r < 0) return r;

    return sd_bus_message_close_container(reply); /* struct */
}

/* ========================================================================== */
/*  D-Bus: DBusMenu methods                                                   */
/* ========================================================================== */

static int menu_get_layout(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    int32_t parent_id, recursion_depth;
    sd_bus_message_read(msg, "ii", &parent_id, &recursion_depth);
    /* Skip property names array */
    sd_bus_message_skip(msg, "as");

    sd_bus_message *reply = NULL;
    int r = sd_bus_message_new_method_return(msg, &reply);
    if (r < 0) return r;

    /* revision */
    r = sd_bus_message_append(reply, "u", tray->menu_version);
    if (r < 0) { sd_bus_message_unref(reply); return r; }

    /* layout */
    r = append_menu_layout(reply, tray, parent_id, recursion_depth);
    if (r < 0) { sd_bus_message_unref(reply); return r; }

    r = sd_bus_send(tray->bus, reply, NULL);
    sd_bus_message_unref(reply);
    return r;
}

static int menu_get_group_properties(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;

    /* Read ids array */
    int r = sd_bus_message_enter_container(msg, 'a', "i");
    if (r < 0) return r;

    int32_t ids[MAX_MENU_ITEMS];
    int id_count = 0;
    int32_t id;
    while (sd_bus_message_read(msg, "i", &id) > 0 && id_count < MAX_MENU_ITEMS) {
        ids[id_count++] = id;
    }
    sd_bus_message_exit_container(msg);

    /* Skip property names */
    sd_bus_message_skip(msg, "as");

    sd_bus_message *reply = NULL;
    r = sd_bus_message_new_method_return(msg, &reply);
    if (r < 0) return r;

    r = sd_bus_message_open_container(reply, 'a', "(ia{sv})");
    if (r < 0) { sd_bus_message_unref(reply); return r; }

    for (int i = 0; i < id_count; i++) {
        menu_item *item = find_item(tray, ids[i]);
        if (!item && ids[i] != 0) continue;

        r = sd_bus_message_open_container(reply, 'r', "ia{sv}");
        if (r < 0) break;
        r = sd_bus_message_append(reply, "i", ids[i]);
        if (r < 0) break;
        r = sd_bus_message_open_container(reply, 'a', "{sv}");
        if (r < 0) break;

        if (item) {
            if (item->is_separator) {
                sd_bus_message_append(reply, "{sv}", "type", "s", "separator");
            } else {
                sd_bus_message_append(reply, "{sv}", "label", "s", item->label ? item->label : "");
                sd_bus_message_append(reply, "{sv}", "enabled", "b", !item->disabled);
                if (item->checkable) {
                    sd_bus_message_append(reply, "{sv}", "toggle-type", "s", "checkmark");
                    sd_bus_message_append(reply, "{sv}", "toggle-state", "i", item->checked ? 1 : 0);
                }
                if (item->shortcut_key) {
                    sd_bus_message_open_container(reply, 'e', "sv");
                    sd_bus_message_append(reply, "s", "shortcut");
                    sd_bus_message_open_container(reply, 'v', "aas");
                    sd_bus_message_open_container(reply, 'a', "as");
                    sd_bus_message_open_container(reply, 'a', "s");
                    if (item->shortcut_ctrl)
                        sd_bus_message_append(reply, "s", "Control");
                    if (item->shortcut_shift)
                        sd_bus_message_append(reply, "s", "Shift");
                    if (item->shortcut_alt)
                        sd_bus_message_append(reply, "s", "Alt");
                    if (item->shortcut_super)
                        sd_bus_message_append(reply, "s", "Super");
                    sd_bus_message_append(reply, "s", item->shortcut_key);
                    sd_bus_message_close_container(reply);
                    sd_bus_message_close_container(reply);
                    sd_bus_message_close_container(reply);
                    sd_bus_message_close_container(reply);
                }
            }
        }

        sd_bus_message_close_container(reply); /* a{sv} */
        sd_bus_message_close_container(reply); /* struct */
    }

    sd_bus_message_close_container(reply); /* array */

    r = sd_bus_send(tray->bus, reply, NULL);
    sd_bus_message_unref(reply);
    return r;
}

static int menu_get_property(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    int32_t id;
    const char *name;
    sd_bus_message_read(msg, "is", &id, &name);

    sd_bus_message *reply = NULL;
    int r = sd_bus_message_new_method_return(msg, &reply);
    if (r < 0) return r;

    menu_item *item = (id == 0) ? NULL : find_item(tray, id);

    if (item && strcmp(name, "label") == 0) {
        r = sd_bus_message_append(reply, "v", "s", item->label ? item->label : "");
    } else if (item && strcmp(name, "enabled") == 0) {
        r = sd_bus_message_append(reply, "v", "b", !item->disabled);
    } else {
        /* Return empty variant for unknown properties */
        r = sd_bus_message_append(reply, "v", "s", "");
    }
    if (r < 0) { sd_bus_message_unref(reply); return r; }

    r = sd_bus_send(tray->bus, reply, NULL);
    sd_bus_message_unref(reply);
    return r;
}

static int menu_event(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    int32_t id;
    const char *event_id;
    sd_bus_message_read(msg, "is", &id, &event_id);
    /* Skip data variant and timestamp */
    sd_bus_message_skip(msg, "vu");

    if (strcmp(event_id, "clicked") == 0) {
        enqueue_callback(tray, (pending_cb){.type = PCB_MENU_ITEM, .id = (uint32_t)id});
    }

    return sd_bus_reply_method_return(msg, "");
}

static int menu_event_group(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;

    int r = sd_bus_message_enter_container(msg, 'a', "(isvu)");
    if (r < 0) return sd_bus_reply_method_return(msg, "ai", 0);

    while (sd_bus_message_enter_container(msg, 'r', "isvu") > 0) {
        int32_t id;
        const char *event_id;
        sd_bus_message_read(msg, "is", &id, &event_id);
        sd_bus_message_skip(msg, "vu");
        sd_bus_message_exit_container(msg);

        if (strcmp(event_id, "clicked") == 0) {
            enqueue_callback(tray, (pending_cb){.type = PCB_MENU_ITEM, .id = (uint32_t)id});
        }
    }
    sd_bus_message_exit_container(msg);

    /* Return empty idErrors array */
    sd_bus_message *reply = NULL;
    r = sd_bus_message_new_method_return(msg, &reply);
    if (r < 0) return r;
    sd_bus_message_open_container(reply, 'a', "i");
    sd_bus_message_close_container(reply);
    r = sd_bus_send(tray->bus, reply, NULL);
    sd_bus_message_unref(reply);
    return r;
}

static int menu_about_to_show(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    int32_t id;
    sd_bus_message_read(msg, "i", &id);

    if (id == 0 && tray->on_menu_opened) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t now_ms = (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
        /* Only fire on genuine user-initiated opens, not on AboutToShow
           calls triggered by a recent LayoutUpdated from a menu rebuild. */
        if (now_ms - tray->last_layout_updated_ms > 300) {
            enqueue_callback(tray, (pending_cb){.type = PCB_MENU_OPENED});
        }
    }
    return sd_bus_reply_method_return(msg, "b", 0);
}

static int menu_about_to_show_group(sd_bus_message *msg, void *userdata, sd_bus_error *error) {
    (void)error;
    sni_tray *tray = userdata;
    /* Skip input */
    sd_bus_message_skip(msg, "ai");

    sd_bus_message *reply = NULL;
    int r = sd_bus_message_new_method_return(msg, &reply);
    if (r < 0) return r;

    /* Empty updatesNeeded */
    sd_bus_message_open_container(reply, 'a', "i");
    sd_bus_message_close_container(reply);
    /* Empty idErrors */
    sd_bus_message_open_container(reply, 'a', "i");
    sd_bus_message_close_container(reply);

    r = sd_bus_send(tray->bus, reply, NULL);
    sd_bus_message_unref(reply);
    return r;
}

/* ========================================================================== */
/*  D-Bus: DBusMenu property getter                                           */
/* ========================================================================== */

static int menu_get_prop(sd_bus *bus, const char *path, const char *interface,
                         const char *property, sd_bus_message *reply,
                         void *userdata, sd_bus_error *error) {
    (void)bus; (void)path; (void)interface; (void)error;
    sni_tray *tray = userdata;

    if (strcmp(property, "Version") == 0)
        return sd_bus_message_append(reply, "u", tray->menu_version);
    if (strcmp(property, "TextDirection") == 0)
        return sd_bus_message_append(reply, "s", "ltr");
    if (strcmp(property, "Status") == 0)
        return sd_bus_message_append(reply, "s", "normal");
    if (strcmp(property, "IconThemePath") == 0) {
        int r = sd_bus_message_open_container(reply, 'a', "s");
        if (r < 0) return r;
        return sd_bus_message_close_container(reply);
    }

    return -ENOENT;
}

/* ========================================================================== */
/*  D-Bus: DBusMenu vtable                                                    */
/* ========================================================================== */

static const sd_bus_vtable menu_vtable[] = {
    SD_BUS_VTABLE_START(0),

    /* Properties */
    SD_BUS_PROPERTY("Version",       "u",  menu_get_prop, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("TextDirection", "s",  menu_get_prop, 0, 0),
    SD_BUS_PROPERTY("Status",        "s",  menu_get_prop, 0, 0),
    SD_BUS_PROPERTY("IconThemePath", "as", menu_get_prop, 0, 0),

    /* Methods */
    SD_BUS_METHOD("GetLayout",           "iias",     "u(ia{sv}av)", menu_get_layout,           SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("GetGroupProperties",  "aias",     "a(ia{sv})",   menu_get_group_properties, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("GetProperty",         "is",       "v",           menu_get_property,         SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("Event",               "isvu",     "",            menu_event,                SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("EventGroup",          "a(isvu)",  "ai",          menu_event_group,          SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("AboutToShow",         "i",        "b",           menu_about_to_show,        SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("AboutToShowGroup",    "ai",       "aiai",        menu_about_to_show_group,  SD_BUS_VTABLE_UNPRIVILEGED),

    /* Signals */
    SD_BUS_SIGNAL("ItemsPropertiesUpdated", "a(ia{sv})a(ias)", 0),
    SD_BUS_SIGNAL("LayoutUpdated",          "ui",              0),
    SD_BUS_SIGNAL("ItemActivationRequested","iu",              0),

    SD_BUS_VTABLE_END
};

/* ========================================================================== */
/*  Public API: Lifecycle                                                     */
/* ========================================================================== */

sni_tray *sni_tray_create(const uint8_t *icon_data, size_t icon_len,
                           const char *tooltip) {
    sni_tray *tray = calloc(1, sizeof(sni_tray));
    if (!tray) return NULL;

    pthread_mutex_init(&tray->click_lock, NULL);
    pthread_mutex_init(&tray->bus_lock, NULL);
    tray->next_id = 1;
    tray->menu_version = 1;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    tray->last_layout_updated_ms = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    tray->de = detect_desktop();
    tray->current_menu_path = no_menu_path(tray->de);

    if (tooltip) tray->tooltip_text = strdup(tooltip);
    if (icon_data && icon_len > 0) {
        tray->icon_pixmaps = build_pixmaps(icon_data, icon_len);
    }

    if (pipe(tray->quit_pipe) < 0) {
        free(tray->tooltip_text);
        free(tray);
        return NULL;
    }

    return tray;
}

int sni_tray_run(sni_tray *tray) {
    int r;

    /* Hold bus_lock across the whole setup: the moment tray->bus becomes
     * non-NULL a foreign thread may try to emit, and the bus is not yet ready. */
    bus_lock(tray);

    r = sd_bus_open_user(&tray->bus);
    if (r < 0) {
        fprintf(stderr, "sni: failed to connect to session bus: %s\n", strerror(-r));
        bus_unlock(tray);
        return r;
    }

    /* Export SNI interface */
    r = sd_bus_add_object_vtable(tray->bus, &tray->sni_slot, SNI_PATH,
                                 SNI_IFACE, sni_vtable, tray);
    if (r < 0) {
        fprintf(stderr, "sni: failed to add SNI vtable: %s\n", strerror(-r));
        bus_unlock(tray);
        return r;
    }

    /* Export DBusMenu interface */
    r = sd_bus_add_object_vtable(tray->bus, &tray->menu_slot, MENU_PATH,
                                 MENU_IFACE, menu_vtable, tray);
    if (r < 0) {
        fprintf(stderr, "sni: failed to add menu vtable: %s\n", strerror(-r));
        bus_unlock(tray);
        return r;
    }

    /* Request bus name */
    char name[128];
    snprintf(name, sizeof(name), "org.kde.StatusNotifierItem-%d-1", getpid());
    tray->bus_name = strdup(name);

    r = sd_bus_request_name(tray->bus, name, 0);
    if (r < 0) {
        fprintf(stderr, "sni: failed to request bus name '%s': %s\n", name, strerror(-r));
        bus_unlock(tray);
        return r;
    }

    /* Register with StatusNotifierWatcher */
    sd_bus_error err = SD_BUS_ERROR_NULL;
    r = sd_bus_call_method(tray->bus,
                           WATCHER_BUS, WATCHER_PATH, WATCHER_IFACE,
                           "RegisterStatusNotifierItem",
                           &err, NULL, "s", SNI_PATH);
    if (r < 0) {
        fprintf(stderr, "sni: failed to register with watcher: %s\n",
                err.message ? err.message : strerror(-r));
        sd_bus_error_free(&err);
        /* Not fatal — some environments don't have a watcher */
    }

    tray->running = 1;
    int bus_fd = sd_bus_get_fd(tray->bus);

    bus_unlock(tray);

    /* Event loop: process D-Bus messages until quit is signaled */
    while (tray->running) {
        /* Process pending messages. The lock serializes against the mutators
         * which emit signals from foreign threads (issue #405). */
        bus_lock(tray);
        for (;;) {
            r = sd_bus_process(tray->bus, NULL);
            if (r < 0) {
                fprintf(stderr, "sni: bus process error: %s\n", strerror(-r));
                tray->running = 0;
                break;
            }
            if (r == 0) break; /* no more to process */
        }
        bus_unlock(tray);

        /* Fire deferred user callbacks WITHOUT bus_lock held, so they may
         * safely re-enter the mutators from this thread. */
        flush_callbacks(tray);

        if (!tray->running) break;

        /* Wait for bus activity or quit signal (no bus access here). */
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(bus_fd, &rfds);
        FD_SET(tray->quit_pipe[0], &rfds);
        int maxfd = (bus_fd > tray->quit_pipe[0]) ? bus_fd : tray->quit_pipe[0];

        struct timeval tv = {.tv_sec = 1, .tv_usec = 0};
        int sel = select(maxfd + 1, &rfds, NULL, NULL, &tv);
        if (sel < 0 && errno != EINTR) break;
        if (FD_ISSET(tray->quit_pipe[0], &rfds)) {
            tray->running = 0;
        }
    }

    /* Teardown under the lock so a late mutator can't touch a closing bus. */
    bus_lock(tray);
    sd_bus_release_name(tray->bus, tray->bus_name);
    sd_bus_slot_unref(tray->sni_slot);
    sd_bus_slot_unref(tray->menu_slot);
    tray->sni_slot = NULL;
    tray->menu_slot = NULL;
    sd_bus_flush_close_unref(tray->bus);
    tray->bus = NULL;
    bus_unlock(tray);

    return 0;
}

void sni_tray_quit(sni_tray *tray) {
    if (!tray) return;
    tray->running = 0;
    /* Wake the select() */
    char c = 1;
    if (write(tray->quit_pipe[1], &c, 1) < 0) { /* ignore */ }
}

void sni_tray_destroy(sni_tray *tray) {
    if (!tray) return;
    close(tray->quit_pipe[0]);
    close(tray->quit_pipe[1]);
    free(tray->title);
    free(tray->tooltip_text);
    free(tray->bus_name);
    free_pixmap_list(&tray->icon_pixmaps);
    free_menu_items(tray);
    free(tray->pending);
    pthread_mutex_destroy(&tray->click_lock);
    pthread_mutex_destroy(&tray->bus_lock);
    free(tray);
}

/* ========================================================================== */
/*  Public API: Tray properties                                               */
/* ========================================================================== */

void sni_tray_set_icon(sni_tray *tray, const uint8_t *icon_data, size_t icon_len) {
    if (!tray) return;
    bus_lock(tray);
    free_pixmap_list(&tray->icon_pixmaps);
    tray->icon_pixmaps = build_pixmaps(icon_data, icon_len);
    emit_new_icon(tray);
    /* Keep tooltip icon consistent */
    emit_sni_properties_changed(tray, "ToolTip");
    bus_unlock(tray);
}

void sni_tray_set_title(sni_tray *tray, const char *title) {
    if (!tray) return;
    bus_lock(tray);
    free(tray->title);
    tray->title = title ? strdup(title) : NULL;
    emit_new_title(tray);
    bus_unlock(tray);
}

void sni_tray_set_tooltip(sni_tray *tray, const char *tooltip) {
    if (!tray) return;
    bus_lock(tray);
    free(tray->tooltip_text);
    tray->tooltip_text = tooltip ? strdup(tooltip) : NULL;
    emit_sni_properties_changed(tray, "ToolTip");
    bus_unlock(tray);
}

/* ========================================================================== */
/*  Public API: Callbacks                                                     */
/* ========================================================================== */

void sni_tray_set_click_callback(sni_tray *tray, sni_click_cb cb, void *userdata) {
    if (!tray) return;
    tray->on_click = cb;
    tray->on_click_data = userdata;
}

void sni_tray_set_rclick_callback(sni_tray *tray, sni_click_cb cb, void *userdata) {
    if (!tray) return;
    tray->on_rclick = cb;
    tray->on_rclick_data = userdata;
}

void sni_tray_set_menu_callback(sni_tray *tray, sni_menu_item_cb cb, void *userdata) {
    if (!tray) return;
    tray->on_menu_item = cb;
    tray->on_menu_item_data = userdata;
}

void sni_tray_set_menu_opened_callback(sni_tray *tray, sni_menu_opened_cb cb, void *userdata) {
    if (!tray) return;
    tray->on_menu_opened = cb;
    tray->on_menu_opened_data = userdata;
}

void sni_tray_get_last_click_xy(sni_tray *tray, int32_t *x, int32_t *y) {
    if (!tray) return;
    pthread_mutex_lock(&tray->click_lock);
    if (x) *x = tray->last_click_x;
    if (y) *y = tray->last_click_y;
    pthread_mutex_unlock(&tray->click_lock);
}

/* ========================================================================== */
/*  Public API: Menu management                                               */
/* ========================================================================== */

/* Update menu path after adding items (GNOME quirk) */
static void update_menu_path_after_add(sni_tray *tray) {
    if (tray->de == DE_GNOME && strcmp(tray->current_menu_path, MENU_PATH) != 0) {
        tray->current_menu_path = MENU_PATH;
        emit_sni_properties_changed(tray, "Menu");
    }
    /* KDE: always emit LayoutUpdated so items appear */
    emit_layout_updated(tray);
}

void sni_tray_reset_menu(sni_tray *tray) {
    if (!tray) return;
    bus_lock(tray);
    free_menu_items(tray);
    tray->menu_version++;
    emit_layout_updated(tray);

    /* GNOME: revert to "/" when menu is empty */
    if (tray->de == DE_GNOME) {
        tray->current_menu_path = "/";
        emit_sni_properties_changed(tray, "Menu");
    }
    bus_unlock(tray);
}

uint32_t sni_tray_add_menu_item(sni_tray *tray, const char *title,
                                 const char *tooltip) {
    if (!tray) return 0;
    bus_lock(tray);
    menu_item *item = alloc_item(tray);
    if (!item) { bus_unlock(tray); return 0; }
    item->id = (int32_t)tray->next_id++;
    item->label = title ? strdup(title) : NULL;
    item->tooltip = tooltip ? strdup(tooltip) : NULL;
    item->parent_id = 0;
    update_menu_path_after_add(tray);
    uint32_t ret = (uint32_t)item->id;
    bus_unlock(tray);
    return ret;
}

uint32_t sni_tray_add_menu_item_checkbox(sni_tray *tray, const char *title,
                                          const char *tooltip, int checked) {
    if (!tray) return 0;
    bus_lock(tray);
    menu_item *item = alloc_item(tray);
    if (!item) { bus_unlock(tray); return 0; }
    item->id = (int32_t)tray->next_id++;
    item->label = title ? strdup(title) : NULL;
    item->tooltip = tooltip ? strdup(tooltip) : NULL;
    item->parent_id = 0;
    item->checkable = 1;
    item->checked = checked;
    update_menu_path_after_add(tray);
    uint32_t ret = (uint32_t)item->id;
    bus_unlock(tray);
    return ret;
}

void sni_tray_add_separator(sni_tray *tray) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = alloc_item(tray);
    if (!item) { bus_unlock(tray); return; }
    item->id = (int32_t)tray->next_id++;
    item->is_separator = 1;
    item->parent_id = 0;
    update_menu_path_after_add(tray);
    bus_unlock(tray);
}

uint32_t sni_tray_add_sub_menu_item(sni_tray *tray, uint32_t parent_id,
                                     const char *title, const char *tooltip) {
    if (!tray) return 0;
    bus_lock(tray);
    menu_item *item = alloc_item(tray);
    if (!item) { bus_unlock(tray); return 0; }
    item->id = (int32_t)tray->next_id++;
    item->label = title ? strdup(title) : NULL;
    item->tooltip = tooltip ? strdup(tooltip) : NULL;
    item->parent_id = (int32_t)parent_id;
    update_menu_path_after_add(tray);
    uint32_t ret = (uint32_t)item->id;
    bus_unlock(tray);
    return ret;
}

uint32_t sni_tray_add_sub_menu_item_checkbox(sni_tray *tray, uint32_t parent_id,
                                              const char *title, const char *tooltip,
                                              int checked) {
    if (!tray) return 0;
    bus_lock(tray);
    menu_item *item = alloc_item(tray);
    if (!item) { bus_unlock(tray); return 0; }
    item->id = (int32_t)tray->next_id++;
    item->label = title ? strdup(title) : NULL;
    item->tooltip = tooltip ? strdup(tooltip) : NULL;
    item->parent_id = (int32_t)parent_id;
    item->checkable = 1;
    item->checked = checked;
    update_menu_path_after_add(tray);
    uint32_t ret = (uint32_t)item->id;
    bus_unlock(tray);
    return ret;
}

void sni_tray_add_sub_separator(sni_tray *tray, uint32_t parent_id) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = alloc_item(tray);
    if (!item) { bus_unlock(tray); return; }
    item->id = (int32_t)tray->next_id++;
    item->is_separator = 1;
    item->parent_id = (int32_t)parent_id;
    update_menu_path_after_add(tray);
    bus_unlock(tray);
}

/* ========================================================================== */
/*  Public API: Per-item operations                                           */
/* ========================================================================== */

int sni_tray_item_set_title(sni_tray *tray, uint32_t id, const char *title) {
    if (!tray) return 0;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (!item) { bus_unlock(tray); return 0; }
    free(item->label);
    item->label = title ? strdup(title) : NULL;
    emit_layout_updated(tray);
    bus_unlock(tray);
    return 1;
}

void sni_tray_item_enable(sni_tray *tray, uint32_t id) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (item) { item->disabled = 0; emit_layout_updated(tray); }
    bus_unlock(tray);
}

void sni_tray_item_disable(sni_tray *tray, uint32_t id) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (item) { item->disabled = 1; emit_layout_updated(tray); }
    bus_unlock(tray);
}

void sni_tray_item_show(sni_tray *tray, uint32_t id) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (item) { item->visible = 1; emit_layout_updated(tray); }
    bus_unlock(tray);
}

void sni_tray_item_hide(sni_tray *tray, uint32_t id) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (item) { item->visible = 0; emit_layout_updated(tray); }
    bus_unlock(tray);
}

void sni_tray_item_check(sni_tray *tray, uint32_t id) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (item) { item->checked = 1; emit_layout_updated(tray); }
    bus_unlock(tray);
}

void sni_tray_item_uncheck(sni_tray *tray, uint32_t id) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (item) { item->checked = 0; emit_layout_updated(tray); }
    bus_unlock(tray);
}

void sni_tray_item_set_icon(sni_tray *tray, uint32_t id,
                             const uint8_t *icon_data, size_t icon_len) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (!item) { bus_unlock(tray); return; }
    free(item->icon_data);
    item->icon_data = NULL;
    item->icon_len = 0;
    if (icon_data && icon_len > 0) {
        item->icon_data = malloc(icon_len);
        if (item->icon_data) {
            memcpy(item->icon_data, icon_data, icon_len);
            item->icon_len = icon_len;
        }
    }
    emit_layout_updated(tray);
    bus_unlock(tray);
}

void sni_tray_item_set_shortcut(sni_tray *tray, uint32_t id,
                                 const char *key,
                                 int ctrl, int shift, int alt, int super_mod) {
    if (!tray) return;
    bus_lock(tray);
    menu_item *item = find_item(tray, (int32_t)id);
    if (!item) { bus_unlock(tray); return; }
    free(item->shortcut_key);
    item->shortcut_key = key ? strdup(key) : NULL;
    item->shortcut_ctrl = ctrl;
    item->shortcut_shift = shift;
    item->shortcut_alt = alt;
    item->shortcut_super = super_mod;
    emit_layout_updated(tray);
    bus_unlock(tray);
}
