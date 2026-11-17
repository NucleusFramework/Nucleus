/* tray.h - Public API, C99 / C++98 compatible */
#ifndef TRAY_H
#define TRAY_H

#ifdef __cplusplus
extern "C" {
#endif

/* -------------------------------------------------------------------------- */
/*  Symbol export                                                             */
/* -------------------------------------------------------------------------- */
#if defined(_WIN32) && !defined(TRAY_STATIC)
#  ifdef TRAY_EXPORTS
#    define TRAY_API __declspec(dllexport)
#  else
#    define TRAY_API __declspec(dllimport)
#  endif
#elif defined(__GNUC__) || defined(__clang__)
#  define TRAY_API __attribute__((visibility("default")))
#else
#  define TRAY_API
#endif

/* -------------------------------------------------------------------------- */
/*  Callback types                                                            */
/* -------------------------------------------------------------------------- */
struct tray;
struct tray_menu_item;

typedef void (*tray_menu_item_callback)(struct tray_menu_item *item);
typedef void (*tray_callback)          (struct tray           *tray);
typedef void (*theme_callback)         (int is_dark /* 1 = dark, 0 = light */);

/* -------------------------------------------------------------------------- */
/*  Structures                                                                */
/* -------------------------------------------------------------------------- */
struct tray_menu_item {
    const char              *text;         /* label or "-" for separator      */
    const char              *icon_filepath; /* path to icon file (optional)    */
    int                      disabled;     /* 1 = grayed out                  */
    int                      checked;      /* 1 = checked                     */
    tray_menu_item_callback  cb;          /* callback (NULL if none)         */
    struct tray_menu_item   *submenu;     /* submenu or NULL                 */
    const char              *key_equivalent;        /* macOS key equivalent (e.g. "q") or NULL */
    unsigned long            key_equivalent_mod_mask; /* macOS NSEventModifierFlags bitmask     */
};

struct tray {
    const char              *icon_filepath; /* path to icon file             */
    const char              *tooltip;       /* tooltip                       */
    struct tray_menu_item   *menu;          /* root menu (NULL = none)       */
    tray_callback            cb;            /* left click (NULL = menu)      */
};

/* -------------------------------------------------------------------------- */
/*  Lifecycle                                                                 */
/* -------------------------------------------------------------------------- */
TRAY_API struct tray *tray_get_instance(void);

TRAY_API int  tray_init (struct tray *tray);   /* 0 = OK, <0 = error         */
TRAY_API int  tray_loop (int blocking);        /* 0 = running, -1 = finished */
TRAY_API void tray_update(struct tray *tray);  /* refresh icon / menu        */
TRAY_API void tray_dispose(struct tray *tray); /* dispose a single instance  */
TRAY_API void tray_exit (void);                /* free everything and exit   */

/* -------------------------------------------------------------------------- */
/*  Additional options / information                                          */
/* -------------------------------------------------------------------------- */
TRAY_API void tray_set_theme_callback(theme_callback cb);
TRAY_API void tray_set_menu_opened_callback(struct tray *tray, tray_callback cb);
TRAY_API int  tray_is_menu_dark(void);         /* 1 = dark mode              */

/* Windows: corner and coordinates of notification area                       */
TRAY_API int         tray_get_notification_icons_position(int *x, int *y);
TRAY_API const char *tray_get_notification_icons_region(void);

/* macOS: corner and coordinates of status item                               */
TRAY_API int         tray_get_status_item_position(int *x, int *y);
TRAY_API const char *tray_get_status_item_region(void);

/* macOS: per-instance variants */
TRAY_API int         tray_get_status_item_position_for(struct tray *tray, int *x, int *y);
TRAY_API const char *tray_get_status_item_region_for(struct tray *tray);

/* macOS: pre-rendered appearance icons for instant light/dark switching       */
TRAY_API void tray_set_icons_for_appearance(struct tray *tray, const char *light_icon, const char *dark_icon);

/* macOS: space behavior for all windows                                       */
TRAY_API void tray_set_windows_move_to_active_space(void);

/* macOS: dock visibility                                                      */
TRAY_API int tray_show_in_dock(void);
TRAY_API int tray_hide_from_dock(void);

/* macOS: floating window / Space management                                   */
TRAY_API int tray_is_floating_window_on_active_space(void);
TRAY_API int tray_bring_floating_window_to_front(void);
TRAY_API int tray_set_move_to_active_space_for_view(void *nsViewPtr);
TRAY_API int tray_is_on_active_space_for_view(void *nsViewPtr);

/* macOS: mouse button state                                                   */
TRAY_API int tray_get_mouse_button_state(int button);

#ifdef __cplusplus
} /* extern "C" */
#endif
#endif /* TRAY_H */