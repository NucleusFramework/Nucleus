/*
 * JNI bridge for the X11 CLIPBOARD selection via XCB + XFixes.
 *
 * XCB and XFixes are resolved lazily through dlopen at init time — the .so
 * therefore loads cleanly on headless / Wayland-only hosts where the higher
 * layer falls back to wl-clipboard.
 *
 * Design:
 *   - one xcb_connection_t shared between the JNI threads and a dedicated
 *     event thread (XCB guarantees thread-safe access);
 *   - one hidden 1x1 InputOnly window used both as selection owner and
 *     requestor;
 *   - owner-side writes publish a flat list of targets (single chunk, no
 *     INCR — BIG-REQUESTS gives ~16 MB which is plenty in practice);
 *   - requestor-side reads implement the INCR protocol so large payloads
 *     from Chromium/Firefox come through intact;
 *   - XFixes selection notifications bump a monotonic counter which the
 *     Kotlin watcher polls.
 *
 * Threading:
 *   The native side holds one global mutex. Payload and read-request state
 *   are protected by it; pthread condvars signal JNI threads when the event
 *   thread has observed the reply they are waiting for.
 */

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* --------------------------------------------------------------------- */
/* XCB + XFixes types & constants (copied locally to avoid link-time dep) */
/* --------------------------------------------------------------------- */

typedef uint32_t xcb_window_t;
typedef uint32_t xcb_atom_t;
typedef uint32_t xcb_timestamp_t;
typedef uint32_t xcb_colormap_t;
typedef uint32_t xcb_visualid_t;

typedef struct xcb_connection_t xcb_connection_t;

typedef struct {
    unsigned int sequence;
} xcb_void_cookie_t;

typedef struct {
    unsigned int sequence;
} xcb_get_property_cookie_t;

typedef struct {
    unsigned int sequence;
} xcb_intern_atom_cookie_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    uint32_t pad[7];
    uint32_t full_sequence;
} xcb_generic_event_t;

typedef struct {
    uint8_t response_type;
    uint8_t error_code;
    uint16_t sequence;
    uint32_t resource_id;
    uint16_t minor_code;
    uint8_t major_code;
    uint8_t pad0;
    uint32_t pad[5];
    uint32_t full_sequence;
} xcb_generic_error_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    uint32_t length;
    xcb_atom_t type;
    uint8_t format;
    uint8_t pad1[3];
    uint32_t bytes_after;
    uint32_t value_len;
    uint8_t pad2[12];
} xcb_get_property_reply_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    uint32_t length;
    xcb_atom_t atom;
} xcb_intern_atom_reply_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    xcb_timestamp_t time;
    xcb_window_t owner;
    xcb_window_t requestor;
    xcb_atom_t selection;
    xcb_atom_t target;
    xcb_atom_t property;
} xcb_selection_request_event_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    xcb_timestamp_t time;
    xcb_window_t requestor;
    xcb_atom_t selection;
    xcb_atom_t target;
    xcb_atom_t property;
} xcb_selection_notify_event_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    xcb_timestamp_t time;
    xcb_window_t owner;
    xcb_atom_t selection;
} xcb_selection_clear_event_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    xcb_window_t window;
    xcb_atom_t atom;
    xcb_timestamp_t time;
    uint8_t state;
    uint8_t pad1[3];
} xcb_property_notify_event_t;

typedef struct {
    uint8_t response_type;
    uint8_t extension;
    uint16_t sequence;
    uint32_t length;
    uint16_t event_type;
    uint8_t pad0[2];
    xcb_window_t window;
    xcb_atom_t selection;
    xcb_timestamp_t timestamp;
    xcb_timestamp_t selection_timestamp;
    uint8_t pad1[8];
} xcb_xfixes_selection_notify_event_t;

typedef struct {
    unsigned int sequence;
} xcb_query_extension_cookie_t;

typedef struct {
    uint8_t response_type;
    uint8_t pad0;
    uint16_t sequence;
    uint32_t length;
    uint8_t present;
    uint8_t major_opcode;
    uint8_t first_event;
    uint8_t first_error;
} xcb_query_extension_reply_t;

typedef struct {
    uint32_t root;
    uint32_t default_colormap;
    uint32_t white_pixel;
    uint32_t black_pixel;
    uint32_t current_input_masks;
    uint16_t width_in_pixels;
    uint16_t height_in_pixels;
    uint16_t width_in_millimeters;
    uint16_t height_in_millimeters;
    uint16_t min_installed_maps;
    uint16_t max_installed_maps;
    xcb_visualid_t root_visual;
    uint8_t backing_stores;
    uint8_t save_unders;
    uint8_t root_depth;
    uint8_t allowed_depths_len;
} xcb_screen_t;

typedef struct {
    const xcb_screen_t *data;
    int rem;
    int index;
} xcb_screen_iterator_t;

typedef struct xcb_setup_t xcb_setup_t;

#define XCB_COPY_FROM_PARENT 0L
#define XCB_WINDOW_CLASS_INPUT_ONLY 2
#define XCB_CW_EVENT_MASK 2048
#define XCB_EVENT_MASK_PROPERTY_CHANGE 4194304
#define XCB_ATOM_NONE 0
#define XCB_ATOM_ATOM 4
#define XCB_ATOM_INTEGER 19
#define XCB_ATOM_STRING 31
#define XCB_CURRENT_TIME 0L
#define XCB_PROP_MODE_REPLACE 0
#define XCB_PROP_MODE_APPEND 2
#define XCB_SEND_EVENT_DEST_POINTER_WINDOW 0
#define XCB_EVENT_MASK_NO_EVENT 0

#define XCB_PROPERTY_NOTIFY 28
#define XCB_SELECTION_CLEAR 29
#define XCB_SELECTION_REQUEST 30
#define XCB_SELECTION_NOTIFY 31

#define XCB_XFIXES_SET_SELECTION_OWNER_NOTIFY_MASK 1
#define XCB_XFIXES_SELECTION_WINDOW_DESTROY_NOTIFY_MASK 2
#define XCB_XFIXES_SELECTION_CLIENT_CLOSE_NOTIFY_MASK 4

/* --------------------------------------------------------------------- */
/* dlopen'd symbols                                                       */
/* --------------------------------------------------------------------- */

static void *g_xcb_handle = NULL;
static void *g_xfixes_handle = NULL;

static xcb_connection_t *(*p_xcb_connect)(const char *, int *);
static int (*p_xcb_connection_has_error)(xcb_connection_t *);
static int (*p_xcb_disconnect_set)(xcb_connection_t *); /* alias */
static void (*p_xcb_disconnect)(xcb_connection_t *);
static const xcb_setup_t *(*p_xcb_get_setup)(xcb_connection_t *);
static xcb_screen_iterator_t (*p_xcb_setup_roots_iterator)(const xcb_setup_t *);
static uint32_t (*p_xcb_generate_id)(xcb_connection_t *);
static int (*p_xcb_flush)(xcb_connection_t *);
static xcb_generic_event_t *(*p_xcb_wait_for_event)(xcb_connection_t *);
static xcb_generic_event_t *(*p_xcb_poll_for_event)(xcb_connection_t *);
static int (*p_xcb_get_file_descriptor)(xcb_connection_t *);

static xcb_void_cookie_t (*p_xcb_create_window)(
    xcb_connection_t *, uint8_t, xcb_window_t, xcb_window_t,
    int16_t, int16_t, uint16_t, uint16_t, uint16_t, uint16_t,
    xcb_visualid_t, uint32_t, const void *);
static xcb_void_cookie_t (*p_xcb_destroy_window)(xcb_connection_t *, xcb_window_t);

static xcb_intern_atom_cookie_t (*p_xcb_intern_atom)(xcb_connection_t *, uint8_t, uint16_t, const char *);
static xcb_intern_atom_reply_t *(*p_xcb_intern_atom_reply)(
    xcb_connection_t *, xcb_intern_atom_cookie_t, xcb_generic_error_t **);

static xcb_void_cookie_t (*p_xcb_change_property)(
    xcb_connection_t *, uint8_t, xcb_window_t, xcb_atom_t, xcb_atom_t,
    uint8_t, uint32_t, const void *);
static xcb_void_cookie_t (*p_xcb_delete_property)(xcb_connection_t *, xcb_window_t, xcb_atom_t);

static xcb_get_property_cookie_t (*p_xcb_get_property)(
    xcb_connection_t *, uint8_t, xcb_window_t, xcb_atom_t, xcb_atom_t,
    uint32_t, uint32_t);
static xcb_get_property_reply_t *(*p_xcb_get_property_reply)(
    xcb_connection_t *, xcb_get_property_cookie_t, xcb_generic_error_t **);
static void *(*p_xcb_get_property_value)(const xcb_get_property_reply_t *);
static int (*p_xcb_get_property_value_length)(const xcb_get_property_reply_t *);

static xcb_void_cookie_t (*p_xcb_set_selection_owner)(
    xcb_connection_t *, xcb_window_t, xcb_atom_t, xcb_timestamp_t);
static xcb_void_cookie_t (*p_xcb_convert_selection)(
    xcb_connection_t *, xcb_window_t, xcb_atom_t, xcb_atom_t, xcb_atom_t, xcb_timestamp_t);

static xcb_void_cookie_t (*p_xcb_send_event)(
    xcb_connection_t *, uint8_t, xcb_window_t, uint32_t, const char *);

static xcb_query_extension_cookie_t (*p_xcb_query_extension)(
    xcb_connection_t *, uint16_t, const char *);
static xcb_query_extension_reply_t *(*p_xcb_query_extension_reply)(
    xcb_connection_t *, xcb_query_extension_cookie_t, xcb_generic_error_t **);

/* XFixes */
static xcb_void_cookie_t (*p_xcb_xfixes_query_version)(xcb_connection_t *, uint32_t, uint32_t);
static xcb_void_cookie_t (*p_xcb_xfixes_select_selection_input_checked)(
    xcb_connection_t *, xcb_window_t, xcb_atom_t, uint32_t);

/* --------------------------------------------------------------------- */
/* Globals                                                                */
/* --------------------------------------------------------------------- */

#define PROP_NAME "NUCLEUS_CLIPBOARD_PROP"

typedef enum {
    OFFER_TEXT = 0,
    OFFER_HTML,
    OFFER_RTF,
    OFFER_IMAGE_PNG,
    OFFER_URI_LIST,
    OFFER_GNOME_URIS,
    OFFER_KDE_CUT,
    OFFER__COUNT
} offer_kind_t;

static bool             g_inited = false;
static xcb_connection_t *g_conn = NULL;
static xcb_window_t     g_window = 0;
static xcb_screen_t     *g_screen = NULL;

static uint8_t g_xfixes_first_event = 0;

/* Interned atoms. */
static xcb_atom_t a_CLIPBOARD, a_TARGETS, a_MULTIPLE, a_TIMESTAMP, a_SAVE_TARGETS,
    a_INCR, a_ATOM_PAIR, a_UTF8_STRING, a_STRING, a_TEXT, a_COMPOUND_TEXT,
    a_text_plain, a_text_plain_utf8, a_text_html, a_text_rtf, a_application_rtf,
    a_image_png, a_text_uri_list, a_x_special_gnome_copied_files,
    a_application_x_kde_cutselection, a_clipboard_manager, a_prop;

/* Mutex protecting everything below. */
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Owner-side payload. Bytes are owned by us, freed on replacement / clear. */
typedef struct {
    uint8_t *data;
    size_t   len;
    bool     set;
} payload_t;

static payload_t g_offers[OFFER__COUNT];
static bool      g_offer_is_cut = false;
static xcb_timestamp_t g_own_ts = 0;

/* Requestor-side pending read. */
typedef struct {
    bool    waiting;         /* JNI thread is waiting */
    bool    done;             /* event thread marked ready */
    xcb_atom_t target;        /* which target we requested */

    bool    incr;             /* current read is an INCR transfer */
    bool    incr_chunk_ready; /* event thread saw PropertyNotify=NewValue */
    bool    incr_done;        /* zero-length chunk seen */

    uint8_t *buf;
    size_t   len;
    size_t   cap;
} pending_read_t;

static pending_read_t g_read;
static pthread_cond_t g_read_cond = PTHREAD_COND_INITIALIZER;

/* Change counter (XFixes). */
static atomic_long g_change_count = 0;

/* Event thread. */
static pthread_t   g_ev_thread;
static bool        g_ev_running = false;

/* --------------------------------------------------------------------- */
/* dl helpers                                                             */
/* --------------------------------------------------------------------- */

#define LOAD(handle, sym)                                              \
    do {                                                                \
        *(void **)(&p_##sym) = dlsym((handle), #sym);                   \
        if (!p_##sym) {                                                 \
            fprintf(stderr, "nucleus_clipboard_linux: missing "#sym"\n"); \
            return false;                                               \
        }                                                               \
    } while (0)

static bool load_xcb_symbols(void) {
    g_xcb_handle = dlopen("libxcb.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!g_xcb_handle) return false;

    LOAD(g_xcb_handle, xcb_connect);
    LOAD(g_xcb_handle, xcb_connection_has_error);
    LOAD(g_xcb_handle, xcb_disconnect);
    LOAD(g_xcb_handle, xcb_get_setup);
    LOAD(g_xcb_handle, xcb_setup_roots_iterator);
    LOAD(g_xcb_handle, xcb_generate_id);
    LOAD(g_xcb_handle, xcb_flush);
    LOAD(g_xcb_handle, xcb_wait_for_event);
    LOAD(g_xcb_handle, xcb_poll_for_event);
    LOAD(g_xcb_handle, xcb_get_file_descriptor);
    LOAD(g_xcb_handle, xcb_create_window);
    LOAD(g_xcb_handle, xcb_destroy_window);
    LOAD(g_xcb_handle, xcb_intern_atom);
    LOAD(g_xcb_handle, xcb_intern_atom_reply);
    LOAD(g_xcb_handle, xcb_change_property);
    LOAD(g_xcb_handle, xcb_delete_property);
    LOAD(g_xcb_handle, xcb_get_property);
    LOAD(g_xcb_handle, xcb_get_property_reply);
    LOAD(g_xcb_handle, xcb_get_property_value);
    LOAD(g_xcb_handle, xcb_get_property_value_length);
    LOAD(g_xcb_handle, xcb_set_selection_owner);
    LOAD(g_xcb_handle, xcb_convert_selection);
    LOAD(g_xcb_handle, xcb_send_event);
    LOAD(g_xcb_handle, xcb_query_extension);
    LOAD(g_xcb_handle, xcb_query_extension_reply);
    return true;
}

static bool load_xfixes_symbols(void) {
    g_xfixes_handle = dlopen("libxcb-xfixes.so.0", RTLD_NOW | RTLD_GLOBAL);
    if (!g_xfixes_handle) return false;
    LOAD(g_xfixes_handle, xcb_xfixes_query_version);
    LOAD(g_xfixes_handle, xcb_xfixes_select_selection_input_checked);
    return true;
}

/* --------------------------------------------------------------------- */
/* Atom interning                                                         */
/* --------------------------------------------------------------------- */

static xcb_atom_t intern(const char *name, bool only_if_exists) {
    xcb_intern_atom_cookie_t c = p_xcb_intern_atom(g_conn, only_if_exists ? 1 : 0,
                                                    (uint16_t)strlen(name), name);
    xcb_intern_atom_reply_t *r = p_xcb_intern_atom_reply(g_conn, c, NULL);
    if (!r) return XCB_ATOM_NONE;
    xcb_atom_t a = r->atom;
    free(r);
    return a;
}

static void intern_all_atoms(void) {
    a_CLIPBOARD          = intern("CLIPBOARD", false);
    a_TARGETS            = intern("TARGETS", false);
    a_MULTIPLE           = intern("MULTIPLE", false);
    a_TIMESTAMP          = intern("TIMESTAMP", false);
    a_SAVE_TARGETS       = intern("SAVE_TARGETS", false);
    a_INCR               = intern("INCR", false);
    a_ATOM_PAIR          = intern("ATOM_PAIR", false);
    a_UTF8_STRING        = intern("UTF8_STRING", false);
    a_STRING             = intern("STRING", false);
    a_TEXT               = intern("TEXT", false);
    a_COMPOUND_TEXT      = intern("COMPOUND_TEXT", false);
    a_text_plain         = intern("text/plain", false);
    a_text_plain_utf8    = intern("text/plain;charset=utf-8", false);
    a_text_html          = intern("text/html", false);
    a_text_rtf           = intern("text/rtf", false);
    a_application_rtf    = intern("application/rtf", false);
    a_image_png          = intern("image/png", false);
    a_text_uri_list      = intern("text/uri-list", false);
    a_x_special_gnome_copied_files = intern("x-special/gnome-copied-files", false);
    a_application_x_kde_cutselection = intern("application/x-kde-cutselection", false);
    a_clipboard_manager  = intern("CLIPBOARD_MANAGER", false);
    a_prop               = intern(PROP_NAME, false);
}

/* --------------------------------------------------------------------- */
/* Owner-side: serve SelectionRequest                                     */
/* --------------------------------------------------------------------- */

static void free_offers_locked(void) {
    for (int i = 0; i < OFFER__COUNT; i++) {
        free(g_offers[i].data);
        g_offers[i].data = NULL;
        g_offers[i].len = 0;
        g_offers[i].set = false;
    }
    g_offer_is_cut = false;
}

/* Returns the offer index for a given target atom, or -1 if we don't serve it. */
static int target_to_offer(xcb_atom_t target) {
    if (target == a_UTF8_STRING || target == a_text_plain_utf8 ||
        target == a_STRING || target == a_TEXT || target == a_text_plain) return OFFER_TEXT;
    if (target == a_text_html) return OFFER_HTML;
    if (target == a_text_rtf || target == a_application_rtf) return OFFER_RTF;
    if (target == a_image_png) return OFFER_IMAGE_PNG;
    if (target == a_text_uri_list) return OFFER_URI_LIST;
    if (target == a_x_special_gnome_copied_files) return OFFER_GNOME_URIS;
    if (target == a_application_x_kde_cutselection) return OFFER_KDE_CUT;
    return -1;
}

/* Build the TARGETS atom list from currently offered formats. */
static size_t build_targets_list(xcb_atom_t *out, size_t cap) {
    size_t n = 0;
#define ADD(a) do { if (n < cap) out[n++] = (a); } while (0)
    ADD(a_TARGETS);
    ADD(a_TIMESTAMP);
    ADD(a_MULTIPLE);
    ADD(a_SAVE_TARGETS);
    if (g_offers[OFFER_TEXT].set) {
        ADD(a_UTF8_STRING);
        ADD(a_text_plain_utf8);
        ADD(a_STRING);
        ADD(a_TEXT);
        ADD(a_text_plain);
    }
    if (g_offers[OFFER_HTML].set) ADD(a_text_html);
    if (g_offers[OFFER_RTF].set) { ADD(a_text_rtf); ADD(a_application_rtf); }
    if (g_offers[OFFER_IMAGE_PNG].set) ADD(a_image_png);
    if (g_offers[OFFER_URI_LIST].set) ADD(a_text_uri_list);
    if (g_offers[OFFER_GNOME_URIS].set) ADD(a_x_special_gnome_copied_files);
    if (g_offers[OFFER_KDE_CUT].set) ADD(a_application_x_kde_cutselection);
#undef ADD
    return n;
}

static void send_selection_notify(const xcb_selection_request_event_t *req, xcb_atom_t property) {
    xcb_selection_notify_event_t ev;
    memset(&ev, 0, sizeof(ev));
    ev.response_type = XCB_SELECTION_NOTIFY;
    ev.time = req->time;
    ev.requestor = req->requestor;
    ev.selection = req->selection;
    ev.target = req->target;
    ev.property = property;
    p_xcb_send_event(g_conn, 0, req->requestor, XCB_EVENT_MASK_NO_EVENT, (const char *)&ev);
    p_xcb_flush(g_conn);
}

static void write_property(xcb_window_t win, xcb_atom_t prop, xcb_atom_t type,
                           uint8_t format, size_t count, const void *data) {
    p_xcb_change_property(g_conn, XCB_PROP_MODE_REPLACE, win, prop, type,
                          format, (uint32_t)count, data);
}

static void handle_selection_request_locked(const xcb_selection_request_event_t *req) {
    xcb_atom_t prop = req->property != XCB_ATOM_NONE ? req->property : req->target;

    if (req->target == a_TARGETS) {
        xcb_atom_t buf[24];
        size_t n = build_targets_list(buf, 24);
        write_property(req->requestor, prop, XCB_ATOM_ATOM, 32, n, buf);
        xcb_selection_notify_event_t ev = {0};
        ev.response_type = XCB_SELECTION_NOTIFY;
        ev.time = req->time; ev.requestor = req->requestor;
        ev.selection = req->selection; ev.target = req->target; ev.property = prop;
        p_xcb_send_event(g_conn, 0, req->requestor, 0, (const char *)&ev);
        p_xcb_flush(g_conn);
        return;
    }

    if (req->target == a_TIMESTAMP) {
        uint32_t ts = (uint32_t)g_own_ts;
        write_property(req->requestor, prop, XCB_ATOM_INTEGER, 32, 1, &ts);
        send_selection_notify(req, prop);
        return;
    }

    if (req->target == a_SAVE_TARGETS) {
        /* Acknowledge but do nothing — we have already published everything. */
        send_selection_notify(req, prop);
        return;
    }

    int idx = target_to_offer(req->target);
    if (idx < 0 || !g_offers[idx].set) {
        /* Refuse: property=None. */
        send_selection_notify(req, XCB_ATOM_NONE);
        return;
    }

    const payload_t *pl = &g_offers[idx];
    /* Single-chunk write. xcb_change_property's length is in units of format/8;
     * BIG-REQUESTS lifts the request size to ~16 MB which is sufficient for
     * V1. Larger payloads would require owner-side INCR which is not yet
     * implemented. */
    write_property(req->requestor, prop, req->target, 8, pl->len, pl->data);
    send_selection_notify(req, prop);
}

/* --------------------------------------------------------------------- */
/* Requestor-side: reads                                                  */
/* --------------------------------------------------------------------- */

static void read_reset_locked(void) {
    free(g_read.buf);
    memset(&g_read, 0, sizeof(g_read));
}

static bool read_append(const void *data, size_t n) {
    if (g_read.len + n > g_read.cap) {
        size_t nc = g_read.cap ? g_read.cap * 2 : 4096;
        while (nc < g_read.len + n) nc *= 2;
        uint8_t *nb = realloc(g_read.buf, nc);
        if (!nb) return false;
        g_read.buf = nb;
        g_read.cap = nc;
    }
    memcpy(g_read.buf + g_read.len, data, n);
    g_read.len += n;
    return true;
}

/* Fetch and consume the window property, returning malloc'd bytes (or NULL).
 * On INCR start, sets *out_incr = true and returns NULL. */
static uint8_t *fetch_property(xcb_atom_t prop, bool *out_incr, size_t *out_len) {
    *out_incr = false;
    *out_len = 0;
    xcb_get_property_cookie_t c =
        p_xcb_get_property(g_conn, 0, g_window, prop, 0 /* AnyType */, 0, 0x1FFFFFFF);
    xcb_get_property_reply_t *r = p_xcb_get_property_reply(g_conn, c, NULL);
    if (!r) return NULL;

    if (r->type == a_INCR) {
        *out_incr = true;
        free(r);
        p_xcb_delete_property(g_conn, g_window, prop);
        p_xcb_flush(g_conn);
        return NULL;
    }

    size_t n = (size_t)p_xcb_get_property_value_length(r);
    uint8_t *out = NULL;
    if (n > 0) {
        out = malloc(n);
        if (out) memcpy(out, p_xcb_get_property_value(r), n);
    }
    *out_len = n;
    free(r);
    p_xcb_delete_property(g_conn, g_window, prop);
    p_xcb_flush(g_conn);
    return out;
}

/* Deadline helper. */
static void add_ms(struct timespec *ts, long ms) {
    ts->tv_sec += ms / 1000;
    ts->tv_nsec += (ms % 1000) * 1000000L;
    if (ts->tv_nsec >= 1000000000L) { ts->tv_sec++; ts->tv_nsec -= 1000000000L; }
}

/* Perform a full read for the given target (atom). Returns malloc'd bytes or NULL. */
static uint8_t *read_selection(xcb_atom_t target, size_t *out_len) {
    *out_len = 0;
    pthread_mutex_lock(&g_mutex);

    if (g_read.waiting) {
        /* Another reader is active; serialise. */
        pthread_mutex_unlock(&g_mutex);
        return NULL;
    }

    read_reset_locked();
    g_read.waiting = true;
    g_read.target = target;

    p_xcb_delete_property(g_conn, g_window, a_prop);
    p_xcb_convert_selection(g_conn, g_window, a_CLIPBOARD, target, a_prop, XCB_CURRENT_TIME);
    p_xcb_flush(g_conn);

    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    add_ms(&deadline, 2000);

    while (!g_read.done) {
        int rc = pthread_cond_timedwait(&g_read_cond, &g_mutex, &deadline);
        if (rc == ETIMEDOUT) {
            g_read.waiting = false;
            read_reset_locked();
            pthread_mutex_unlock(&g_mutex);
            return NULL;
        }
    }

    if (g_read.incr) {
        /* Collect chunks until we see a zero-length terminator. */
        struct timespec incr_deadline;
        clock_gettime(CLOCK_REALTIME, &incr_deadline);
        add_ms(&incr_deadline, 10000);

        while (!g_read.incr_done) {
            int rc = pthread_cond_timedwait(&g_read_cond, &g_mutex, &incr_deadline);
            if (rc == ETIMEDOUT) {
                g_read.waiting = false;
                read_reset_locked();
                pthread_mutex_unlock(&g_mutex);
                return NULL;
            }
        }
    }

    uint8_t *out = g_read.buf;
    size_t len = g_read.len;
    g_read.buf = NULL;
    g_read.len = 0;
    g_read.cap = 0;
    g_read.waiting = false;
    g_read.done = false;
    g_read.incr = false;
    g_read.incr_chunk_ready = false;
    g_read.incr_done = false;

    pthread_mutex_unlock(&g_mutex);
    *out_len = len;
    return out;
}

/* --------------------------------------------------------------------- */
/* Event thread                                                           */
/* --------------------------------------------------------------------- */

static void on_selection_notify_locked(const xcb_selection_notify_event_t *ev) {
    if (!g_read.waiting || g_read.done) return;
    if (ev->property == XCB_ATOM_NONE) {
        /* Selection refused: return empty. */
        g_read.done = true;
        pthread_cond_broadcast(&g_read_cond);
        return;
    }
    if (ev->target != g_read.target) return;

    bool is_incr = false; size_t n = 0;
    uint8_t *bytes = fetch_property(ev->property, &is_incr, &n);
    if (is_incr) {
        g_read.incr = true;
        g_read.done = true;
        pthread_cond_broadcast(&g_read_cond);
        return;
    }
    if (bytes && n > 0) {
        read_append(bytes, n);
    }
    free(bytes);
    g_read.done = true;
    pthread_cond_broadcast(&g_read_cond);
}

static void on_property_notify_locked(const xcb_property_notify_event_t *ev) {
    /* state 0 = NewValue, 1 = Delete. We only care about NewValue on our
     * own property during INCR. */
    if (!g_read.waiting || !g_read.incr || g_read.incr_done) return;
    if (ev->window != g_window || ev->atom != a_prop || ev->state != 0) return;

    bool is_incr = false; size_t n = 0;
    uint8_t *bytes = fetch_property(a_prop, &is_incr, &n);
    if (n == 0) {
        g_read.incr_done = true;
        pthread_cond_broadcast(&g_read_cond);
    } else if (bytes) {
        read_append(bytes, n);
    }
    free(bytes);
}

static void on_selection_request(const xcb_selection_request_event_t *ev) {
    pthread_mutex_lock(&g_mutex);
    handle_selection_request_locked(ev);
    pthread_mutex_unlock(&g_mutex);
}

static void on_selection_clear(const xcb_selection_clear_event_t *ev) {
    (void)ev;
    pthread_mutex_lock(&g_mutex);
    free_offers_locked();
    pthread_mutex_unlock(&g_mutex);
}

static void *event_thread_main(void *arg) {
    (void)arg;
    while (g_ev_running) {
        xcb_generic_event_t *ev = p_xcb_wait_for_event(g_conn);
        if (!ev) break;
        uint8_t rt = ev->response_type & ~0x80;

        if (rt == g_xfixes_first_event) {
            /* Any XFixes event we registered for on CLIPBOARD = change. */
            atomic_fetch_add(&g_change_count, 1);
        } else {
            switch (rt) {
                case XCB_SELECTION_REQUEST:
                    on_selection_request((xcb_selection_request_event_t *)ev);
                    break;
                case XCB_SELECTION_CLEAR:
                    on_selection_clear((xcb_selection_clear_event_t *)ev);
                    break;
                case XCB_SELECTION_NOTIFY:
                    pthread_mutex_lock(&g_mutex);
                    on_selection_notify_locked((xcb_selection_notify_event_t *)ev);
                    pthread_mutex_unlock(&g_mutex);
                    break;
                case XCB_PROPERTY_NOTIFY:
                    pthread_mutex_lock(&g_mutex);
                    on_property_notify_locked((xcb_property_notify_event_t *)ev);
                    pthread_mutex_unlock(&g_mutex);
                    break;
                default:
                    break;
            }
        }
        free(ev);
    }
    return NULL;
}

/* --------------------------------------------------------------------- */
/* Init / teardown                                                        */
/* --------------------------------------------------------------------- */

static bool setup_xfixes(void) {
    xcb_query_extension_cookie_t c = p_xcb_query_extension(g_conn, 6, "XFIXES");
    xcb_query_extension_reply_t *r = p_xcb_query_extension_reply(g_conn, c, NULL);
    if (!r) return false;
    bool present = r->present != 0;
    g_xfixes_first_event = r->first_event;
    free(r);
    if (!present) return false;

    /* Negotiate at least version 1.0. */
    p_xcb_xfixes_query_version(g_conn, 5, 0);

    uint32_t mask =
        XCB_XFIXES_SET_SELECTION_OWNER_NOTIFY_MASK |
        XCB_XFIXES_SELECTION_WINDOW_DESTROY_NOTIFY_MASK |
        XCB_XFIXES_SELECTION_CLIENT_CLOSE_NOTIFY_MASK;
    p_xcb_xfixes_select_selection_input_checked(g_conn, g_window, a_CLIPBOARD, mask);
    p_xcb_flush(g_conn);
    return true;
}

static bool do_init(void) {
    if (g_inited) return true;
    if (getenv("DISPLAY") == NULL) return false;
    if (!load_xcb_symbols()) return false;

    int screen_num = 0;
    g_conn = p_xcb_connect(NULL, &screen_num);
    if (!g_conn || p_xcb_connection_has_error(g_conn)) {
        if (g_conn) p_xcb_disconnect(g_conn);
        g_conn = NULL;
        return false;
    }

    const xcb_setup_t *setup = p_xcb_get_setup(g_conn);
    xcb_screen_iterator_t it = p_xcb_setup_roots_iterator(setup);
    g_screen = (xcb_screen_t *)it.data;
    if (!g_screen) return false;

    /* Create hidden 1x1 InputOnly window with PropertyChangeMask. */
    g_window = p_xcb_generate_id(g_conn);
    uint32_t values[1] = { XCB_EVENT_MASK_PROPERTY_CHANGE };
    p_xcb_create_window(g_conn,
                        XCB_COPY_FROM_PARENT,
                        g_window,
                        g_screen->root,
                        0, 0, 1, 1, 0,
                        XCB_WINDOW_CLASS_INPUT_ONLY,
                        XCB_COPY_FROM_PARENT,
                        XCB_CW_EVENT_MASK,
                        values);

    intern_all_atoms();

    if (!load_xfixes_symbols()) {
        /* Watcher disabled, but the rest still works. */
        g_xfixes_first_event = 0xFF;
    } else {
        setup_xfixes();
    }

    g_ev_running = true;
    if (pthread_create(&g_ev_thread, NULL, event_thread_main, NULL) != 0) {
        g_ev_running = false;
        return false;
    }

    g_inited = true;
    return true;
}

/* --------------------------------------------------------------------- */
/* Writes                                                                 */
/* --------------------------------------------------------------------- */

static void set_offer_locked(offer_kind_t k, const void *bytes, size_t len) {
    free(g_offers[k].data);
    g_offers[k].data = malloc(len);
    if (g_offers[k].data == NULL) {
        g_offers[k].set = false;
        g_offers[k].len = 0;
        return;
    }
    memcpy(g_offers[k].data, bytes, len);
    g_offers[k].len = len;
    g_offers[k].set = true;
}

/* JNI side provides UTF-8 encoded strings already; we just copy. */
static void offer_string(offer_kind_t k, const char *utf8) {
    if (!utf8) return;
    set_offer_locked(k, utf8, strlen(utf8));
}

/* --------------------------------------------------------------------- */
/* JNI                                                                    */
/* --------------------------------------------------------------------- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}

static const char *jstr_to_utf8(JNIEnv *env, jstring s, const char **release_ref) {
    *release_ref = NULL;
    if (!s) return NULL;
    const char *p = (*env)->GetStringUTFChars(env, s, NULL);
    *release_ref = p;
    return p;
}

static void jstr_release(JNIEnv *env, jstring s, const char *p) {
    if (s && p) (*env)->ReleaseStringUTFChars(env, s, p);
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeInit(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return do_init() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeChangeCount(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)atomic_load(&g_change_count);
}

/* Return available TARGETS on the current CLIPBOARD owner as a Java String[]. */
JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeAvailableTargets(
    JNIEnv *env, jclass cls) {
    (void)cls;
    if (!g_inited) {
        jclass sc = (*env)->FindClass(env, "java/lang/String");
        return (*env)->NewObjectArray(env, 0, sc, NULL);
    }

    size_t n = 0;
    uint8_t *bytes = read_selection(a_TARGETS, &n);
    size_t count = bytes ? (n / 4) : 0;

    jclass sc = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, (jsize)count, sc, NULL);

    for (size_t i = 0; i < count; i++) {
        xcb_atom_t a;
        memcpy(&a, bytes + i * 4, 4);
        /* Resolve atom -> name with get_atom_name via raw XCB. */
        /* Use xcb_get_atom_name for human names; to keep the surface small
         * we match against our known set only. */
        const char *name = NULL;
        if (a == a_UTF8_STRING) name = "UTF8_STRING";
        else if (a == a_STRING) name = "STRING";
        else if (a == a_TEXT) name = "TEXT";
        else if (a == a_COMPOUND_TEXT) name = "COMPOUND_TEXT";
        else if (a == a_text_plain) name = "text/plain";
        else if (a == a_text_plain_utf8) name = "text/plain;charset=utf-8";
        else if (a == a_text_html) name = "text/html";
        else if (a == a_text_rtf) name = "text/rtf";
        else if (a == a_application_rtf) name = "application/rtf";
        else if (a == a_image_png) name = "image/png";
        else if (a == a_text_uri_list) name = "text/uri-list";
        else if (a == a_x_special_gnome_copied_files) name = "x-special/gnome-copied-files";
        else if (a == a_application_x_kde_cutselection) name = "application/x-kde-cutselection";
        else name = NULL;

        if (name) {
            jstring js = (*env)->NewStringUTF(env, name);
            (*env)->SetObjectArrayElement(env, out, (jsize)i, js);
            (*env)->DeleteLocalRef(env, js);
        }
    }
    free(bytes);
    return out;
}

/* Reads a text target. Falls back through UTF8_STRING → text/plain;charset=utf-8. */
static jstring read_text_target(JNIEnv *env, xcb_atom_t primary, xcb_atom_t fallback) {
    size_t n = 0;
    uint8_t *bytes = read_selection(primary, &n);
    if ((!bytes || n == 0) && fallback != XCB_ATOM_NONE) {
        free(bytes);
        bytes = read_selection(fallback, &n);
    }
    if (!bytes || n == 0) { free(bytes); return NULL; }

    /* Strip trailing NULs and NUL-terminate for NewStringUTF. */
    while (n > 0 && bytes[n - 1] == '\0') n--;
    char *z = malloc(n + 1);
    if (!z) { free(bytes); return NULL; }
    memcpy(z, bytes, n);
    z[n] = '\0';
    free(bytes);
    jstring out = (*env)->NewStringUTF(env, z);
    free(z);
    return out;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeReadText(
    JNIEnv *env, jclass cls) {
    (void)cls;
    if (!g_inited) return NULL;
    return read_text_target(env, a_UTF8_STRING, a_text_plain_utf8);
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeReadHtml(
    JNIEnv *env, jclass cls) {
    (void)cls;
    if (!g_inited) return NULL;
    size_t n = 0;
    uint8_t *bytes = read_selection(a_text_html, &n);
    if (!bytes || n == 0) { free(bytes); return NULL; }

    /* Firefox emits UTF-16 BE BOM; Chromium may emit UTF-8 BOM. The higher
     * Kotlin layer strips UTF-8 BOMs. For the UTF-16 case we transcode here
     * since NewStringUTF expects modified UTF-8. */
    if (n >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF) {
        size_t body = n - 2;
        size_t chars = body / 2;
        char *utf8 = malloc(chars * 3 + 1);
        if (!utf8) { free(bytes); return NULL; }
        size_t o = 0;
        for (size_t i = 0; i < chars; i++) {
            uint16_t cu = ((uint16_t)bytes[2 + i * 2] << 8) | bytes[3 + i * 2];
            if (cu < 0x80) {
                utf8[o++] = (char)cu;
            } else if (cu < 0x800) {
                utf8[o++] = (char)(0xC0 | (cu >> 6));
                utf8[o++] = (char)(0x80 | (cu & 0x3F));
            } else {
                utf8[o++] = (char)(0xE0 | (cu >> 12));
                utf8[o++] = (char)(0x80 | ((cu >> 6) & 0x3F));
                utf8[o++] = (char)(0x80 | (cu & 0x3F));
            }
        }
        utf8[o] = 0;
        free(bytes);
        jstring js = (*env)->NewStringUTF(env, utf8);
        free(utf8);
        return js;
    }

    while (n > 0 && bytes[n - 1] == '\0') n--;
    char *z = malloc(n + 1);
    if (!z) { free(bytes); return NULL; }
    memcpy(z, bytes, n);
    z[n] = '\0';
    free(bytes);
    jstring js = (*env)->NewStringUTF(env, z);
    free(z);
    return js;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeReadRtf(
    JNIEnv *env, jclass cls) {
    (void)cls;
    if (!g_inited) return NULL;
    return read_text_target(env, a_text_rtf, a_application_rtf);
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeReadImagePng(
    JNIEnv *env, jclass cls) {
    (void)cls;
    if (!g_inited) return NULL;
    size_t n = 0;
    uint8_t *bytes = read_selection(a_image_png, &n);
    if (!bytes || n == 0) { free(bytes); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
    if (!out) { free(bytes); return NULL; }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)bytes);
    free(bytes);
    return out;
}

/* URL-decodes a file:// URI into an absolute POSIX path. */
static void append_uri_as_path(const char *uri, size_t len, char ***out, size_t *on, size_t *ocap) {
    if (len > 7 && strncmp(uri, "file://", 7) == 0) {
        uri += 7; len -= 7;
    }
    /* Skip host up to first '/'. If missing, treat entire string as path. */
    char *path = malloc(len + 1);
    if (!path) return;
    size_t j = 0;
    for (size_t i = 0; i < len; i++) {
        char c = uri[i];
        if (c == '%' && i + 2 < len) {
            char h[3] = { uri[i + 1], uri[i + 2], 0 };
            path[j++] = (char)strtol(h, NULL, 16);
            i += 2;
        } else {
            path[j++] = c;
        }
    }
    path[j] = 0;

    if (*on >= *ocap) {
        size_t nc = *ocap ? *ocap * 2 : 8;
        char **nb = realloc(*out, nc * sizeof(char *));
        if (!nb) { free(path); return; }
        *out = nb; *ocap = nc;
    }
    (*out)[(*on)++] = path;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeReadFilePaths(
    JNIEnv *env, jclass cls) {
    (void)cls;
    jclass sc = (*env)->FindClass(env, "java/lang/String");
    if (!g_inited) return (*env)->NewObjectArray(env, 0, sc, NULL);

    /* Try GNOME side-channel first for copy/cut awareness. */
    size_t n = 0;
    uint8_t *bytes = read_selection(a_x_special_gnome_copied_files, &n);
    bool is_cut = false;
    char **paths = NULL; size_t pn = 0, pc = 0;

    if (bytes && n > 0) {
        size_t start = 0;
        size_t line_end = 0;
        for (size_t i = 0; i <= n; i++) {
            if (i == n || bytes[i] == '\n' || bytes[i] == '\r') {
                line_end = i;
                size_t ll = line_end - start;
                if (ll > 0) {
                    if (pn == 0 && ll == 3 && memcmp(bytes + start, "cut", 3) == 0) {
                        is_cut = true;
                    } else if (pn == 0 && ll == 4 && memcmp(bytes + start, "copy", 4) == 0) {
                        is_cut = false;
                    } else {
                        append_uri_as_path((const char *)(bytes + start), ll, &paths, &pn, &pc);
                    }
                }
                start = i + 1;
            }
        }
    } else {
        free(bytes); bytes = NULL;
        bytes = read_selection(a_text_uri_list, &n);
        if (bytes && n > 0) {
            size_t start = 0;
            for (size_t i = 0; i <= n; i++) {
                if (i == n || bytes[i] == '\n' || bytes[i] == '\r') {
                    size_t ll = i - start;
                    if (ll > 0 && bytes[start] != '#') {
                        append_uri_as_path((const char *)(bytes + start), ll, &paths, &pn, &pc);
                    }
                    start = i + 1;
                }
            }
        }
    }
    free(bytes);

    /* Pack into a String[] with the copy/cut marker at index 0. */
    jobjectArray out = (*env)->NewObjectArray(env, (jsize)(pn + 1), sc, NULL);
    jstring marker = (*env)->NewStringUTF(env, is_cut ? "m" : "c");
    (*env)->SetObjectArrayElement(env, out, 0, marker);
    (*env)->DeleteLocalRef(env, marker);
    for (size_t i = 0; i < pn; i++) {
        jstring js = (*env)->NewStringUTF(env, paths[i]);
        (*env)->SetObjectArrayElement(env, out, (jsize)(i + 1), js);
        (*env)->DeleteLocalRef(env, js);
        free(paths[i]);
    }
    free(paths);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeWrite(
    JNIEnv *env, jclass cls,
    jstring jText, jstring jHtml, jstring jRtf,
    jbyteArray jPng, jobjectArray jPaths, jboolean isCut) {
    (void)cls;
    if (!g_inited) return JNI_FALSE;

    pthread_mutex_lock(&g_mutex);
    free_offers_locked();

    const char *rel = NULL;
    if (jText) {
        const char *p = jstr_to_utf8(env, jText, &rel);
        if (p) offer_string(OFFER_TEXT, p);
        jstr_release(env, jText, rel);
    }
    if (jHtml) {
        const char *p = jstr_to_utf8(env, jHtml, &rel);
        if (p) offer_string(OFFER_HTML, p);
        jstr_release(env, jHtml, rel);
    }
    if (jRtf) {
        const char *p = jstr_to_utf8(env, jRtf, &rel);
        if (p) offer_string(OFFER_RTF, p);
        jstr_release(env, jRtf, rel);
    }
    if (jPng) {
        jsize len = (*env)->GetArrayLength(env, jPng);
        jbyte *b = (*env)->GetByteArrayElements(env, jPng, NULL);
        if (b) {
            set_offer_locked(OFFER_IMAGE_PNG, b, (size_t)len);
            (*env)->ReleaseByteArrayElements(env, jPng, b, JNI_ABORT);
        }
    }
    if (jPaths) {
        jsize pc = (*env)->GetArrayLength(env, jPaths);
        if (pc > 0) {
            /* Build text/uri-list (CRLF). */
            size_t total = 0;
            for (jsize i = 0; i < pc; i++) {
                jstring s = (jstring)(*env)->GetObjectArrayElement(env, jPaths, i);
                if (!s) continue;
                const char *p = (*env)->GetStringUTFChars(env, s, NULL);
                if (p) total += 7 + strlen(p) + 2;
                if (p) (*env)->ReleaseStringUTFChars(env, s, p);
                (*env)->DeleteLocalRef(env, s);
            }
            char *uri = malloc(total + 1);
            char *gnome = malloc(total + 8);
            if (uri && gnome) {
                size_t uo = 0, go = 0;
                /* GNOME side-channel header. */
                if (isCut) { memcpy(gnome + go, "cut\n", 4); go += 4; }
                else       { memcpy(gnome + go, "copy\n", 5); go += 5; }
                for (jsize i = 0; i < pc; i++) {
                    jstring s = (jstring)(*env)->GetObjectArrayElement(env, jPaths, i);
                    if (!s) continue;
                    const char *p = (*env)->GetStringUTFChars(env, s, NULL);
                    if (p) {
                        size_t pl = strlen(p);
                        memcpy(uri + uo, "file://", 7); uo += 7;
                        memcpy(uri + uo, p, pl); uo += pl;
                        memcpy(uri + uo, "\r\n", 2); uo += 2;

                        memcpy(gnome + go, "file://", 7); go += 7;
                        memcpy(gnome + go, p, pl); go += pl;
                        if (i < pc - 1) { gnome[go++] = '\n'; }
                        (*env)->ReleaseStringUTFChars(env, s, p);
                    }
                    (*env)->DeleteLocalRef(env, s);
                }
                set_offer_locked(OFFER_URI_LIST, uri, uo);
                set_offer_locked(OFFER_GNOME_URIS, gnome, go);
                /* KDE cut marker. */
                set_offer_locked(OFFER_KDE_CUT, isCut ? "1" : "0", 1);
            }
            free(uri); free(gnome);
        }
    }

    g_offer_is_cut = (isCut == JNI_TRUE);

    /* Use CurrentTime; xcb_set_selection_owner is routed via server and will
     * be our implicit timestamp. Record it for TIMESTAMP replies. */
    g_own_ts = XCB_CURRENT_TIME;
    p_xcb_set_selection_owner(g_conn, g_window, a_CLIPBOARD, XCB_CURRENT_TIME);
    p_xcb_flush(g_conn);

    pthread_mutex_unlock(&g_mutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_linux_NativeX11ClipboardBridge_nativeClear(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (!g_inited) return JNI_FALSE;

    pthread_mutex_lock(&g_mutex);

    /* Best-effort SAVE_TARGETS handoff to the clipboard manager. */
    /* Skipped for V1 — GNOME no longer ships gsd-clipboard, so the handoff
     * often dies silently anyway. */

    free_offers_locked();
    p_xcb_set_selection_owner(g_conn, XCB_ATOM_NONE, a_CLIPBOARD, XCB_CURRENT_TIME);
    p_xcb_flush(g_conn);

    pthread_mutex_unlock(&g_mutex);
    return JNI_TRUE;
}
