/* tray_windows.c - Shell_NotifyIcon tray icon with a native popup menu.
 *
 * One tray = one thread (see tray.h). Its context hangs off the hidden window's GWLP_USERDATA,
 * so the window procedure never searches a shared list and two trays never contend: a popup
 * menu open on one tray (TrackPopupMenu runs a modal loop) cannot stall another.
 */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
#include <stdlib.h>
#include <string.h>
#include "tray.h"

#define WM_TRAY_CALLBACK  (WM_APP + 1)
#define WM_TRAY_WAKE      (WM_APP + 2)
#define TRAY_CLASS_NAME   L"NucleusTrayWindow"
#define TRAY_FIRST_CMD    1000

typedef struct TrayContext {
    HWND hwnd;
    HMENU hmenu;
    NOTIFYICONDATAW nid;
    BOOL icon_added;
    BOOL menu_open;
    BOOL exiting;
    /* Command id -> item, rebuilt with the menu (ids are TRAY_FIRST_CMD + index). */
    struct tray_menu_item **commands;
    int command_count;
    int command_capacity;
    /* Every bitmap handed to the current menu, submenus included. */
    HBITMAP *bitmaps;
    int bitmap_count;
    int bitmap_capacity;
} TrayContext;

static INIT_ONCE g_class_once = INIT_ONCE_STATIC_INIT;
static UINT g_wm_taskbarcreated;
static volatile LONG g_next_uid = TRAY_FIRST_CMD;

/* ------------------------------------------------------------------------ */
/*  Strings                                                                  */
/* ------------------------------------------------------------------------ */

/* UTF-8 -> UTF-16, never NULL for a non-NULL input ("" -> L""). */
static WCHAR *utf8_to_wide(const char *s)
{
    if (!s) return NULL;
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n <= 0) n = 1;
    WCHAR *w = (WCHAR *)calloc((size_t)n, sizeof(WCHAR));
    if (!w) return NULL;
    if (*s) MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n);
    return w;
}

/* Menu label: '&' would be read as a mnemonic marker and vanish, so it is doubled. */
static WCHAR *menu_label(const char *s)
{
    WCHAR *w = utf8_to_wide(s ? s : "");
    if (!w) return NULL;
    size_t amps = 0, len = 0;
    for (; w[len]; len++) if (w[len] == L'&') amps++;
    if (!amps) return w;
    WCHAR *out = (WCHAR *)calloc(len + amps + 1, sizeof(WCHAR));
    if (!out) { free(w); return NULL; }
    for (size_t i = 0, j = 0; i < len; i++) {
        out[j++] = w[i];
        if (w[i] == L'&') out[j++] = L'&';
    }
    free(w);
    return out;
}

/* Copies into a fixed buffer without splitting a surrogate pair at the cut. */
static void copy_truncated(WCHAR *dst, size_t cap, const WCHAR *src)
{
    size_t i = 0;
    if (src) {
        for (; src[i] && i + 1 < cap; i++) dst[i] = src[i];
        if (i > 0 && src[i] && IS_HIGH_SURROGATE(dst[i - 1])) i--;
    }
    dst[i] = 0;
}

/* ------------------------------------------------------------------------ */
/*  Icons                                                                    */
/* ------------------------------------------------------------------------ */

/* SM_CXSMICON follows the system DPI (16@96, 20@120, 24@144, 32@192). */
static int small_icon_px(void)
{
    int cx = GetSystemMetrics(SM_CXSMICON);
    return cx > 0 ? cx : 16;
}

static HICON load_icon(const char *path)
{
    if (!path || !*path) return NULL;
    WCHAR *w = utf8_to_wide(path);
    if (!w) return NULL;
    int size = small_icon_px();
    HICON icon = (HICON)LoadImageW(NULL, w, IMAGE_ICON, size, size, LR_LOADFROMFILE);
    if (!icon) ExtractIconExW(w, 0, NULL, &icon, 1);
    free(w);
    return icon;
}

static HBITMAP bitmap_from_icon(HICON icon, int cx, int cy)
{
    BITMAPINFO bi;
    ZeroMemory(&bi, sizeof(bi));
    bi.bmiHeader.biSize = sizeof(bi.bmiHeader);
    bi.bmiHeader.biWidth = cx;
    bi.bmiHeader.biHeight = -cy;
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;

    void *bits = NULL;
    HDC screen = GetDC(NULL);
    HBITMAP bmp = CreateDIBSection(screen, &bi, DIB_RGB_COLORS, &bits, NULL, 0);
    if (bmp) {
        HDC mem = CreateCompatibleDC(screen);
        HGDIOBJ old = SelectObject(mem, bmp);
        DrawIconEx(mem, 0, 0, icon, cx, cy, 0, NULL, DI_NORMAL);
        SelectObject(mem, old);
        DeleteDC(mem);
    }
    ReleaseDC(NULL, screen);
    return bmp;
}

static HBITMAP load_menu_bitmap(const char *path)
{
    if (!path || !*path) return NULL;
    WCHAR *w = utf8_to_wide(path);
    if (!w) return NULL;
    int size = small_icon_px();
    HBITMAP bmp = (HBITMAP)LoadImageW(NULL, w, IMAGE_BITMAP, size, size, LR_LOADFROMFILE | LR_CREATEDIBSECTION);
    if (!bmp) {
        HICON icon = (HICON)LoadImageW(NULL, w, IMAGE_ICON, size, size, LR_LOADFROMFILE);
        if (icon) {
            bmp = bitmap_from_icon(icon, size, size);
            DestroyIcon(icon);
        }
    }
    free(w);
    return bmp;
}

/* ------------------------------------------------------------------------ */
/*  Menu                                                                     */
/* ------------------------------------------------------------------------ */

static int grow(void **array, int *capacity, int needed, size_t element)
{
    if (needed <= *capacity) return 1;
    int cap = *capacity ? *capacity * 2 : 16;
    while (cap < needed) cap *= 2;
    void *p = realloc(*array, (size_t)cap * element);
    if (!p) return 0;
    *array = p;
    *capacity = cap;
    return 1;
}

static void destroy_menu(TrayContext *ctx)
{
    if (ctx->hmenu) {
        DestroyMenu(ctx->hmenu); /* destroys the submenus too */
        ctx->hmenu = NULL;
    }
    for (int i = 0; i < ctx->bitmap_count; i++) DeleteObject(ctx->bitmaps[i]);
    ctx->bitmap_count = 0;
    ctx->command_count = 0;
}

static HMENU build_menu(TrayContext *ctx, struct tray_menu_item *m)
{
    HMENU menu = CreatePopupMenu();
    if (!menu) return NULL;
    for (; m && !m->terminator; ++m) {
        if (m->separator) {
            AppendMenuW(menu, MF_SEPARATOR, 0, NULL);
            continue;
        }
        WCHAR *label = menu_label(m->text);
        if (!label) continue;

        MENUITEMINFOW info;
        ZeroMemory(&info, sizeof(info));
        info.cbSize = sizeof(info);
        info.fMask = MIIM_ID | MIIM_STRING | MIIM_STATE | MIIM_FTYPE;
        info.fType = MFT_STRING;
        info.dwTypeData = label;
        info.cch = (UINT)wcslen(label);
        if (m->disabled) info.fState |= MFS_DISABLED;
        if (m->checked) info.fState |= MFS_CHECKED;

        if (grow((void **)&ctx->commands, &ctx->command_capacity, ctx->command_count + 1, sizeof(*ctx->commands))) {
            ctx->commands[ctx->command_count] = m;
            info.wID = TRAY_FIRST_CMD + ctx->command_count++;
        }
        if (m->submenu) {
            info.fMask |= MIIM_SUBMENU;
            info.hSubMenu = build_menu(ctx, m->submenu);
        }
        if (m->icon_path && *m->icon_path) {
            HBITMAP bmp = load_menu_bitmap(m->icon_path);
            if (bmp && grow((void **)&ctx->bitmaps, &ctx->bitmap_capacity, ctx->bitmap_count + 1, sizeof(HBITMAP))) {
                ctx->bitmaps[ctx->bitmap_count++] = bmp;
                info.fMask |= MIIM_BITMAP;
                info.hbmpItem = bmp;
            } else if (bmp) {
                DeleteObject(bmp);
            }
        }
        InsertMenuItemW(menu, (UINT)-1, TRUE, &info);
        free(label);
    }
    return menu;
}

static void show_menu(struct tray *tray, TrayContext *ctx)
{
    if (ctx->menu_open || ctx->exiting || tray->closing) return;
    if (tray->menu_opened_cb) tray->menu_opened_cb(tray);
    if (!ctx->hmenu || GetMenuItemCount(ctx->hmenu) <= 0) return;

    POINT p;
    GetCursorPos(&p);
    /* Without the foreground switch the menu would not close when clicking elsewhere. */
    SetForegroundWindow(ctx->hwnd);
    UINT align = GetSystemMetrics(SM_MENUDROPALIGNMENT) ? TPM_RIGHTALIGN : TPM_LEFTALIGN;
    ctx->menu_open = TRUE;
    UINT cmd = (UINT)TrackPopupMenuEx(ctx->hmenu, align | TPM_RIGHTBUTTON | TPM_RETURNCMD | TPM_NONOTIFY,
                                      p.x, p.y, ctx->hwnd, NULL);
    ctx->menu_open = FALSE;
    /* KB135788: lets the shell see the menu as dismissed. */
    PostMessageW(ctx->hwnd, WM_NULL, 0, 0);
    if (cmd && !ctx->exiting && !tray->closing) PostMessageW(ctx->hwnd, WM_COMMAND, cmd, 0);
}

/* ------------------------------------------------------------------------ */
/*  Window                                                                   */
/* ------------------------------------------------------------------------ */

static void apply_icon_data(struct tray *tray, TrayContext *ctx)
{
    HICON icon = load_icon(tray->icon_filepath);
    /* A bad path keeps the current icon rather than blanking the slot. */
    if (icon) {
        if (ctx->nid.hIcon) DestroyIcon(ctx->nid.hIcon);
        ctx->nid.hIcon = icon;
    }
    WCHAR *tip = utf8_to_wide(tray->tooltip ? tray->tooltip : "");
    copy_truncated(ctx->nid.szTip, sizeof(ctx->nid.szTip) / sizeof(WCHAR), tip);
    free(tip);
    ctx->nid.uFlags = NIF_MESSAGE | NIF_TIP | (ctx->nid.hIcon ? NIF_ICON : 0);
}

static void add_icon(TrayContext *ctx)
{
    ctx->icon_added = Shell_NotifyIconW(NIM_ADD, &ctx->nid);
}

static LRESULT CALLBACK tray_wnd_proc(HWND hwnd, UINT msg, WPARAM w, LPARAM l)
{
    struct tray *tray = (struct tray *)GetWindowLongPtrW(hwnd, GWLP_USERDATA);
    TrayContext *ctx = tray ? (TrayContext *)tray->impl : NULL;
    if (!ctx) return DefWindowProcW(hwnd, msg, w, l);

    switch (msg) {
    case WM_TRAY_CALLBACK:
        switch ((UINT)l) {
        case WM_LBUTTONUP:
            if (tray->cb) tray->cb(tray);
            else show_menu(tray, ctx);
            return 0;
        case WM_RBUTTONUP:
            show_menu(tray, ctx);
            return 0;
        }
        return 0;

    case WM_COMMAND: {
        int index = (int)LOWORD(w) - TRAY_FIRST_CMD;
        if (HIWORD(w) == 0 && index >= 0 && index < ctx->command_count) {
            struct tray_menu_item *item = ctx->commands[index];
            if (item && item->cb && !item->disabled) item->cb(item);
        }
        return 0;
    }

    case WM_TRAY_WAKE:
        if (w && ctx->menu_open) EndMenu();
        return 0;

    case WM_CLOSE:
        return 0; /* only tray_exit() destroys the window */

    default:
        if (msg == g_wm_taskbarcreated && g_wm_taskbarcreated) {
            /* Explorer restarted (or the DPI changed): re-add at the current icon size. */
            apply_icon_data(tray, ctx);
            add_icon(ctx);
            return 0;
        }
    }
    return DefWindowProcW(hwnd, msg, w, l);
}

typedef enum { AppModeDefault, AppModeAllowDark } PreferredAppMode;

static BOOL CALLBACK register_class_once(PINIT_ONCE once, PVOID param, PVOID *context)
{
    (void)once; (void)param; (void)context;
    /* Dark popup menus follow the system theme. uxtheme ordinal 135 is undocumented: guard it. */
    HMODULE ux = LoadLibraryW(L"uxtheme.dll");
    if (ux) {
        typedef PreferredAppMode(WINAPI * SetPreferredAppMode_t)(PreferredAppMode);
        SetPreferredAppMode_t set = (SetPreferredAppMode_t)GetProcAddress(ux, MAKEINTRESOURCEA(135));
        if (set) {
            __try { set(AppModeAllowDark); } __except (EXCEPTION_EXECUTE_HANDLER) { }
        }
    }
    g_wm_taskbarcreated = RegisterWindowMessageW(L"TaskbarCreated");

    WNDCLASSEXW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = tray_wnd_proc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = TRAY_CLASS_NAME;
    return RegisterClassExW(&wc) || GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
}

/* ------------------------------------------------------------------------ */
/*  Public API                                                               */
/* ------------------------------------------------------------------------ */

int tray_init(struct tray *tray)
{
    if (!tray || tray->impl) return -1;
    if (!InitOnceExecuteOnce(&g_class_once, register_class_once, NULL, NULL)) return -1;

    TrayContext *ctx = (TrayContext *)calloc(1, sizeof(TrayContext));
    if (!ctx) return -1;
    ctx->hwnd = CreateWindowExW(WS_EX_TOOLWINDOW, TRAY_CLASS_NAME, L"", WS_POPUP, 0, 0, 0, 0,
                                NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (!ctx->hwnd) {
        free(ctx);
        return -1;
    }
    tray->impl = ctx;
    SetWindowLongPtrW(ctx->hwnd, GWLP_USERDATA, (LONG_PTR)tray);

    ctx->nid.cbSize = sizeof(ctx->nid);
    ctx->nid.hWnd = ctx->hwnd;
    ctx->nid.uID = (UINT)InterlockedIncrement(&g_next_uid);
    ctx->nid.uCallbackMessage = WM_TRAY_CALLBACK;
    tray->uid = ctx->nid.uID;

    ctx->hmenu = build_menu(ctx, tray->menu);
    apply_icon_data(tray, ctx);
    add_icon(ctx);
    tray->wake_hwnd = ctx->hwnd;
    return 0;
}

int tray_pump(struct tray *tray, int wait)
{
    if (!tray || !tray->impl) return -1;
    MSG msg;
    if (tray->closing) return -1;
    if (wait && !PeekMessageW(&msg, NULL, 0, 0, PM_NOREMOVE)) {
        MsgWaitForMultipleObjectsEx(0, NULL, INFINITE, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
    }
    /* A closing tray stops here: a backlog of queued clicks must not open menus after dispose. */
    while (!tray->closing && PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
        if (msg.message == WM_QUIT) continue; /* not ours to honour: the owner ends the loop */
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return 0;
}

void tray_update(struct tray *tray)
{
    if (!tray || !tray->impl) return;
    TrayContext *ctx = (TrayContext *)tray->impl;
    destroy_menu(ctx);
    ctx->hmenu = build_menu(ctx, tray->menu);
    apply_icon_data(tray, ctx);
    if (!ctx->icon_added || !Shell_NotifyIconW(NIM_MODIFY, &ctx->nid)) add_icon(ctx);
}

void tray_exit(struct tray *tray)
{
    if (!tray || !tray->impl) return;
    TrayContext *ctx = (TrayContext *)tray->impl;
    ctx->exiting = TRUE;
    tray->wake_hwnd = NULL;
    Shell_NotifyIconW(NIM_DELETE, &ctx->nid);
    if (ctx->nid.hIcon) DestroyIcon(ctx->nid.hIcon);
    destroy_menu(ctx);
    SetWindowLongPtrW(ctx->hwnd, GWLP_USERDATA, 0);
    DestroyWindow(ctx->hwnd);
    free(ctx->commands);
    free(ctx->bitmaps);
    free(ctx);
    tray->impl = NULL;
}

void tray_wake(struct tray *tray, int cancel_menu)
{
    if (!tray) return;
    if (cancel_menu) InterlockedExchange(&tray->closing, 1);
    HWND hwnd = (HWND)tray->wake_hwnd;
    if (hwnd) PostMessageW(hwnd, WM_TRAY_WAKE, (WPARAM)(cancel_menu != 0), 0);
}

int tray_get_identity(struct tray *tray, void **out_hwnd, unsigned int *out_uid)
{
    if (!tray || !out_hwnd || !out_uid) return 0;
    HWND hwnd = (HWND)tray->wake_hwnd;
    if (!hwnd) return 0;
    *out_hwnd = hwnd;
    *out_uid = tray->uid;
    return 1;
}

static BOOL get_tray_icon_rect(struct tray *tray, RECT *r)
{
    HWND hwnd = tray ? (HWND)tray->wake_hwnd : NULL;
    if (!hwnd) return FALSE;
    NOTIFYICONIDENTIFIER nii;
    ZeroMemory(&nii, sizeof(nii));
    nii.cbSize = sizeof(nii);
    nii.hWnd = hwnd;
    nii.uID = tray->uid;
    return SUCCEEDED(Shell_NotifyIconGetRect(&nii, r));
}

int tray_get_notification_icons_position(struct tray *tray, int *x, int *y)
{
    if (!x || !y) return 0;
    RECT r;
    ZeroMemory(&r, sizeof(r));
    if (get_tray_icon_rect(tray, &r)) {
        MONITORINFO mi;
        ZeroMemory(&mi, sizeof(mi));
        mi.cbSize = sizeof(mi);
        HMONITOR mon = MonitorFromRect(&r, MONITOR_DEFAULTTOPRIMARY);
        *x = (r.left + r.right) / 2;
        /* Anchor below an icon in the top half of its monitor, above it otherwise. */
        if (GetMonitorInfoW(mon, &mi)) {
            *y = (r.top < (mi.rcMonitor.top + mi.rcMonitor.bottom) / 2) ? r.bottom : r.top;
        } else {
            *y = r.bottom;
        }
        return 1;
    }
    HWND taskbar = FindWindowW(L"Shell_TrayWnd", NULL);
    HWND notify = taskbar ? FindWindowExW(taskbar, NULL, L"TrayNotifyWnd", NULL) : NULL;
    if (notify && GetWindowRect(notify, &r)) {
        *x = r.left;
        *y = r.top;
    } else {
        *x = *y = 0;
    }
    return 0;
}

const char *tray_get_notification_icons_region(void)
{
    RECT r;
    POINT p = { 0, 0 };
    HWND taskbar = FindWindowW(L"Shell_TrayWnd", NULL);
    HWND notify = taskbar ? FindWindowExW(taskbar, NULL, L"TrayNotifyWnd", NULL) : NULL;
    if (notify && GetWindowRect(notify, &r)) {
        p.x = r.left;
        p.y = r.top;
    }
    MONITORINFO mi;
    ZeroMemory(&mi, sizeof(mi));
    mi.cbSize = sizeof(mi);
    GetMonitorInfoW(MonitorFromPoint(p, MONITOR_DEFAULTTOPRIMARY), &mi);
    LONG mid_x = (mi.rcMonitor.right + mi.rcMonitor.left) / 2;
    LONG mid_y = (mi.rcMonitor.bottom + mi.rcMonitor.top) / 2;
    if (p.x < mid_x && p.y < mid_y) return "top-left";
    if (p.x >= mid_x && p.y < mid_y) return "top-right";
    if (p.x < mid_x && p.y >= mid_y) return "bottom-left";
    return "bottom-right";
}
