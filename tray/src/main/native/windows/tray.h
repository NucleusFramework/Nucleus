/* tray.h
 * Public API – remains C99/C++98 compatible
 */
#ifndef TRAY_H
#define TRAY_H

#ifdef __cplusplus
extern "C" {
#endif

/* -------------------------------------------------------------------------- */
/*  Export                                                                    */
/* -------------------------------------------------------------------------- */
#ifdef _WIN32
#  ifdef TRAY_EXPORTS
#    define TRAY_EXPORT __declspec(dllexport)
#  else
#    define TRAY_EXPORT __declspec(dllimport)
#  endif
#else
#  if __GNUC__ >= 4 || defined(__clang__)
#    define TRAY_EXPORT extern __attribute__((visibility("default")))
#  else
#    define TRAY_EXPORT extern
#  endif
#endif

/* -------------------------------------------------------------------------- */
/*  Structures                                                                */
/* -------------------------------------------------------------------------- */
struct tray_menu_item;

struct tray {
    const char              *icon_filepath;      /* Path to the .ico icon       */
    const char              *tooltip;            /* Tooltip text                */
    void   (*cb)(struct tray *);                 /* Left-click callback (NULL → menu) */
    struct tray_menu_item   *menu;               /* Root menu                   */
};

struct tray_menu_item {
    char *text;
    char *icon_path;     // Path to icon file (PNG, ICO, etc.)
    int disabled;
    int checked;
    void (*cb)(struct tray_menu_item *);
    struct tray_menu_item *submenu;
    const char *key_equivalent;          // Reserved for future use (macOS only for now)
    unsigned long key_equivalent_mod_mask; // Reserved for future use (macOS only for now)
};

/* -------------------------------------------------------------------------- */
/*  API                                                                       */
/* -------------------------------------------------------------------------- */
TRAY_EXPORT struct tray *tray_get_instance(void);

TRAY_EXPORT int  tray_init (struct tray *tray);
TRAY_EXPORT int  tray_loop (int blocking);       /* 0 = still running, -1 = finished */
TRAY_EXPORT void tray_update(struct tray *tray); /* Refresh menu/info           */
TRAY_EXPORT void tray_exit (void);               /* Free all resources          */

/* Notification area information */
TRAY_EXPORT int tray_get_notification_icons_position(int *x, int *y);
TRAY_EXPORT const char *tray_get_notification_icons_region(void);

/* Menu-opened callback: invoked just before the popup menu is shown */
TRAY_EXPORT void tray_set_menu_opened_callback(struct tray *tray, void (*cb)(struct tray *));

#ifdef __cplusplus
} /* extern "C" */
#endif
#endif /* TRAY_H */ 