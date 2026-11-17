/* tray.c - Windows implementation with full Unicode support */
#define COBJMACROS
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include "tray.h"

/* -------------------------------------------------------------------------- */
/*  Helpers: opt-in dark mode                                                 */
/* -------------------------------------------------------------------------- */
typedef enum {
    AppMode_Default,
    AppMode_AllowDark,
    AppMode_ForceDark,
    AppMode_ForceLight,
    AppMode_Max
} PreferredAppMode;

static void tray_enable_dark_mode(void)
{
    HMODULE hUx = LoadLibraryW(L"uxtheme.dll");
    if (!hUx) return;

    typedef PreferredAppMode (WINAPI *SetPreferredAppMode_t)(PreferredAppMode);
    SetPreferredAppMode_t SetPreferredAppMode =
        (SetPreferredAppMode_t)GetProcAddress(hUx, MAKEINTRESOURCEA(135));
    if (SetPreferredAppMode)
        SetPreferredAppMode(AppMode_AllowDark);
}

/* -------------------------------------------------------------------------- */
/*  UTF-8 to UTF-16 conversion helper                                         */
/* -------------------------------------------------------------------------- */
static LPWSTR utf8_to_wide(const char *utf8_str)
{
    if (!utf8_str || !*utf8_str) return NULL;

    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8_str, -1, NULL, 0);
    if (wlen <= 0) return NULL;

    LPWSTR wide_str = (LPWSTR)malloc(wlen * sizeof(WCHAR));
    if (!wide_str) return NULL;

    MultiByteToWideChar(CP_UTF8, 0, utf8_str, -1, wide_str, wlen);
    return wide_str;
}

/* -------------------------------------------------------------------------- */
/*  UTF-16 to UTF-8 conversion helper                                         */
/* -------------------------------------------------------------------------- */
static char* wide_to_utf8(const WCHAR *wide_str)
{
    if (!wide_str || !*wide_str) return NULL;

    int utf8_len = WideCharToMultiByte(CP_UTF8, 0, wide_str, -1, NULL, 0, NULL, NULL);
    if (utf8_len <= 0) return NULL;

    char *utf8_str = (char*)malloc(utf8_len);
    if (!utf8_str) return NULL;

    WideCharToMultiByte(CP_UTF8, 0, wide_str, -1, utf8_str, utf8_len, NULL, NULL);
    return utf8_str;
}

/* -------------------------------------------------------------------------- */
/*  Internal constants                                                        */
/* -------------------------------------------------------------------------- */
#define WM_TRAY_CALLBACK_MESSAGE (WM_USER + 1)
#define WC_TRAY_CLASS_NAME       L"TRAY"
#define ID_TRAY_FIRST            1000

/* -------------------------------------------------------------------------- */
/*  Internal variables                                                        */
/* -------------------------------------------------------------------------- */
static struct tray     *tray_instance  = NULL;   /* unused in multi-instance mode */
static WNDCLASSEXW      wc             = {0};
static NOTIFYICONDATAW  nid            = {0};    /* unused in multi-instance mode */
static HWND             hwnd           = NULL;   /* unused in multi-instance mode */
static HMENU            hmenu          = NULL;   /* unused in multi-instance mode */
static UINT             wm_taskbarcreated;
static BOOL             exit_called    = FALSE;  /* unused in multi-instance mode */
static CRITICAL_SECTION tray_cs;
static BOOL             cs_initialized = FALSE;

/* Multi-instance support: one context per tray */
typedef struct TrayContext {
    struct tray *tray;                /* public tray pointer (key)        */
    HWND         hwnd;                /* hidden window for messages       */
    HMENU        hmenu;               /* root menu                        */
    NOTIFYICONDATAW nid;              /* per-icon notify data             */
    UINT         uID;                 /* unique id for Shell_NotifyIcon   */
    DWORD        threadId;            /* thread that owns this context    */
    BOOL         exiting;             /* exit requested for this context  */
    void       (*menu_opened_cb)(struct tray *); /* called before menu popup */
    struct TrayContext *next;         /* linked list                      */
} TrayContext;

static TrayContext *g_ctx_head = NULL;
static UINT g_next_uid = ID_TRAY_FIRST;

static void ensure_critical_section(void);
static TrayContext* find_ctx_by_tray(struct tray *t);
static TrayContext* find_ctx_by_hwnd(HWND h);
static TrayContext* find_ctx_by_uid(UINT uid);
static TrayContext* find_ctx_by_thread(DWORD tid);
static TrayContext* create_ctx(struct tray *t);
static void destroy_ctx(TrayContext *ctx);

/* -------------------------------------------------------------------------- */
/*  Internal prototypes                                                       */
/* -------------------------------------------------------------------------- */
static HMENU tray_menu_item(struct tray_menu_item *m, UINT *id);
static void ensure_critical_section(void);
static HBITMAP load_icon_bitmap(const char *icon_path);
static int small_icon_px(void);

/* -------------------------------------------------------------------------- */
/*  Critical section helper                                                   */
/* -------------------------------------------------------------------------- */
static void ensure_critical_section(void)
{
    if (!cs_initialized) {
        InitializeCriticalSection(&tray_cs);
        cs_initialized = TRUE;
    }
}

/* -------------------------------------------------------------------------- */
/*  Context helpers                                                            */
/* -------------------------------------------------------------------------- */
static TrayContext* find_ctx_by_tray(struct tray *t)
{
    TrayContext *p = g_ctx_head;
    while (p) {
        if (p->tray == t) return p;
        p = p->next;
    }
    return NULL;
}

static TrayContext* find_ctx_by_hwnd(HWND h)
{
    TrayContext *p = g_ctx_head;
    while (p) {
        if (p->hwnd == h) return p;
        p = p->next;
    }
    return NULL;
}

static TrayContext* find_ctx_by_uid(UINT uid)
{
    TrayContext *p = g_ctx_head;
    while (p) {
        if (p->uID == uid) return p;
        p = p->next;
    }
    return NULL;
}

static TrayContext* find_ctx_by_thread(DWORD tid)
{
    TrayContext *p = g_ctx_head;
    while (p) {
        if (p->threadId == tid) return p;
        p = p->next;
    }
    return NULL;
}

static TrayContext* create_ctx(struct tray *t)
{
    TrayContext *ctx = (TrayContext*)calloc(1, sizeof(TrayContext));
    if (!ctx) return NULL;
    ctx->tray     = t;
    ctx->hwnd     = NULL;
    ctx->hmenu    = NULL;
    ZeroMemory(&ctx->nid, sizeof(ctx->nid));
    ctx->uID      = g_next_uid++;
    ctx->threadId = GetCurrentThreadId();
    ctx->exiting  = FALSE;

    /* Insert at head */
    ctx->next = g_ctx_head;
    g_ctx_head = ctx;
    return ctx;
}

static void destroy_ctx(TrayContext *ctx)
{
    if (!ctx) return;

    /* Remove from list */
    if (g_ctx_head == ctx) {
        g_ctx_head = ctx->next;
    } else {
        TrayContext *prev = g_ctx_head;
        while (prev && prev->next != ctx) prev = prev->next;
        if (prev) prev->next = ctx->next;
    }

    /* Free menu */
    if (ctx->hmenu) {
        MENUITEMINFOW item = {0};
        item.cbSize = sizeof(item);
        item.fMask = MIIM_BITMAP;
        int count = GetMenuItemCount(ctx->hmenu);
        for (int i = 0; i < count; i++) {
            if (GetMenuItemInfoW(ctx->hmenu, i, TRUE, &item)) {
                if (item.hbmpItem && item.hbmpItem != HBMMENU_CALLBACK) {
                    DeleteObject(item.hbmpItem);
                }
            }
        }
        DestroyMenu(ctx->hmenu);
        ctx->hmenu = NULL;
    }

    /* Destroy window */
    if (ctx->hwnd) {
        DestroyWindow(ctx->hwnd);
        ctx->hwnd = NULL;
    }

    /* Free icon handle if any */
    if (ctx->nid.hIcon) {
        DestroyIcon(ctx->nid.hIcon);
        ctx->nid.hIcon = NULL;
    }

    free(ctx);
}

/* ------------------------------------------------------------------ */
/*  Safe conversion of an HICON to ARGB 32-bit bitmap                */
/* ------------------------------------------------------------------ */
static HBITMAP bitmap_from_icon(HICON hIcon, int cx, int cy)
{
    if (!hIcon) return NULL;

    BITMAPINFO bi = {0};
    bi.bmiHeader.biSize        = sizeof(bi.bmiHeader);
    bi.bmiHeader.biWidth       = cx;
    bi.bmiHeader.biHeight      = -cy;          /* top-down orientation */
    bi.bmiHeader.biPlanes      = 1;
    bi.bmiHeader.biBitCount    = 32;           /* BGRA */
    bi.bmiHeader.biCompression = BI_RGB;

    void   *bits = NULL;
    HDC     hdc  = GetDC(NULL);
    HBITMAP hbmp = CreateDIBSection(hdc, &bi, DIB_RGB_COLORS, &bits, NULL, 0);
    if (hbmp) {
        HDC hdcMem   = CreateCompatibleDC(hdc);
        HBITMAP hold = (HBITMAP)SelectObject(hdcMem, hbmp);

        DrawIconEx(hdcMem, 0, 0, hIcon, cx, cy, 0, NULL, DI_NORMAL);

        SelectObject(hdcMem, hold);
        DeleteDC(hdcMem);
    }
    ReleaseDC(NULL, hdc);
    return hbmp;
}

/* SM_CXSMICON follows system DPI (16@96dpi, 20@120dpi, 24@144dpi, 32@192dpi). */
static int small_icon_px(void)
{
    int cx = GetSystemMetrics(SM_CXSMICON);
    return cx > 0 ? cx : 16;
}

/* ------------------------------------------------------------------ */
/*  Generic loading of icon/bitmap from disk → ARGB bitmap           */
/* ------------------------------------------------------------------ */
static HBITMAP load_icon_bitmap(const char *icon_path)
{
    if (!icon_path || !*icon_path) return NULL;

    int size = small_icon_px();

    /* Convert UTF-8 path to Wide */
    LPWSTR wpath = utf8_to_wide(icon_path);
    if (!wpath) return NULL;

    /* 1st: try direct .bmp/.png as 32-bit DIB at the DPI-scaled size */
    HBITMAP hbmp = (HBITMAP)LoadImageW(
        NULL, wpath,
        IMAGE_BITMAP,
        size, size,
        LR_LOADFROMFILE | LR_CREATEDIBSECTION
    );
    if (hbmp) {
        free(wpath);
        return hbmp;
    }

    /* 2nd: try .ico → ARGB conversion, picking the matching ICO frame */
    HICON hIcon = (HICON)LoadImageW(
        NULL, wpath,
        IMAGE_ICON,
        size, size,
        LR_LOADFROMFILE
    );
    free(wpath);

    if (hIcon) {
        hbmp = bitmap_from_icon(hIcon, size, size);
        DestroyIcon(hIcon);
    }
    return hbmp;
}

/* -------------------------------------------------------------------------- */
/*  Invisible window procedure                                                */
/* -------------------------------------------------------------------------- */
static LRESULT CALLBACK tray_wnd_proc(HWND h, UINT msg, WPARAM w, LPARAM l)
{
    TrayContext *ctx = find_ctx_by_hwnd(h);
    switch (msg)
    {
    case WM_CLOSE:
        DestroyWindow(h);
        return 0;

    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;

    case WM_TRAY_CALLBACK_MESSAGE:
        if (l == WM_LBUTTONUP && ctx && ctx->tray && ctx->tray->cb) {
            EnterCriticalSection(&tray_cs);
            if (ctx && ctx->tray && ctx->tray->cb) {
                ctx->tray->cb(ctx->tray);
            }
            LeaveCriticalSection(&tray_cs);
            return 0;
        }
        if (l == WM_LBUTTONUP || l == WM_RBUTTONUP) {
            POINT p;
            GetCursorPos(&p);
            SetForegroundWindow(h);

            /* Invoke menu-opened callback outside the critical section
             * to avoid deadlocks with JNI calls */
            if (ctx && ctx->menu_opened_cb && ctx->tray) {
                ctx->menu_opened_cb(ctx->tray);
            }

            EnterCriticalSection(&tray_cs);
            if (ctx && ctx->hmenu) {
                WORD cmd = TrackPopupMenu(ctx->hmenu,
                                          TPM_LEFTALIGN | TPM_RIGHTBUTTON |
                                          TPM_RETURNCMD | TPM_NONOTIFY,
                                          p.x, p.y, 0, h, NULL);
                SendMessage(h, WM_COMMAND, cmd, 0);
            }
            LeaveCriticalSection(&tray_cs);
            return 0;
        }
        break;

    case WM_COMMAND:
        if (w >= ID_TRAY_FIRST) {
            EnterCriticalSection(&tray_cs);
            if (ctx && ctx->hmenu) {
                MENUITEMINFOW item = { 0 };
                item.cbSize = sizeof(item);
                item.fMask = MIIM_ID | MIIM_DATA;
                if (GetMenuItemInfoW(ctx->hmenu, (UINT)w, FALSE, &item)) {
                    struct tray_menu_item *mi = (struct tray_menu_item *)item.dwItemData;
                    if (mi && mi->cb) mi->cb(mi);
                }
            }
            LeaveCriticalSection(&tray_cs);
            return 0;
        }
        break;

    default:
        if (msg == wm_taskbarcreated) {
            if (ctx) {
                Shell_NotifyIconW(NIM_ADD, &ctx->nid);
            }
            return 0;
        }
    }
    return DefWindowProcW(h, msg, w, l);
}

/* -------------------------------------------------------------------------- */
/*  Recursive HMENU construction with safe icon support                       */
/* -------------------------------------------------------------------------- */
static HMENU tray_menu_item(struct tray_menu_item *m, UINT *id)
{
    HMENU menu = CreatePopupMenu();
    if (!menu) return NULL;

    for (; m && m->text; ++m) {

        /* Separator "-" */
        if (strcmp(m->text, "-") == 0) {
            AppendMenuW(menu, MF_SEPARATOR, 0, NULL);
            continue;
        }

        /* Normal item (text + optional icon + submenu) */
        MENUITEMINFOW info;
        ZeroMemory(&info, sizeof(info));
        info.cbSize = sizeof(info);

        /* Convert UTF-8 text to Wide */
        LPWSTR wtext = utf8_to_wide(m->text);
        if (!wtext) continue;

        /* Text: MIIM_STRING + MFT_STRING instead of MIIM_TYPE */
        info.fMask      = MIIM_ID | MIIM_STRING | MIIM_STATE |
                          MIIM_FTYPE | MIIM_DATA;
        info.fType      = MFT_STRING;
        info.dwTypeData = wtext;
        info.cch        = (UINT)wcslen(wtext);

        /* Unique identifier */
        info.wID        = (*id)++;
        info.dwItemData = (ULONG_PTR)m;

        /* Optional submenu */
        if (m->submenu) {
            info.fMask   |= MIIM_SUBMENU;
            info.hSubMenu = tray_menu_item(m->submenu, id);
        }

        /* State (disabled / checked) */
        if (m->disabled) info.fState |= MFS_DISABLED;
        if (m->checked)  info.fState |= MFS_CHECKED;

        /* Optional icon */
        if (m->icon_path && *m->icon_path) {
            HBITMAP hBmp = load_icon_bitmap(m->icon_path);
            if (hBmp) {
                info.fMask    |= MIIM_BITMAP;
                info.hbmpItem  = hBmp;
            }
        }

        /* Append at end of menu to avoid out-of-range indexes */
        InsertMenuItemW(menu, (UINT)-1, TRUE, &info);

        free(wtext);
    }
    return menu;
}

/* -------------------------------------------------------------------------- */
/*  Public API                                                                */
/* -------------------------------------------------------------------------- */
struct tray *tray_get_instance(void) {
    ensure_critical_section();
    EnterCriticalSection(&tray_cs);
    TrayContext *ctx = find_ctx_by_thread(GetCurrentThreadId());
    if (!ctx) ctx = g_ctx_head; /* fallback */
    struct tray *t = ctx ? ctx->tray : NULL;
    LeaveCriticalSection(&tray_cs);
    return t;
}

/* -------------------------------------------------------------------------- */
/*  Initializes the tray icon and creates the hidden message window           */
/* -------------------------------------------------------------------------- */
int tray_init(struct tray *tray)
{
    if (!tray) return -1;

    ensure_critical_section();

    /* Wrap in SEH: tray_enable_dark_mode uses undocumented ordinal 135 of
       uxtheme.dll which may cause an access violation on some Windows 10 builds. */
    __try {
        tray_enable_dark_mode();
    } __except(EXCEPTION_EXECUTE_HANDLER) {
        /* Silently ignore – dark-mode theming is cosmetic only. */
    }
    wm_taskbarcreated = RegisterWindowMessageW(L"TaskbarCreated");

    // Register (ignore if the class already exists)
    ZeroMemory(&wc, sizeof(wc));
    wc.cbSize        = sizeof(wc);
    wc.lpfnWndProc   = tray_wnd_proc;
    wc.hInstance     = GetModuleHandleW(NULL);
    wc.lpszClassName = WC_TRAY_CLASS_NAME;
    if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
        return -1;

    EnterCriticalSection(&tray_cs);
    TrayContext *ctx = find_ctx_by_tray(tray);
    if (!ctx) ctx = create_ctx(tray);
    LeaveCriticalSection(&tray_cs);
    if (!ctx) return -1;

    ctx->hwnd = CreateWindowExW(
        0,
        WC_TRAY_CLASS_NAME,
        NULL,
        0,
        0, 0, 0, 0,
        0,
        0,
        GetModuleHandleW(NULL),
        NULL);
    if (!ctx->hwnd) return -1;

    ZeroMemory(&ctx->nid, sizeof(ctx->nid));
    ctx->nid.cbSize           = sizeof(ctx->nid);
    ctx->nid.hWnd             = ctx->hwnd;
    ctx->nid.uID              = ctx->uID;
    ctx->nid.uFlags           = NIF_ICON | NIF_MESSAGE;
    ctx->nid.uCallbackMessage = WM_TRAY_CALLBACK_MESSAGE;
    Shell_NotifyIconW(NIM_ADD, &ctx->nid);

    tray_update(tray);
    return 0;
}

/* Message loop: blocking = 1 -> GetMessage, 0 -> PeekMessage */
int tray_loop(int blocking)
{
    MSG msg;

    /* Ensure there is at least one context for this thread */
    DWORD tid = GetCurrentThreadId();
    EnterCriticalSection(&tray_cs);
    TrayContext *ctx = find_ctx_by_thread(tid);
    LeaveCriticalSection(&tray_cs);
    if (!ctx) return -1;

    if (blocking) {
        int ret = GetMessageW(&msg, NULL, 0, 0);
        if (ret == 0) {
            return -1; /* WM_QUIT */
        } else if (ret < 0) {
            return -1; /* Error */
        }
    } else {
        if (!PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE))
            return 0;
    }

    if (msg.message == WM_QUIT)
        return -1;

    TranslateMessage(&msg);
    DispatchMessageW(&msg);
    return 0;
}

/* Updates icon, tooltip and menu */
void tray_update(struct tray *tray)
{
    if (!tray) return;

    ensure_critical_section();
    EnterCriticalSection(&tray_cs);

    /* Prefer thread-local context to tolerate different struct pointers across updates */
    TrayContext *ctx = find_ctx_by_thread(GetCurrentThreadId());
    if (!ctx) ctx = find_ctx_by_tray(tray);
    if (!ctx) { LeaveCriticalSection(&tray_cs); return; }

    /* Update pointer to reflect latest struct (callbacks, etc.) */
    ctx->tray = tray;

    // Clean up old menu and its bitmaps
    if (ctx->hmenu) {
        MENUITEMINFOW item = {0};
        item.cbSize = sizeof(item);
        item.fMask = MIIM_BITMAP;

        int count = GetMenuItemCount(ctx->hmenu);
        for (int i = 0; i < count; i++) {
            if (GetMenuItemInfoW(ctx->hmenu, i, TRUE, &item)) {
                if (item.hbmpItem && item.hbmpItem != HBMMENU_CALLBACK) {
                    DeleteObject(item.hbmpItem);
                }
            }
        }
        DestroyMenu(ctx->hmenu);
        ctx->hmenu = NULL;
    }

    UINT   id = ID_TRAY_FIRST;
    ctx->hmenu = tray_menu_item(tray->menu, &id);

    /* Icon: request SM_CXSMICON so a multi-frame ICO yields an exact DPI match. */
    HICON icon = NULL;
    if (tray->icon_filepath && *tray->icon_filepath) {
        LPWSTR wpath = utf8_to_wide(tray->icon_filepath);
        if (wpath) {
            int size = small_icon_px();
            icon = (HICON)LoadImageW(NULL, wpath, IMAGE_ICON, size, size, LR_LOADFROMFILE);
            if (!icon) {
                ExtractIconExW(wpath, 0, NULL, &icon, 1);
            }
            free(wpath);
        }
    }
    if (ctx->nid.hIcon && ctx->nid.hIcon != icon) {
        DestroyIcon(ctx->nid.hIcon);
    }
    ctx->nid.hIcon = icon;

    /* Tooltip */
    ctx->nid.uFlags = NIF_ICON | NIF_MESSAGE;
    if (tray->tooltip && *tray->tooltip) {
        LPWSTR wtooltip = utf8_to_wide(tray->tooltip);
        if (wtooltip) {
            wcsncpy_s(ctx->nid.szTip, sizeof(ctx->nid.szTip)/sizeof(WCHAR), wtooltip, _TRUNCATE);
            ctx->nid.uFlags |= NIF_TIP;
            free(wtooltip);
        }
    }

    /* Update the tray */
    Shell_NotifyIconW(NIM_MODIFY, &ctx->nid);

    LeaveCriticalSection(&tray_cs);
}

/* -------------------------------------------------------------------------- */
/*  Cleanly shuts down and unregisters everything                             */
/* -------------------------------------------------------------------------- */
void tray_exit(void)
{
    ensure_critical_section();
    EnterCriticalSection(&tray_cs);

    TrayContext *ctx = find_ctx_by_thread(GetCurrentThreadId());
    if (!ctx) ctx = g_ctx_head; /* fallback */
    if (!ctx) {
        LeaveCriticalSection(&tray_cs);
        return;
    }

    /* Remove tray icon */
    Shell_NotifyIconW(NIM_DELETE, &ctx->nid);
    if (ctx->nid.hIcon) {
        DestroyIcon(ctx->nid.hIcon);
        ctx->nid.hIcon = NULL;
    }

    /* Post WM_QUIT to unblock any blocking GetMessage call and destroy window */
    if (ctx->hwnd) {
        PostMessageW(ctx->hwnd, WM_QUIT, 0, 0);
        DestroyWindow(ctx->hwnd);
        ctx->hwnd = NULL;
    }

    /* Destroy context (frees menu bitmaps, etc.) */
    destroy_ctx(ctx);

    /* If no more contexts, unregister class and optionally release critical section */
    if (!g_ctx_head) {
        UnregisterClassW(WC_TRAY_CLASS_NAME, GetModuleHandleW(NULL));
        LeaveCriticalSection(&tray_cs);
        DeleteCriticalSection(&tray_cs);
        cs_initialized = FALSE;
        return;
    }

    LeaveCriticalSection(&tray_cs);
}

/* -------------------------------------------------------------------------- */
/*  Set/clear the menu-opened callback for a given tray                       */
/* -------------------------------------------------------------------------- */
void tray_set_menu_opened_callback(struct tray *tray, void (*cb)(struct tray *))
{
    if (!tray) return;
    ensure_critical_section();
    EnterCriticalSection(&tray_cs);
    TrayContext *ctx = find_ctx_by_tray(tray);
    if (!ctx) ctx = find_ctx_by_thread(GetCurrentThreadId());
    if (ctx) ctx->menu_opened_cb = cb;
    LeaveCriticalSection(&tray_cs);
}

static BOOL get_tray_icon_rect(RECT *r)
{
    /* Use per-thread context to identify the correct tray icon */
    ensure_critical_section();
    EnterCriticalSection(&tray_cs);
    TrayContext *ctx = find_ctx_by_thread(GetCurrentThreadId());
    if (!ctx) ctx = g_ctx_head; /* fallback to first */
    HWND l_hwnd = ctx ? ctx->hwnd : NULL;
    UINT l_uid  = ctx ? ctx->nid.uID : 0;
    LeaveCriticalSection(&tray_cs);

    if (!l_hwnd) return FALSE;

    HMODULE hShell = GetModuleHandleW(L"shell32.dll");
    if (!hShell) return FALSE;

    typedef HRESULT (WINAPI *NIGetRect_t)(const NOTIFYICONIDENTIFIER*, RECT*);
    NIGetRect_t pGetRect = (NIGetRect_t)GetProcAddress(hShell, "Shell_NotifyIconGetRect");
    if (!pGetRect) return FALSE;               /* OS too old */

    /* CRITICAL FIX: Properly zero-initialize the entire structure */
    /* This ensures all fields including guidItem are zeroed */
    NOTIFYICONIDENTIFIER nii;
    ZeroMemory(&nii, sizeof(nii));
    nii.cbSize = sizeof(nii);
    nii.hWnd = l_hwnd;        /* owner window */
    nii.uID  = l_uid;         /* id */

    /* The guidItem field is now safely zeroed.
     * When guidItem is zero (NULL GUID), Windows will use hWnd/uID for identification */

    return SUCCEEDED(pGetRect(&nii, r));
}

/* -------------------------------------------------------------------------- */
/*  Notification area info                                                    */
/* -------------------------------------------------------------------------- */
int tray_get_notification_icons_position(int *x, int *y)
{
    if (!x || !y) return 0;  /* Safety check for null pointers */

    RECT r = {0};
    BOOL precise = get_tray_icon_rect(&r);   /* TRUE if modern API OK */

    if (precise) {
        /* Use the actual icon rectangle to compute a better anchor */
        int cx = (r.left + r.right) / 2;   /* center X of the icon */
        int cy = 0;                        /* anchor Y: bottom for top tray, top for bottom tray */

        HMONITOR hMon = MonitorFromRect(&r, MONITOR_DEFAULTTOPRIMARY);
        MONITORINFO mi = {0};
        mi.cbSize = sizeof(mi);
        if (GetMonitorInfoW(hMon, &mi)) {
            LONG midY = (mi.rcMonitor.bottom + mi.rcMonitor.top) / 2;
            /* If icon is on top half of monitor, anchor below it; otherwise above */
            cy = (r.top < midY) ? r.bottom : r.top;
        } else {
            /* Fallback if monitor info is unavailable: use bottom as a reasonable default */
            cy = r.bottom;
        }
        *x = cx;
        *y = cy;
        return 1;                           /* precise */
    } else {
        /* Fallback: use notification area window rect (top-left) */
        HWND hTray  = FindWindowW(L"Shell_TrayWnd", NULL);
        HWND hNotif = FindWindowExW(hTray, NULL, L"TrayNotifyWnd", NULL);
        if (!hNotif || !GetWindowRect(hNotif, &r)) {
            *x = *y = 0;
            return 0;                        /* nothing reliable */
        }
        *x = r.left;
        *y = r.top;
        return 0;                            /* not precise */
    }
}

const char *tray_get_notification_icons_region(void)
{
    RECT  r;
    POINT p = {0, 0};
    HWND  hTray = FindWindowW(L"Shell_TrayWnd", NULL);
    HWND  hNotif = FindWindowExW(hTray, NULL, L"TrayNotifyWnd", NULL);

    if (hNotif && GetWindowRect(hNotif, &r)) {
        p.x = r.left; p.y = r.top;
    }

    HMONITOR hMon = MonitorFromWindow(hNotif, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO mi = { .cbSize = sizeof(mi) };
    GetMonitorInfoW(hMon, &mi);

    LONG midX = (mi.rcMonitor.right  + mi.rcMonitor.left) / 2;
    LONG midY = (mi.rcMonitor.bottom + mi.rcMonitor.top)  / 2;

    if (p.x < midX && p.y < midY) return "top-left";
    if (p.x >= midX && p.y < midY) return "top-right";
    if (p.x < midX && p.y >= midY) return "bottom-left";
    return "bottom-right";
}