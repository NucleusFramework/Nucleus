/*
 * nucleus_tray_linux.c – Linux system tray: StatusNotifierItem + com.canonical.dbusmenu over
 * sd-bus, with its JNI bridge (dev.nucleusframework.tray.internal.linux.LinuxNativeBridge).
 *
 * Model (same as the macOS backend):
 *   - One tray = one daemon thread that owns its own bus connection, its own well-known name
 *     (org.kde.StatusNotifierItem-<pid>-<n>) and everything sd-bus touches. No other thread ever
 *     uses the bus, so there is no bus lock and an upcall can never deadlock against one.
 *   - nativeSubmit turns the whole wanted state (icon, tooltip, callbacks, the menu flattened in
 *     pre-order) into an immutable nt_state on the calling thread, then publishes it as the
 *     tray's pending state (conflated: a newer one replaces an unapplied older one) and wakes the
 *     loop. The loop applies the newest one and tells the host what changed, in one signal.
 *   - Callbacks are JNI global refs owned by the state that carries them, so they belong to one
 *     tray (or one menu item of it) and die with that state. The loop calls them directly; the
 *     Kotlin side hands them off to another thread at once.
 *   - nativeDispose only marks the tray closing and wakes the loop; the loop closes the
 *     connection (the host drops the item), frees everything and detaches. It never waits.
 */

#include <jni.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <time.h>
#include <unistd.h>

#include <systemd/sd-bus.h>

#include "sni.h"
#include "../shared/jni_utf8.h"
#include "../../../../../native-common/nucleus_jni.h"

#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_STATIC
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#include "stb_image.h"

#define STB_IMAGE_RESIZE_IMPLEMENTATION
#define STB_IMAGE_RESIZE_STATIC
#include "stb_image_resize2.h"

#define SNI_PATH      "/StatusNotifierItem"
#define MENU_PATH     "/StatusNotifierMenu"
#define SNI_IFACE     "org.kde.StatusNotifierItem"
#define MENU_IFACE    "com.canonical.dbusmenu"
#define WATCHER_BUS   "org.kde.StatusNotifierWatcher"
#define WATCHER_PATH  "/StatusNotifierWatcher"
#define WATCHER_IFACE "org.kde.StatusNotifierWatcher"

/* Menu entry flags (LinuxNativeBridge.FLAG_*). */
#define NT_DISABLED  1u
#define NT_CHECKED   2u
#define NT_SEPARATOR 4u
#define NT_CHECKABLE 8u
#define NT_SUBMENU   16u

/* Shortcut modifiers (LinuxNativeBridge.MOD_*). */
#define NT_MOD_CTRL  1u
#define NT_MOD_SHIFT 2u
#define NT_MOD_ALT   4u
#define NT_MOD_SUPER 8u

/* An AboutToShow that follows one of our layout updates this closely is the host refreshing an
 * open menu, not the user opening it; one menu opening also arrives as both AboutToShow and
 * Event("opened") on some hosts. */
#define OPENED_DEBOUNCE_MS 300

/* ========================================================================== */
/*  JVM                                                                       */
/* ========================================================================== */

static JavaVM *g_jvm;
static jmethodID g_run_method;      /* java.lang.Runnable.run()V */
static jmethodID g_click_method;    /* LinuxNativeBridge$ClickCallback.onClick(II)V */
static pthread_mutex_t g_jni_cache_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_live_trays;
static atomic_uint g_next_tray;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

/* Resolved on a Java thread (nativeCreate) so FindClass sees the bridge's class loader. */
static int ensure_jni_cache(JNIEnv *env) {
    int ok;
    pthread_mutex_lock(&g_jni_cache_lock);
    if (!g_run_method) {
        jclass runnable = (*env)->FindClass(env, "java/lang/Runnable");
        if (runnable) g_run_method = (*env)->GetMethodID(env, runnable, "run", "()V");
        nucleus_jni_clear_exception(env);
    }
    if (!g_click_method) {
        jclass click = (*env)->FindClass(
            env, "dev/nucleusframework/tray/internal/linux/LinuxNativeBridge$ClickCallback");
        if (click) g_click_method = (*env)->GetMethodID(env, click, "onClick", "(II)V");
        nucleus_jni_clear_exception(env);
    }
    ok = g_run_method && g_click_method;
    pthread_mutex_unlock(&g_jni_cache_lock);
    return ok;
}

static jobject new_global(JNIEnv *env, jobject obj) {
    return obj ? (*env)->NewGlobalRef(env, obj) : NULL;
}

static void delete_global(JNIEnv *env, jobject ref) {
    if (env && ref) (*env)->DeleteGlobalRef(env, ref);
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ========================================================================== */
/*  Icons                                                                     */
/* ========================================================================== */

typedef struct {
    int      width;
    int      height;
    uint8_t *argb;     /* ARGB32, network byte order */
    size_t   len;
} nt_pixmap;

typedef struct {
    nt_pixmap *levels;
    int        count;
} nt_icon;

static void free_icon(nt_icon *icon) {
    for (int i = 0; i < icon->count; i++) free(icon->levels[i].argb);
    free(icon->levels);
    icon->levels = NULL;
    icon->count = 0;
}

static int add_level(nt_icon *icon, const uint8_t *rgba, int w, int h, int size) {
    uint8_t *resized = malloc((size_t)size * size * 4);
    if (!resized) return 0;
    stbir_resize_uint8_linear(rgba, w, h, w * 4, resized, size, size, size * 4, STBIR_RGBA);
    for (int i = 0; i < size * size; i++) {
        uint8_t r = resized[i * 4], g = resized[i * 4 + 1], b = resized[i * 4 + 2], a = resized[i * 4 + 3];
        resized[i * 4] = a;
        resized[i * 4 + 1] = r;
        resized[i * 4 + 2] = g;
        resized[i * 4 + 3] = b;
    }
    nt_pixmap *p = &icon->levels[icon->count++];
    p->width = size;
    p->height = size;
    p->argb = resized;
    p->len = (size_t)size * size * 4;
    return 1;
}

/* Square pyramid of the image, never upscaled past the source. 0 when the bytes do not decode. */
static int decode_icon(const uint8_t *bytes, size_t len, nt_icon *out) {
    memset(out, 0, sizeof(*out));
    if (!bytes || len == 0 || len > INT32_MAX) return 0;
    int w, h, channels;
    uint8_t *rgba = stbi_load_from_memory(bytes, (int)len, &w, &h, &channels, 4);
    if (!rgba) return 0;
    int src_min = w < h ? w : h;
    int levels = sni_pixmap_level_count_for_source(w, h);
    if (levels <= 0) { stbi_image_free(rgba); return 0; }
    out->levels = calloc((size_t)levels, sizeof(nt_pixmap));
    if (!out->levels) { stbi_image_free(rgba); return 0; }
    for (size_t i = 0; i < SNI_NUM_ICON_SIZES && out->count < levels; i++) {
        if (SNI_ICON_SIZES[i] <= src_min) add_level(out, rgba, w, h, SNI_ICON_SIZES[i]);
    }
    if (out->count == 0) add_level(out, rgba, w, h, src_min);
    stbi_image_free(rgba);
    if (out->count == 0) { free_icon(out); return 0; }
    return 1;
}

/* Whole file in memory; NULL when it cannot be read or is unreasonably large. */
static uint8_t *read_file(const char *path, size_t *len) {
    *len = 0;
    if (!path || !*path) return NULL;
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    uint8_t *buf = NULL;
    if (fseek(f, 0, SEEK_END) == 0) {
        long size = ftell(f);
        if (size > 0 && size <= 64L * 1024 * 1024 && fseek(f, 0, SEEK_SET) == 0) {
            buf = malloc((size_t)size);
            if (buf && fread(buf, 1, (size_t)size, f) != (size_t)size) {
                free(buf);
                buf = NULL;
            }
            if (buf) *len = (size_t)size;
        }
    }
    fclose(f);
    return buf;
}

static int is_png(const uint8_t *bytes, size_t len) {
    static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    return bytes && len > 8 && memcmp(bytes, sig, 8) == 0;
}

/* ========================================================================== */
/*  States                                                                    */
/* ========================================================================== */

/* One menu entry. Its DBusMenu id is its pre-order index + 1 (the root is 0), so an unchanged
 * menu keeps its ids and a host's cache stays valid across submits. */
typedef struct {
    char     *label;         /* DBusMenu label: every '_' doubled, so it is shown literally */
    uint32_t  flags;
    int       depth;
    int32_t   parent;        /* id of the parent entry, 0 for top level */
    int32_t   first_child;   /* id, 0 = none */
    int32_t   next_sibling;  /* id, 0 = none */
    uint8_t  *png;           /* icon-data (PNG only) */
    size_t    png_len;
    char     *key;           /* shortcut key name, NULL = no shortcut */
    uint32_t  mods;
    jobject   callback;      /* Runnable, global ref, may be NULL */
} nt_item;

typedef struct {
    int       keep_icon;     /* the icon did not load: keep showing the current one */
    uint8_t  *icon_src;      /* the icon file's bytes, to tell a new icon from the same one */
    size_t    icon_src_len;
    nt_icon   icon;
    char     *tooltip;
    jobject   click;         /* ClickCallback, global ref, NULL = no primary action */
    jobject   menu_opened;   /* Runnable, global ref, may be NULL */
    int       count;
    nt_item  *items;         /* items[i] has id i + 1 */
    int32_t   root_first;    /* id of the first top-level entry, 0 = empty menu */
} nt_state;

static void free_state(JNIEnv *env, nt_state *s) {
    if (!s) return;
    free(s->icon_src);
    free_icon(&s->icon);
    free(s->tooltip);
    delete_global(env, s->click);
    delete_global(env, s->menu_opened);
    for (int i = 0; i < s->count; i++) {
        free(s->items[i].label);
        free(s->items[i].png);
        free(s->items[i].key);
        delete_global(env, s->items[i].callback);
    }
    free(s->items);
    free(s);
}

static nt_item *item_of(nt_state *s, int32_t id) {
    return (s && id >= 1 && id <= s->count) ? &s->items[id - 1] : NULL;
}

static char *escape_mnemonics(const char *utf8) {
    if (!utf8) return strdup("");
    size_t extra = 0;
    for (const char *p = utf8; *p; p++) if (*p == '_') extra++;
    char *out = malloc(strlen(utf8) + extra + 1);
    if (!out) return NULL;
    char *o = out;
    for (const char *p = utf8; *p; p++) {
        *o++ = *p;
        if (*p == '_') *o++ = '_';
    }
    *o = '\0';
    return out;
}

static int strings_equal(const char *a, const char *b) {
    if (!a || !b) return a == b;
    return strcmp(a, b) == 0;
}

static int bytes_equal(const uint8_t *a, size_t al, const uint8_t *b, size_t bl) {
    return al == bl && (al == 0 || memcmp(a, b, al) == 0);
}

/* ========================================================================== */
/*  Tray                                                                      */
/* ========================================================================== */

typedef struct nt_tray {
    pthread_mutex_t lock;          /* guards pending, closing */
    nt_state       *pending;
    int             closing;
    int             wake_fd;

    /* Everything below belongs to the loop thread. */
    JNIEnv         *env;
    sd_bus         *bus;
    int             bus_dead;
    char            bus_name[96];
    char           *item_id;
    nt_state       *current;
    uint32_t        revision;
    const char     *menu_path;
    int64_t         last_layout_ms;
    int64_t         last_opened_ms;
} nt_tray;

static void wake(nt_tray *t) {
    uint64_t one = 1;
    if (write(t->wake_fd, &one, sizeof(one)) < 0) { /* the counter cannot overflow in practice */ }
}

/* ---- calling into the JVM (loop thread only) ---------------------------- */

static void call_runnable(nt_tray *t, jobject runnable) {
    if (!runnable || !t->env || !g_run_method) return;
    (*t->env)->CallVoidMethod(t->env, runnable, g_run_method);
    nucleus_jni_clear_exception(t->env);
}

static void call_click(nt_tray *t, int32_t x, int32_t y) {
    if (!t->current || !t->current->click || !t->env || !g_click_method) return;
    (*t->env)->CallVoidMethod(t->env, t->current->click, g_click_method, (jint)x, (jint)y);
    nucleus_jni_clear_exception(t->env);
}

static void menu_opened(nt_tray *t) {
    int64_t now = now_ms();
    if (now - t->last_layout_ms < OPENED_DEBOUNCE_MS) return;
    if (now - t->last_opened_ms < OPENED_DEBOUNCE_MS) return;
    t->last_opened_ms = now;
    if (t->current) call_runnable(t, t->current->menu_opened);
}

/* ---- message writers ---------------------------------------------------- */

static int append_pixmaps(sd_bus_message *m, const nt_icon *icon) {
    int r = sd_bus_message_open_container(m, 'a', "(iiay)");
    for (int i = 0; r >= 0 && icon && i < icon->count; i++) {
        r = sd_bus_message_open_container(m, 'r', "iiay");
        if (r >= 0) r = sd_bus_message_append(m, "ii", icon->levels[i].width, icon->levels[i].height);
        if (r >= 0) r = sd_bus_message_append_array(m, 'y', icon->levels[i].argb, icon->levels[i].len);
        if (r >= 0) r = sd_bus_message_close_container(m);
    }
    if (r >= 0) r = sd_bus_message_close_container(m);
    return r;
}

static const nt_icon *shown_icon(nt_tray *t) {
    return t->current ? &t->current->icon : NULL;
}

static const char *shown_tooltip(nt_tray *t) {
    return (t->current && t->current->tooltip) ? t->current->tooltip : "";
}

static int append_tooltip(sd_bus_message *m, nt_tray *t) {
    int r = sd_bus_message_open_container(m, 'r', "sa(iiay)ss");
    if (r >= 0) r = sd_bus_message_append(m, "s", "");
    if (r >= 0) r = append_pixmaps(m, shown_icon(t));
    if (r >= 0) r = sd_bus_message_append(m, "ss", shown_tooltip(t), "");
    if (r >= 0) r = sd_bus_message_close_container(m);
    return r;
}

static int append_shortcut(sd_bus_message *m, const nt_item *it) {
    int r = sd_bus_message_open_container(m, 'e', "sv");
    if (r >= 0) r = sd_bus_message_append(m, "s", "shortcut");
    if (r >= 0) r = sd_bus_message_open_container(m, 'v', "aas");
    if (r >= 0) r = sd_bus_message_open_container(m, 'a', "as");
    if (r >= 0) r = sd_bus_message_open_container(m, 'a', "s");
    if (r >= 0 && (it->mods & NT_MOD_CTRL)) r = sd_bus_message_append(m, "s", "Control");
    if (r >= 0 && (it->mods & NT_MOD_SHIFT)) r = sd_bus_message_append(m, "s", "Shift");
    if (r >= 0 && (it->mods & NT_MOD_ALT)) r = sd_bus_message_append(m, "s", "Alt");
    if (r >= 0 && (it->mods & NT_MOD_SUPER)) r = sd_bus_message_append(m, "s", "Super");
    if (r >= 0) r = sd_bus_message_append(m, "s", it->key);
    for (int i = 0; i < 4 && r >= 0; i++) r = sd_bus_message_close_container(m);
    return r;
}

/* Properties of one entry (or of the root for id 0) as a{sv}. With only_dynamic, just the ones
 * ItemsPropertiesUpdated may change: label, enabled, toggle-state. */
static int append_item_props(sd_bus_message *m, nt_state *s, int32_t id, int only_dynamic) {
    int r = sd_bus_message_open_container(m, 'a', "{sv}");
    if (r < 0) return r;
    if (id == 0) {
        r = sd_bus_message_append(m, "{sv}", "children-display", "s", "submenu");
    } else {
        const nt_item *it = item_of(s, id);
        if (it && (it->flags & NT_SEPARATOR)) {
            if (!only_dynamic) r = sd_bus_message_append(m, "{sv}", "type", "s", "separator");
        } else if (it) {
            r = sd_bus_message_append(m, "{sv}", "label", "s", it->label);
            if (r >= 0) r = sd_bus_message_append(m, "{sv}", "enabled", "b", !(it->flags & NT_DISABLED));
            if (r >= 0 && (it->flags & NT_CHECKABLE)) {
                if (!only_dynamic) r = sd_bus_message_append(m, "{sv}", "toggle-type", "s", "checkmark");
                if (r >= 0) r = sd_bus_message_append(m, "{sv}", "toggle-state", "i", (it->flags & NT_CHECKED) ? 1 : 0);
            }
            if (!only_dynamic) {
                if (r >= 0 && (it->flags & NT_SUBMENU))
                    r = sd_bus_message_append(m, "{sv}", "children-display", "s", "submenu");
                if (r >= 0 && it->png) {
                    r = sd_bus_message_open_container(m, 'e', "sv");
                    if (r >= 0) r = sd_bus_message_append(m, "s", "icon-data");
                    if (r >= 0) r = sd_bus_message_open_container(m, 'v', "ay");
                    if (r >= 0) r = sd_bus_message_append_array(m, 'y', it->png, it->png_len);
                    if (r >= 0) r = sd_bus_message_close_container(m);
                    if (r >= 0) r = sd_bus_message_close_container(m);
                }
                if (r >= 0 && it->key) r = append_shortcut(m, it);
            }
        }
    }
    if (r >= 0) r = sd_bus_message_close_container(m);
    return r;
}

/* Each level of a layout nests three containers (struct, array, variant), and D-Bus refuses a
 * message nested deeper than 64: dbus-daemon disconnects the sender, which would take the whole
 * tray down. One reply therefore carries at most this many levels below the requested parent;
 * deeper submenus come without children, and a host that asks for that subtree gets the rest. */
#define MAX_LAYOUT_LEVELS 16

static int append_layout(sd_bus_message *m, nt_state *s, int32_t id, int32_t depth) {
    int r = sd_bus_message_open_container(m, 'r', "ia{sv}av");
    if (r >= 0) r = sd_bus_message_append(m, "i", id);
    if (r >= 0) r = append_item_props(m, s, id, 0);
    if (r >= 0) r = sd_bus_message_open_container(m, 'a', "v");
    if (r >= 0 && depth != 0) {
        if (depth < 0 || depth > MAX_LAYOUT_LEVELS) depth = MAX_LAYOUT_LEVELS;
        int32_t child = 0;
        if (id == 0) child = s ? s->root_first : 0;
        else if (item_of(s, id)) child = item_of(s, id)->first_child;
        int32_t next_depth = depth - 1;
        while (r >= 0 && child) {
            r = sd_bus_message_open_container(m, 'v', "(ia{sv}av)");
            if (r >= 0) r = append_layout(m, s, child, next_depth);
            if (r >= 0) r = sd_bus_message_close_container(m);
            child = item_of(s, child)->next_sibling;
        }
    }
    if (r >= 0) r = sd_bus_message_close_container(m);
    if (r >= 0) r = sd_bus_message_close_container(m);
    return r;
}

/* ========================================================================== */
/*  org.kde.StatusNotifierItem                                                */
/* ========================================================================== */

static int sni_property(sd_bus *bus, const char *path, const char *interface, const char *property,
                        sd_bus_message *reply, void *userdata, sd_bus_error *error) {
    (void)bus; (void)path; (void)interface; (void)error;
    nt_tray *t = userdata;
    if (!strcmp(property, "Category")) return sd_bus_message_append(reply, "s", "ApplicationStatus");
    if (!strcmp(property, "Id")) return sd_bus_message_append(reply, "s", t->item_id ? t->item_id : "");
    if (!strcmp(property, "Title")) return sd_bus_message_append(reply, "s", shown_tooltip(t));
    if (!strcmp(property, "Status")) return sd_bus_message_append(reply, "s", "Active");
    if (!strcmp(property, "WindowId")) return sd_bus_message_append(reply, "i", 0);
    if (!strcmp(property, "IconThemePath")) return sd_bus_message_append(reply, "s", "");
    if (!strcmp(property, "Menu")) return sd_bus_message_append(reply, "o", t->menu_path);
    /* True when there is no primary action: the host then opens the menu on a left click. */
    if (!strcmp(property, "ItemIsMenu"))
        return sd_bus_message_append(reply, "b", !(t->current && t->current->click));
    if (!strcmp(property, "IconName")) return sd_bus_message_append(reply, "s", "");
    if (!strcmp(property, "IconPixmap")) return append_pixmaps(reply, shown_icon(t));
    if (!strcmp(property, "OverlayIconName")) return sd_bus_message_append(reply, "s", "");
    if (!strcmp(property, "OverlayIconPixmap")) return append_pixmaps(reply, NULL);
    if (!strcmp(property, "AttentionIconName")) return sd_bus_message_append(reply, "s", "");
    if (!strcmp(property, "AttentionIconPixmap")) return append_pixmaps(reply, NULL);
    if (!strcmp(property, "AttentionMovieName")) return sd_bus_message_append(reply, "s", "");
    if (!strcmp(property, "ToolTip")) return append_tooltip(reply, t);
    return -ENOENT;
}

static int sni_activate(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)error;
    nt_tray *t = userdata;
    int32_t x = 0, y = 0;
    int r = sd_bus_message_read(m, "ii", &x, &y);
    if (r < 0) return r;
    r = sd_bus_reply_method_return(m, "");
    call_click(t, x, y);
    return r;
}

static int sni_ignored(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)userdata; (void)error;
    return sd_bus_reply_method_return(m, "");
}

static const sd_bus_vtable sni_vtable[] = {
    SD_BUS_VTABLE_START(0),
    SD_BUS_PROPERTY("Category", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_PROPERTY("Id", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_PROPERTY("Title", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("Status", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("WindowId", "i", sni_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_PROPERTY("IconThemePath", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_PROPERTY("Menu", "o", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("ItemIsMenu", "b", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("IconName", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("IconPixmap", "a(iiay)", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("OverlayIconName", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("OverlayIconPixmap", "a(iiay)", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("AttentionIconName", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("AttentionIconPixmap", "a(iiay)", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("AttentionMovieName", "s", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_PROPERTY("ToolTip", "(sa(iiay)ss)", sni_property, 0, SD_BUS_VTABLE_PROPERTY_EMITS_CHANGE),
    SD_BUS_METHOD("Activate", "ii", "", sni_activate, SD_BUS_VTABLE_UNPRIVILEGED),
    /* No host-drawn context menu of our own (the menu is the DBusMenu); middle click and the
     * wheel have no meaning for a tray. */
    SD_BUS_METHOD("ContextMenu", "ii", "", sni_ignored, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("SecondaryActivate", "ii", "", sni_ignored, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("Scroll", "is", "", sni_ignored, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_SIGNAL("NewTitle", "", 0),
    SD_BUS_SIGNAL("NewIcon", "", 0),
    SD_BUS_SIGNAL("NewAttentionIcon", "", 0),
    SD_BUS_SIGNAL("NewOverlayIcon", "", 0),
    SD_BUS_SIGNAL("NewToolTip", "", 0),
    SD_BUS_SIGNAL("NewStatus", "s", 0),
    SD_BUS_SIGNAL("NewIconThemePath", "s", 0),
    SD_BUS_SIGNAL("NewMenu", "", 0),
    SD_BUS_VTABLE_END
};

/* ========================================================================== */
/*  com.canonical.dbusmenu                                                    */
/* ========================================================================== */

static int menu_property(sd_bus *bus, const char *path, const char *interface, const char *property,
                         sd_bus_message *reply, void *userdata, sd_bus_error *error) {
    (void)bus; (void)path; (void)interface; (void)error;
    nt_tray *t = userdata;
    if (!strcmp(property, "Version")) return sd_bus_message_append(reply, "u", 3u);
    if (!strcmp(property, "TextDirection")) return sd_bus_message_append(reply, "s", "ltr");
    if (!strcmp(property, "Status")) return sd_bus_message_append(reply, "s", "normal");
    if (!strcmp(property, "IconThemePath")) return sd_bus_message_append(reply, "as", 0);
    (void)t;
    return -ENOENT;
}

static int known_id(nt_tray *t, int32_t id) {
    return id == 0 || item_of(t->current, id) != NULL;
}

static int menu_get_layout(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    nt_tray *t = userdata;
    int32_t parent = 0, depth = -1;
    int r = sd_bus_message_read(m, "ii", &parent, &depth);
    if (r >= 0) r = sd_bus_message_skip(m, "as");
    if (r < 0) return r;
    if (!known_id(t, parent))
        return sd_bus_error_setf(error, SD_BUS_ERROR_INVALID_ARGS, "No menu item %d", parent);
    sd_bus_message *reply = NULL;
    r = sd_bus_message_new_method_return(m, &reply);
    if (r >= 0) r = sd_bus_message_append(reply, "u", t->revision);
    if (r >= 0) r = append_layout(reply, t->current, parent, depth);
    if (r >= 0) r = sd_bus_send(NULL, reply, NULL);
    sd_bus_message_unref(reply);
    return r;
}

static int menu_get_group_properties(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)error;
    nt_tray *t = userdata;
    sd_bus_message *reply = NULL;
    int r = sd_bus_message_new_method_return(m, &reply);
    if (r >= 0) r = sd_bus_message_open_container(reply, 'a', "(ia{sv})");
    if (r >= 0) r = sd_bus_message_enter_container(m, 'a', "i");
    int32_t id;
    while (r >= 0 && (r = sd_bus_message_read(m, "i", &id)) > 0) {
        if (!known_id(t, id)) continue;
        r = sd_bus_message_open_container(reply, 'r', "ia{sv}");
        if (r >= 0) r = sd_bus_message_append(reply, "i", id);
        if (r >= 0) r = append_item_props(reply, t->current, id, 0);
        if (r >= 0) r = sd_bus_message_close_container(reply);
    }
    if (r >= 0) r = sd_bus_message_exit_container(m);
    if (r >= 0) r = sd_bus_message_close_container(reply);
    if (r >= 0) r = sd_bus_send(NULL, reply, NULL);
    sd_bus_message_unref(reply);
    return r;
}

static int menu_get_property(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    nt_tray *t = userdata;
    int32_t id;
    const char *name;
    int r = sd_bus_message_read(m, "is", &id, &name);
    if (r < 0) return r;
    const nt_item *it = item_of(t->current, id);
    if (id == 0 && !strcmp(name, "children-display"))
        return sd_bus_reply_method_return(m, "v", "s", "submenu");
    if (it && (it->flags & NT_SEPARATOR) && !strcmp(name, "type"))
        return sd_bus_reply_method_return(m, "v", "s", "separator");
    if (it && !(it->flags & NT_SEPARATOR)) {
        if (!strcmp(name, "label")) return sd_bus_reply_method_return(m, "v", "s", it->label);
        if (!strcmp(name, "enabled")) return sd_bus_reply_method_return(m, "v", "b", !(it->flags & NT_DISABLED));
        if ((it->flags & NT_CHECKABLE) && !strcmp(name, "toggle-type"))
            return sd_bus_reply_method_return(m, "v", "s", "checkmark");
        if ((it->flags & NT_CHECKABLE) && !strcmp(name, "toggle-state"))
            return sd_bus_reply_method_return(m, "v", "i", (it->flags & NT_CHECKED) ? 1 : 0);
        if ((it->flags & NT_SUBMENU) && !strcmp(name, "children-display"))
            return sd_bus_reply_method_return(m, "v", "s", "submenu");
    }
    return sd_bus_error_setf(error, SD_BUS_ERROR_INVALID_ARGS, "No property %s on menu item %d", name, id);
}

/* A click reaches the item's callback only when the user could have made it: the item exists in
 * the current menu, is a plain or checkable entry (not a separator, not a submenu) and is enabled,
 * as is every submenu above it. */
static void menu_clicked(nt_tray *t, int32_t id) {
    nt_item *it = item_of(t->current, id);
    if (!it || (it->flags & (NT_SEPARATOR | NT_SUBMENU | NT_DISABLED))) return;
    for (int32_t p = it->parent; p; p = item_of(t->current, p)->parent) {
        if (item_of(t->current, p)->flags & NT_DISABLED) return;
    }
    call_runnable(t, it->callback);
}

static void menu_event_one(nt_tray *t, int32_t id, const char *event) {
    if (!strcmp(event, "clicked")) menu_clicked(t, id);
    else if (id == 0 && !strcmp(event, "opened")) menu_opened(t);
}

static int menu_event(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    nt_tray *t = userdata;
    int32_t id;
    const char *event;
    int r = sd_bus_message_read(m, "is", &id, &event);
    if (r < 0) return r;
    if (!known_id(t, id))
        return sd_bus_error_setf(error, SD_BUS_ERROR_INVALID_ARGS, "No menu item %d", id);
    /* event lives in the message, which stays referenced by sd-bus until the handler returns. */
    char *copy = strdup(event);
    r = sd_bus_reply_method_return(m, "");
    if (copy) menu_event_one(t, id, copy);
    free(copy);
    return r;
}

static int menu_event_group(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)error;
    nt_tray *t = userdata;
    /* Collected first, run after the reply: a callback must never hold the host waiting. */
    int cap = 8, n = 0, unknown_n = 0;
    int32_t *ids = malloc(sizeof(int32_t) * cap);
    char **events = malloc(sizeof(char *) * cap);
    int32_t *unknown = malloc(sizeof(int32_t) * cap);
    int r = (ids && events && unknown) ? 0 : -ENOMEM;
    if (r >= 0) r = sd_bus_message_enter_container(m, 'a', "(isvu)");
    while (r >= 0 && (r = sd_bus_message_enter_container(m, 'r', "isvu")) > 0) {
        int32_t id;
        const char *event;
        r = sd_bus_message_read(m, "is", &id, &event);
        if (r >= 0) r = sd_bus_message_skip(m, "vu");
        if (r >= 0) r = sd_bus_message_exit_container(m);
        if (r < 0) break;
        if (n == cap) {
            cap *= 2;
            int32_t *ni = realloc(ids, sizeof(int32_t) * cap);
            if (ni) ids = ni;
            char **ne = realloc(events, sizeof(char *) * cap);
            if (ne) events = ne;
            int32_t *nu = realloc(unknown, sizeof(int32_t) * cap);
            if (nu) unknown = nu;
            if (!ni || !ne || !nu) { r = -ENOMEM; break; }
        }
        if (known_id(t, id)) {
            ids[n] = id;
            events[n] = strdup(event);
            n++;
        } else {
            unknown[unknown_n++] = id;
        }
    }
    if (r >= 0) r = sd_bus_message_exit_container(m);
    if (r >= 0) {
        sd_bus_message *reply = NULL;
        r = sd_bus_message_new_method_return(m, &reply);
        if (r >= 0) r = sd_bus_message_append_array(reply, 'i', unknown, sizeof(int32_t) * unknown_n);
        if (r >= 0) r = sd_bus_send(NULL, reply, NULL);
        sd_bus_message_unref(reply);
    }
    for (int i = 0; i < n; i++) {
        if (r >= 0 && events[i]) menu_event_one(t, ids[i], events[i]);
        free(events[i]);
    }
    free(ids);
    free(events);
    free(unknown);
    return r;
}

static int menu_about_to_show(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)error;
    nt_tray *t = userdata;
    int32_t id;
    int r = sd_bus_message_read(m, "i", &id);
    if (r < 0) return r;
    r = sd_bus_reply_method_return(m, "b", 0);
    if (id == 0) menu_opened(t);
    return r;
}

static int menu_about_to_show_group(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)error;
    nt_tray *t = userdata;
    int root = 0, unknown_n = 0, cap = 8;
    int32_t *unknown = malloc(sizeof(int32_t) * cap);
    int r = unknown ? sd_bus_message_enter_container(m, 'a', "i") : -ENOMEM;
    int32_t id;
    while (r >= 0 && (r = sd_bus_message_read(m, "i", &id)) > 0) {
        if (id == 0) root = 1;
        if (known_id(t, id)) continue;
        if (unknown_n == cap) {
            int32_t *nu = realloc(unknown, sizeof(int32_t) * (cap *= 2));
            if (!nu) { r = -ENOMEM; break; }
            unknown = nu;
        }
        unknown[unknown_n++] = id;
    }
    if (r >= 0) r = sd_bus_message_exit_container(m);
    if (r >= 0) {
        sd_bus_message *reply = NULL;
        r = sd_bus_message_new_method_return(m, &reply);
        if (r >= 0) r = sd_bus_message_append(reply, "ai", 0);
        if (r >= 0) r = sd_bus_message_append_array(reply, 'i', unknown, sizeof(int32_t) * unknown_n);
        if (r >= 0) r = sd_bus_send(NULL, reply, NULL);
        sd_bus_message_unref(reply);
    }
    free(unknown);
    if (r >= 0 && root) menu_opened(t);
    return r;
}

static const sd_bus_vtable menu_vtable[] = {
    SD_BUS_VTABLE_START(0),
    SD_BUS_PROPERTY("Version", "u", menu_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_PROPERTY("TextDirection", "s", menu_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_PROPERTY("Status", "s", menu_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_PROPERTY("IconThemePath", "as", menu_property, 0, SD_BUS_VTABLE_PROPERTY_CONST),
    SD_BUS_METHOD("GetLayout", "iias", "u(ia{sv}av)", menu_get_layout, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("GetGroupProperties", "aias", "a(ia{sv})", menu_get_group_properties, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("GetProperty", "is", "v", menu_get_property, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("Event", "isvu", "", menu_event, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("EventGroup", "a(isvu)", "ai", menu_event_group, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("AboutToShow", "i", "b", menu_about_to_show, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("AboutToShowGroup", "ai", "aiai", menu_about_to_show_group, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_SIGNAL("ItemsPropertiesUpdated", "a(ia{sv})a(ias)", 0),
    SD_BUS_SIGNAL("LayoutUpdated", "ui", 0),
    SD_BUS_SIGNAL("ItemActivationRequested", "iu", 0),
    SD_BUS_VTABLE_END
};

/* ========================================================================== */
/*  Watcher                                                                   */
/* ========================================================================== */

static int registered_reply(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)userdata; (void)error;
    /* No watcher (yet) is normal: the item appears once a panel starts one (NameOwnerChanged). */
    (void)m;
    return 0;
}

static void register_with_watcher(nt_tray *t) {
    sd_bus_call_method_async(t->bus, NULL, WATCHER_BUS, WATCHER_PATH, WATCHER_IFACE,
                             "RegisterStatusNotifierItem", registered_reply, t, "s", t->bus_name);
}

/* A panel that (re)starts brings a new watcher, which knows nothing of us: register again. */
static int watcher_owner_changed(sd_bus_message *m, void *userdata, sd_bus_error *error) {
    (void)error;
    nt_tray *t = userdata;
    const char *name, *old_owner, *new_owner;
    if (sd_bus_message_read(m, "sss", &name, &old_owner, &new_owner) < 0) return 0;
    if (!strcmp(name, WATCHER_BUS) && new_owner && *new_owner) register_with_watcher(t);
    return 0;
}

/* ========================================================================== */
/*  Applying a state (loop thread)                                            */
/* ========================================================================== */

static int same_shape(const nt_state *a, const nt_state *b) {
    if (a->count != b->count || a->root_first != b->root_first) return 0;
    for (int i = 0; i < a->count; i++) {
        const nt_item *x = &a->items[i], *y = &b->items[i];
        uint32_t layout_flags = NT_SEPARATOR | NT_CHECKABLE | NT_SUBMENU;
        if (x->parent != y->parent || x->first_child != y->first_child || x->next_sibling != y->next_sibling)
            return 0;
        if ((x->flags & layout_flags) != (y->flags & layout_flags)) return 0;
        if (!bytes_equal(x->png, x->png_len, y->png, y->png_len)) return 0;
        if (!strings_equal(x->key, y->key) || x->mods != y->mods) return 0;
    }
    return 1;
}

static int dynamic_props_equal(const nt_item *x, const nt_item *y) {
    return strings_equal(x->label, y->label) &&
           (x->flags & (NT_DISABLED | NT_CHECKED)) == (y->flags & (NT_DISABLED | NT_CHECKED));
}

static void emit_items_properties(nt_tray *t, const nt_state *old, nt_state *now) {
    sd_bus_message *m = NULL;
    int r = sd_bus_message_new_signal(t->bus, &m, MENU_PATH, MENU_IFACE, "ItemsPropertiesUpdated");
    if (r >= 0) r = sd_bus_message_open_container(m, 'a', "(ia{sv})");
    for (int i = 0; r >= 0 && i < now->count; i++) {
        if (dynamic_props_equal(&old->items[i], &now->items[i])) continue;
        r = sd_bus_message_open_container(m, 'r', "ia{sv}");
        if (r >= 0) r = sd_bus_message_append(m, "i", (int32_t)(i + 1));
        if (r >= 0) r = append_item_props(m, now, i + 1, 1);
        if (r >= 0) r = sd_bus_message_close_container(m);
    }
    if (r >= 0) r = sd_bus_message_close_container(m);
    if (r >= 0) r = sd_bus_message_append(m, "a(ias)", 0);
    if (r >= 0) sd_bus_send(t->bus, m, NULL);
    sd_bus_message_unref(m);
}

static void emit_layout_updated(nt_tray *t) {
    t->revision++;
    t->last_layout_ms = now_ms();
    sd_bus_emit_signal(t->bus, MENU_PATH, MENU_IFACE, "LayoutUpdated", "ui", t->revision, (int32_t)0);
}

static int menu_equal(const nt_state *a, const nt_state *b) {
    if (!same_shape(a, b)) return 0;
    for (int i = 0; i < a->count; i++) {
        if (!dynamic_props_equal(&a->items[i], &b->items[i])) return 0;
    }
    return 1;
}

static int is_gnome(void) {
    const char *xdg = getenv("XDG_CURRENT_DESKTOP");
    return xdg && strcasestr(xdg, "gnome");
}

/* GNOME's AppIndicator extension shows an empty menu for an empty layout; "/" hides it. */
static const char *menu_path_for(const nt_state *s) {
    return (s->count == 0 && is_gnome()) ? "/" : MENU_PATH;
}

static void apply_state(nt_tray *t, nt_state *now) {
    nt_state *old = t->current;
    if (now->keep_icon && old) {
        now->icon = old->icon;
        now->icon_src = old->icon_src;
        now->icon_src_len = old->icon_src_len;
        memset(&old->icon, 0, sizeof(old->icon));
        old->icon_src = NULL;
        old->icon_src_len = 0;
    }
    t->current = now;
    if (!t->bus || t->bus_dead) {
        free_state(t->env, old);
        return;
    }

    int icon_changed = !old || !bytes_equal(old->icon_src, old->icon_src_len, now->icon_src, now->icon_src_len);
    int tooltip_changed = !old || !strings_equal(old->tooltip, now->tooltip);
    int item_is_menu_changed = !old || (!old->click != !now->click);
    const char *path = menu_path_for(now);
    int path_changed = path != t->menu_path;
    t->menu_path = path;

    if (icon_changed) sd_bus_emit_signal(t->bus, SNI_PATH, SNI_IFACE, "NewIcon", "");
    if (tooltip_changed) {
        sd_bus_emit_signal(t->bus, SNI_PATH, SNI_IFACE, "NewTitle", "");
        sd_bus_emit_signal(t->bus, SNI_PATH, SNI_IFACE, "NewToolTip", "");
    }
    char *changed[6];
    int n_changed = 0;
    if (icon_changed) changed[n_changed++] = "IconPixmap";
    if (icon_changed || tooltip_changed) changed[n_changed++] = "ToolTip";
    if (tooltip_changed) changed[n_changed++] = "Title";
    if (item_is_menu_changed) changed[n_changed++] = "ItemIsMenu";
    if (path_changed) changed[n_changed++] = "Menu";
    changed[n_changed] = NULL;
    if (n_changed) sd_bus_emit_properties_changed_strv(t->bus, SNI_PATH, SNI_IFACE, changed);
    if (path_changed) sd_bus_emit_signal(t->bus, SNI_PATH, SNI_IFACE, "NewMenu", "");

    if (!old) {
        emit_layout_updated(t);
    } else if (!menu_equal(old, now)) {
        if (same_shape(old, now)) emit_items_properties(t, old, now);
        else emit_layout_updated(t);
    }
    free_state(t->env, old);
}

/* ========================================================================== */
/*  Loop                                                                      */
/* ========================================================================== */

static int open_bus(nt_tray *t) {
    int r = sd_bus_open_user(&t->bus);
    if (r < 0) {
        fprintf(stderr, "nucleus_tray: no session bus (%s); the tray icon cannot be shown\n", strerror(-r));
        t->bus = NULL;
        return r;
    }
    r = sd_bus_add_object_vtable(t->bus, NULL, SNI_PATH, SNI_IFACE, sni_vtable, t);
    if (r >= 0) r = sd_bus_add_object_vtable(t->bus, NULL, MENU_PATH, MENU_IFACE, menu_vtable, t);
    if (r >= 0) r = sd_bus_request_name(t->bus, t->bus_name, 0);
    if (r >= 0)
        r = sd_bus_match_signal(t->bus, NULL, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                                "org.freedesktop.DBus", "NameOwnerChanged", watcher_owner_changed, t);
    if (r < 0) {
        fprintf(stderr, "nucleus_tray: cannot export the tray on the session bus (%s)\n", strerror(-r));
        t->bus = sd_bus_flush_close_unref(t->bus);
        return r;
    }
    register_with_watcher(t);
    return 0;
}

static int poll_timeout_ms(sd_bus *bus) {
    uint64_t until;
    if (sd_bus_get_timeout(bus, &until) < 0 || until == UINT64_MAX) return -1;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now = (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
    if (until <= now) return 0;
    uint64_t ms = (until - now + 999) / 1000;
    return ms > 60000 ? 60000 : (int)ms;
}

static void *tray_loop(void *arg) {
    nt_tray *t = arg;
    char thread_name[32];
    snprintf(thread_name, sizeof(thread_name), "NucleusTray-%s", strrchr(t->bus_name, '-') + 1);
    JavaVMAttachArgs args = {JNI_VERSION_1_8, thread_name, NULL};
    if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&t->env, &args) != JNI_OK) t->env = NULL;

    open_bus(t);

    for (;;) {
        pthread_mutex_lock(&t->lock);
        nt_state *next = t->pending;
        t->pending = NULL;
        int closing = t->closing;
        pthread_mutex_unlock(&t->lock);

        if (closing) {
            free_state(t->env, next);
            break;
        }
        if (next) apply_state(t, next);

        if (t->bus && !t->bus_dead) {
            int r;
            while ((r = sd_bus_process(t->bus, NULL)) > 0) { }
            if (r < 0) {
                /* The session bus went away: nothing to show the icon on any more. */
                fprintf(stderr, "nucleus_tray: session bus lost (%s)\n", strerror(-r));
                t->bus_dead = 1;
            }
        }

        struct pollfd fds[2] = {{t->wake_fd, POLLIN, 0}, {-1, 0, 0}};
        int nfds = 1, timeout = -1;
        if (t->bus && !t->bus_dead) {
            fds[1].fd = sd_bus_get_fd(t->bus);
            int events = sd_bus_get_events(t->bus);
            fds[1].events = (short)(events > 0 ? events : POLLIN);
            nfds = 2;
            timeout = poll_timeout_ms(t->bus);
        }
        if (poll(fds, (nfds_t)nfds, timeout) < 0 && errno != EINTR) break;
        if (fds[0].revents & POLLIN) {
            uint64_t count;
            if (read(t->wake_fd, &count, sizeof(count)) < 0) { /* drained */ }
        }
    }

    /* Closing the connection drops the name: the host removes the item at once. */
    if (t->bus) sd_bus_flush_close_unref(t->bus);
    t->bus = NULL;
    free_state(t->env, t->current);
    close(t->wake_fd);
    pthread_mutex_destroy(&t->lock);
    free(t->item_id);
    free(t);
    atomic_fetch_sub(&g_live_trays, 1);
    (*g_jvm)->DetachCurrentThread(g_jvm);
    return NULL;
}

/* ========================================================================== */
/*  JNI                                                                       */
/* ========================================================================== */

#define BRIDGE(name) Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_##name

JNIEXPORT jlong JNICALL BRIDGE(nativeCreate)(JNIEnv *env, jclass clazz, jstring itemId) {
    (void)clazz;
    if (!ensure_jni_cache(env)) return 0;
    nt_tray *t = calloc(1, sizeof(nt_tray));
    if (!t) return 0;
    t->wake_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (t->wake_fd < 0) { free(t); return 0; }
    pthread_mutex_init(&t->lock, NULL);
    t->item_id = jni_utf8_dup(env, itemId);
    t->revision = 1;
    t->menu_path = MENU_PATH;
    snprintf(t->bus_name, sizeof(t->bus_name), "org.kde.StatusNotifierItem-%d-%u",
             (int)getpid(), atomic_fetch_add(&g_next_tray, 1) + 1);

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    atomic_fetch_add(&g_live_trays, 1);
    int r = pthread_create(&thread, &attr, tray_loop, t);
    pthread_attr_destroy(&attr);
    if (r != 0) {
        atomic_fetch_sub(&g_live_trays, 1);
        close(t->wake_fd);
        pthread_mutex_destroy(&t->lock);
        free(t->item_id);
        free(t);
        return 0;
    }
    return (jlong)(uintptr_t)t;
}

static jobject array_element(JNIEnv *env, jobjectArray array, jsize i) {
    return array ? (*env)->GetObjectArrayElement(env, array, i) : NULL;
}

static char *string_element(JNIEnv *env, jobjectArray array, jsize i) {
    jstring s = (jstring)array_element(env, array, i);
    char *out = s ? jni_utf8_dup(env, s) : NULL;
    if (s) (*env)->DeleteLocalRef(env, s);
    return out;
}

static char *path_string(JNIEnv *env, jstring s) {
    return s ? jni_utf8_dup(env, s) : NULL;
}

JNIEXPORT void JNICALL BRIDGE(nativeSubmit)(
    JNIEnv *env, jclass clazz, jlong handle, jstring iconPath, jstring tooltip, jobject click,
    jobject menuOpened, jobjectArray labels, jobjectArray iconPaths, jobjectArray shortcutKeys,
    jintArray shortcutMods, jintArray flags, jintArray depths, jobjectArray callbacks)
{
    (void)clazz;
    nt_tray *t = (nt_tray *)(uintptr_t)handle;
    if (!t) return;
    nt_state *s = calloc(1, sizeof(nt_state));
    if (!s) return;

    char *icon = path_string(env, iconPath);
    s->icon_src = read_file(icon, &s->icon_src_len);
    free(icon);
    if (!s->icon_src || !decode_icon(s->icon_src, s->icon_src_len, &s->icon)) {
        free(s->icon_src);
        s->icon_src = NULL;
        s->icon_src_len = 0;
        s->keep_icon = 1;
    }
    s->tooltip = tooltip ? jni_utf8_dup(env, tooltip) : strdup("");
    s->click = new_global(env, click);
    s->menu_opened = new_global(env, menuOpened);

    jsize n = labels ? (*env)->GetArrayLength(env, labels) : 0;
    jint *f = n ? (*env)->GetIntArrayElements(env, flags, NULL) : NULL;
    jint *d = n ? (*env)->GetIntArrayElements(env, depths, NULL) : NULL;
    jint *mods = n ? (*env)->GetIntArrayElements(env, shortcutMods, NULL) : NULL;
    s->items = n ? calloc((size_t)n, sizeof(nt_item)) : NULL;
    if (n && (!f || !d || !mods || !s->items)) n = 0;
    s->count = (int)n;

    /* Pre-order with depths: entry i's parent is the closest earlier entry one level up. The
     * last entry seen at each depth gives both the parent and the previous sibling. */
    int32_t *last_at = calloc((size_t)n + 2, sizeof(int32_t));
    if (n && !last_at) s->count = n = 0;
    int prev_depth = -1;
    for (jsize i = 0; i < n; i++) {
        nt_item *it = &s->items[i];
        int32_t id = (int32_t)i + 1;
        int depth = d[i] < 0 ? 0 : d[i];
        if (depth > prev_depth + 1) depth = prev_depth + 1; /* malformed input: clamp */
        prev_depth = depth;
        it->depth = depth;
        it->flags = (uint32_t)f[i];
        it->parent = depth == 0 ? 0 : last_at[depth - 1];
        if (it->parent && !(item_of(s, it->parent)->flags & NT_SUBMENU)) item_of(s, it->parent)->flags |= NT_SUBMENU;
        /* A new entry at this depth closes every deeper level. */
        int32_t prev = last_at[depth];
        if (prev && item_of(s, prev)->parent == it->parent) item_of(s, prev)->next_sibling = id;
        else if (it->parent) item_of(s, it->parent)->first_child = id;
        else s->root_first = id;
        last_at[depth] = id;
        /* Only the next level can hold an entry: deeper ones were cleared when it was set. */
        last_at[depth + 1] = 0;

        if (!(it->flags & NT_SEPARATOR)) {
            char *label = string_element(env, labels, i);
            it->label = escape_mnemonics(label);
            free(label);
            if (!it->label) it->label = strdup("");
            char *icon_path = string_element(env, iconPaths, i);
            it->png = read_file(icon_path, &it->png_len);
            free(icon_path);
            if (!is_png(it->png, it->png_len)) {
                free(it->png);
                it->png = NULL;
                it->png_len = 0;
            }
            it->key = string_element(env, shortcutKeys, i);
            if (it->key && !*it->key) { free(it->key); it->key = NULL; }
            it->mods = (uint32_t)mods[i];
            jobject cb = array_element(env, callbacks, i);
            it->callback = new_global(env, cb);
            if (cb) (*env)->DeleteLocalRef(env, cb);
        }
    }
    free(last_at);
    if (f) (*env)->ReleaseIntArrayElements(env, flags, f, JNI_ABORT);
    if (d) (*env)->ReleaseIntArrayElements(env, depths, d, JNI_ABORT);
    if (mods) (*env)->ReleaseIntArrayElements(env, shortcutMods, mods, JNI_ABORT);

    pthread_mutex_lock(&t->lock);
    nt_state *dropped = t->pending;
    t->pending = s;
    pthread_mutex_unlock(&t->lock);
    /* A newer state replaced one the loop had not applied yet: it is never shown. */
    free_state(env, dropped);
    wake(t);
}

JNIEXPORT void JNICALL BRIDGE(nativeDispose)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    nt_tray *t = (nt_tray *)(uintptr_t)handle;
    if (!t) return;
    pthread_mutex_lock(&t->lock);
    t->closing = 1;
    pthread_mutex_unlock(&t->lock);
    wake(t);
}

JNIEXPORT jint JNICALL BRIDGE(nativeDebugLiveTrays)(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jint)atomic_load(&g_live_trays);
}
