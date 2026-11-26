/* tray.h - Windows notification-area icon (Shell_NotifyIcon) used by nucleus_tray.dll.
 *
 * Threading: every function except tray_wake() and tray_get_identity() must be called on the
 * tray's own thread, the one that called tray_init(). That thread owns the hidden window, the
 * menu, the icon and pumps the messages; nothing is shared between trays, so there is no lock.
 */
#ifndef NUCLEUS_TRAY_H
#define NUCLEUS_TRAY_H

#ifdef __cplusplus
extern "C" {
#endif

struct tray_menu_item {
    char *text;                     /* UTF-8, shown literally ('&' is escaped) */
    char *icon_path;                /* optional .ico for the item bitmap */
    int disabled;
    int checked;
    int separator;                  /* a divider; text is ignored */
    void (*cb)(struct tray_menu_item *);
    struct tray_menu_item *submenu; /* array terminated by an item with text == NULL && !separator */
    void *user;                     /* bridge data (JNI global ref of the click callback) */
    int terminator;                 /* 1 on the sentinel ending an item array */
};

struct tray {
    char *icon_filepath;            /* .ico */
    char *tooltip;                  /* UTF-8; empty clears it */
    void (*cb)(struct tray *);      /* left click; NULL shows the menu instead */
    void (*menu_opened_cb)(struct tray *);
    struct tray_menu_item *menu;
    void *user_click;               /* bridge data */
    void *user_menu_opened;         /* bridge data */
    void *impl;                     /* TrayContext, owned by the tray thread */
    volatile void *wake_hwnd;       /* hidden window, readable from any thread */
    volatile unsigned int uid;      /* Shell icon id */
    volatile long closing;          /* set by tray_wake(cancel_menu): no more menus, stop pumping */
};

int  tray_init(struct tray *tray);
/* Dispatches pending messages; when wait != 0 and none is pending, sleeps until one arrives.
 * Returns 0, or -1 once the tray is gone. */
int  tray_pump(struct tray *tray, int wait);
void tray_update(struct tray *tray);
void tray_exit(struct tray *tray);
/* Any thread: wakes tray_pump(); cancel_menu also closes an open popup menu. */
void tray_wake(struct tray *tray, int cancel_menu);

int tray_get_identity(struct tray *tray, void **out_hwnd, unsigned int *out_uid);
int tray_get_notification_icons_position(struct tray *tray, int *x, int *y);
const char *tray_get_notification_icons_region(void);

#ifdef __cplusplus
}
#endif
#endif /* NUCLEUS_TRAY_H */
