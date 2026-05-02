/*
 * nucleus_tao_a11y.c — UI Automation provider for the Tao backend on Windows.
 *
 * Mirror of objc/a11y.m: parses the same wire format produced by
 * TaoA11ySnapshotSerializer (Kotlin) and projects it as a UIA fragment tree
 * rooted on the Tao-owned HWND.
 *
 * Wire format documented in objc/a11y.m header — version 4.
 *
 * Architecture:
 *   - One NucleusUiaProjection per HWND, parses snapshots and owns the
 *     element tree.
 *   - NucleusUiaRoot: implements IRawElementProviderSimple +
 *     IRawElementProviderFragment + IRawElementProviderFragmentRoot. Returned
 *     to UIA on WM_GETOBJECT(UiaRootObjectId).
 *   - NucleusUiaElement: per-Compose-node provider, implements Simple +
 *     Fragment + (conditionally) IInvokeProvider. Patterns Toggle/Value/etc.
 *     come in a follow-up.
 *
 * Linked libraries: kernel32.lib user32.lib uiautomationcore.lib oleaut32.lib
 *                   ole32.lib uuid.lib
 */

#define COBJMACROS
#define CINTERFACE
#define INITGUID

#include <jni.h>
#include <windows.h>
#include <commctrl.h>
#include <objbase.h>
#include <oleauto.h>
#include <uiautomation.h>
#include <uiautomationcoreapi.h>
#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>

/* /NODEFAULTLIB support — supplied by sibling DLL but we statically need them */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

#pragma function(memcpy)
void *memcpy(void *dest, const void *src, size_t count) {
    unsigned char *d = (unsigned char *)dest;
    const unsigned char *s = (const unsigned char *)src;
    while (count--) *d++ = *s++;
    return dest;
}

int memcmp(const void *a, const void *b, size_t count) {
    const unsigned char *p = (const unsigned char *)a;
    const unsigned char *q = (const unsigned char *)b;
    while (count--) {
        if (*p != *q) return (int)*p - (int)*q;
        p++; q++;
    }
    return 0;
}

/* The Windows SDK declares these as `const long` in C++-only blocks, which
 * makes them unusable in C `case` labels. Redefine the subset we need as
 * preprocessor constants. Values are MIDL-stable (UIAutomationClient.h /
 * UIAutomationCore.h) — checked against SDK 10.0.22621.0. */
#define UIA_InvokePatternId           10000
#define UIA_SelectionPatternId        10001
#define UIA_ValuePatternId            10002
#define UIA_RangeValuePatternId       10003
#define UIA_ScrollPatternId           10004
#define UIA_ExpandCollapsePatternId   10005
#define UIA_GridPatternId             10006
#define UIA_GridItemPatternId         10007
#define UIA_TogglePatternId           10015
#define UIA_SelectionItemPatternId    10010
#define UIA_TextPatternId             10014
#define UIA_ScrollItemPatternId       10017

#define UIA_ControlTypePropertyId          30003
#define UIA_NamePropertyId                 30005
#define UIA_HasKeyboardFocusPropertyId     30008
#define UIA_IsKeyboardFocusablePropertyId  30009
#define UIA_IsEnabledPropertyId            30010
#define UIA_AutomationIdPropertyId         30011
#define UIA_IsControlElementPropertyId     30016
#define UIA_IsContentElementPropertyId     30017
#define UIA_NativeWindowHandlePropertyId   30020
#define UIA_FrameworkIdPropertyId          30024
#define UIA_IsPasswordPropertyId           30019
#define UIA_HelpTextPropertyId             30013
#define UIA_LocalizedControlTypePropertyId 30004
#define UIA_OrientationPropertyId          30023
#define UIA_LevelPropertyId                30154
#define UIA_AriaPropertiesPropertyId       30102
#define UIA_AriaRolePropertyId             30101
#define UIA_RangeValueValuePropertyId      30047
#define UIA_RangeValueIsReadOnlyPropertyId 30048
#define UIA_RangeValueMinimumPropertyId    30049
#define UIA_RangeValueMaximumPropertyId    30050
#define UIA_RangeValueLargeChangePropertyId 30051
#define UIA_RangeValueSmallChangePropertyId 30052
#define UIA_ToggleToggleStatePropertyId    30086
#define UIA_ValueValuePropertyId           30045
#define UIA_ValueIsReadOnlyPropertyId      30046
#define UIA_SelectionItemIsSelectedPropertyId 30079

/* Notification kinds (UiaRaiseNotificationEvent in Win10 1709+). */
#define NotificationKind_ItemAdded       0
#define NotificationKind_ItemRemoved     1
#define NotificationKind_ActionCompleted 2
#define NotificationKind_ActionAborted   3
#define NotificationKind_Other           4

#define NotificationProcessing_ImportantAll       0
#define NotificationProcessing_ImportantMostRecent 1
#define NotificationProcessing_All                2
#define NotificationProcessing_MostRecent         3
#define NotificationProcessing_CurrentThenMostRecent 4

/* UIA event IDs we may raise. */
#define UIA_AutomationFocusChangedEventId 20005
#define UIA_StructureChangedEventId       20002

#define UIA_ButtonControlTypeId       50000
#define UIA_CheckBoxControlTypeId     50002
#define UIA_EditControlTypeId         50004
#define UIA_ImageControlTypeId        50006
#define UIA_MenuControlTypeId         50009
#define UIA_ProgressBarControlTypeId  50012
#define UIA_RadioButtonControlTypeId  50013
#define UIA_SliderControlTypeId       50015
#define UIA_TabItemControlTypeId      50019
#define UIA_TextControlTypeId         50020
#define UIA_TreeControlTypeId         50023
#define UIA_GroupControlTypeId        50026
#define UIA_DataGridControlTypeId     50028
#define UIA_DataItemControlTypeId     50029
#define UIA_PaneControlTypeId         50033

/* ── Wire-format constants (must match TaoA11ySnapshotSerializer) ─────────── */

#define SNAPSHOT_MAGIC   0xA110A11Au
#define SNAPSHOT_VERSION 4

enum NucleusA11yRole {
    A11Y_ROLE_UNKNOWN     = 0,
    A11Y_ROLE_GROUP       = 1,
    A11Y_ROLE_BUTTON      = 2,
    A11Y_ROLE_STATIC_TEXT = 3,
    A11Y_ROLE_CHECKBOX    = 4,
    A11Y_ROLE_RADIOBUTTON = 5,
    A11Y_ROLE_SWITCH      = 6,
    A11Y_ROLE_TEXT_FIELD  = 7,
    A11Y_ROLE_TEXT_AREA   = 8,
    A11Y_ROLE_SLIDER      = 9,
    A11Y_ROLE_PROGRESS    = 10,
    A11Y_ROLE_IMAGE       = 11,
    A11Y_ROLE_SCROLL_AREA = 12,
    A11Y_ROLE_HEADING     = 13,
    A11Y_ROLE_TAB         = 14,
    A11Y_ROLE_POPUP_MENU  = 15,
    A11Y_ROLE_TABLE       = 16,
    A11Y_ROLE_OUTLINE     = 17,
    A11Y_ROLE_ROW         = 18,
    A11Y_ROLE_CELL        = 19
};

#define A11Y_FLAG_IS_ELEMENT       (1u << 0)
#define A11Y_FLAG_ENABLED          (1u << 1)
#define A11Y_FLAG_FOCUSED          (1u << 2)
#define A11Y_FLAG_SELECTED         (1u << 3)
#define A11Y_FLAG_CHECKED          (1u << 4)
#define A11Y_FLAG_MIXED            (1u << 5)
#define A11Y_FLAG_HEADING          (1u << 6)
#define A11Y_FLAG_PASSWORD         (1u << 7)
#define A11Y_FLAG_MULTILINE        (1u << 8)
#define A11Y_FLAG_MODAL            (1u << 9)
#define A11Y_FLAG_LIVE_POLITE      (1u << 10)
#define A11Y_FLAG_LIVE_ASSERTIVE   (1u << 11)

#define A11Y_ACTION_CLICK          (1u << 0)
#define A11Y_ACTION_INCREMENT      (1u << 1)
#define A11Y_ACTION_DECREMENT      (1u << 2)
#define A11Y_ACTION_SET_TEXT       (1u << 3)
#define A11Y_ACTION_REQUEST_FOCUS  (1u << 4)
#define A11Y_ACTION_SCROLL_UP      (1u << 5)
#define A11Y_ACTION_SCROLL_DOWN    (1u << 6)
#define A11Y_ACTION_SCROLL_LEFT    (1u << 7)
#define A11Y_ACTION_SCROLL_RIGHT   (1u << 8)
#define A11Y_ACTION_DISMISS        (1u << 9)

/* ── Action callbacks (registered by lib.rs at JVM startup) ───────────────── */
/* The Rust crate registers each callback once at boot; the stubs forward into
 * the JVM upcall. Kept as function pointers (rather than direct externs) so
 * this DLL has no hard link-time dependency on nucleus_tao.dll. */
typedef void (*A11yInvokeActionFn)(int64_t hwnd, uint64_t node_id, uint16_t action);
typedef void (*A11ySetTextFn)(int64_t hwnd, uint64_t node_id, const char *utf8, int32_t len);
typedef void (*A11ySetSelectionFn)(int64_t hwnd, uint64_t node_id, int32_t start, int32_t end);
typedef void (*A11yScrollByFn)(int64_t hwnd, uint64_t node_id, float dx, float dy);
typedef void (*A11yCustomActionFn)(int64_t hwnd, uint64_t node_id, int32_t index);

static A11yInvokeActionFn  g_invokeActionCb  = NULL;
static A11ySetTextFn       g_setTextCb       = NULL;
static A11ySetSelectionFn  g_setSelectionCb  = NULL;
static A11yScrollByFn      g_scrollByCb      = NULL;
static A11yCustomActionFn  g_customActionCb  = NULL;

__declspec(dllexport) void nucleus_tao_a11y_register_action_callback_win(A11yInvokeActionFn cb) {
    g_invokeActionCb = cb;
}
__declspec(dllexport) void nucleus_tao_a11y_register_set_text_callback_win(A11ySetTextFn cb) {
    g_setTextCb = cb;
}
__declspec(dllexport) void nucleus_tao_a11y_register_set_selection_callback_win(A11ySetSelectionFn cb) {
    g_setSelectionCb = cb;
}
__declspec(dllexport) void nucleus_tao_a11y_register_scroll_by_callback_win(A11yScrollByFn cb) {
    g_scrollByCb = cb;
}
__declspec(dllexport) void nucleus_tao_a11y_register_custom_action_callback_win(A11yCustomActionFn cb) {
    g_customActionCb = cb;
}

static void nucleus_tao_a11y_invoke_action_win(int64_t hwnd, uint64_t node_id, uint16_t action) {
    if (g_invokeActionCb) g_invokeActionCb(hwnd, node_id, action);
}

/* ── Data model ───────────────────────────────────────────────────────────── */

typedef struct NucleusUiaElement NucleusUiaElement;
typedef struct NucleusUiaProjection NucleusUiaProjection;
typedef struct NucleusUiaRoot NucleusUiaRoot;

struct NucleusUiaElement {
    /* Vtable embedding — multiple-interface object. Each pattern is a tear-off
     * sub-object recoverable via offsetof (see ELEMENT_FROM_xxx macros). */
    IRawElementProviderSimpleVtbl   *lpSimpleVtbl;     /* primary */
    IRawElementProviderFragmentVtbl *lpFragmentVtbl;
    IInvokeProviderVtbl             *lpInvokeVtbl;
    IToggleProviderVtbl             *lpToggleVtbl;
    IValueProviderVtbl              *lpValueVtbl;
    IRangeValueProviderVtbl         *lpRangeValueVtbl;
    ISelectionItemProviderVtbl      *lpSelectionItemVtbl;
    IScrollProviderVtbl             *lpScrollVtbl;
    IExpandCollapseProviderVtbl     *lpExpandCollapseVtbl;

    LONG refCount;

    /* Data parsed from snapshot. */
    uint64_t nodeId;
    uint64_t parentId;
    uint16_t role;
    uint16_t flags;
    uint16_t actions;
    float frameX, frameY, frameW, frameH;       /* logical points, top-left, window-local */
    float minValue, maxValue, numericValue;
    uint32_t selectionStart, selectionEnd;
    float hScrollMax, hScrollValue;
    float vScrollMax, vScrollValue;
    wchar_t *label;     /* UTF-16 */
    wchar_t *valueStr;  /* UTF-16 */
    /* Custom action labels (Compose CustomAccessibilityAction.label). The
     * dispatch index is the position in this array. UIA has no native concept
     * of custom actions, so they're surfaced as Help text and exposed via
     * IRawElementProviderSimple's PROPERTYID_HelpText (concat of labels). */
    wchar_t **customActions;
    int customActionCount;

    /* Tree links — populated by projection during snapshot parse. */
    NucleusUiaProjection *projection;
    NucleusUiaElement *parent;          /* NULL for top-level (parent=root) */
    NucleusUiaElement **children;
    int childCount;
    /* Sibling order index in parent->children (or projection->roots). */
    int siblingIndex;
};

struct NucleusUiaRoot {
    IRawElementProviderSimpleVtbl       *lpSimpleVtbl;
    IRawElementProviderFragmentVtbl     *lpFragmentVtbl;
    IRawElementProviderFragmentRootVtbl *lpFragmentRootVtbl;

    LONG refCount;

    HWND hwnd;
    NucleusUiaProjection *projection;
};

struct NucleusUiaProjection {
    HWND hwnd;
    CRITICAL_SECTION lock;

    NucleusUiaRoot *root;            /* AddRef'd; lifetime-managed via attach/detach */

    NucleusUiaElement **byId;        /* hash table: nodeId → element */
    int byIdCapacity;
    int byIdCount;

    NucleusUiaElement **roots;       /* top-level (parentId==0) elements */
    int rootCount;

    /* Last node that carried the FOCUSED flag — tracked across pushes so we
     * can raise UIA_AutomationFocusChangedEventId on the new element. */
    uint64_t focusedNodeId;

    /* Activity tracking — last UIA query timestamp (GetTickCount64). */
    volatile LONG64 lastQueryTickMs;
    /* Set when an AX query landed during a skip window — observer must push next tick. */
    volatile LONG resyncRequested;
};

/* Forward decl of vtables (defined below). */
static IRawElementProviderSimpleVtbl       g_elementSimpleVtbl;
static IRawElementProviderFragmentVtbl     g_elementFragmentVtbl;
static IInvokeProviderVtbl                 g_elementInvokeVtbl;
static IToggleProviderVtbl                 g_elementToggleVtbl;
static IValueProviderVtbl                  g_elementValueVtbl;
static IRangeValueProviderVtbl             g_elementRangeValueVtbl;
static ISelectionItemProviderVtbl          g_elementSelectionItemVtbl;
static IScrollProviderVtbl                 g_elementScrollVtbl;
static IExpandCollapseProviderVtbl         g_elementExpandCollapseVtbl;
static IRawElementProviderSimpleVtbl       g_rootSimpleVtbl;
static IRawElementProviderFragmentVtbl     g_rootFragmentVtbl;
static IRawElementProviderFragmentRootVtbl g_rootFragmentRootVtbl;

/* ── Per-HWND projection registry ─────────────────────────────────────────── */

static const wchar_t *PROP_PROJECTION = L"NucleusTaoUiaProjection";

static NucleusUiaProjection *getProjection(HWND hwnd) {
    return (NucleusUiaProjection *)GetPropW(hwnd, PROP_PROJECTION);
}

/* ── Debug log (env-gated) ────────────────────────────────────────────────── */
static volatile LONG g_debugChecked = 0;
static int g_debugEnabled = 0;
static void debug_log(const char *fmt, ...) {
    if (InterlockedCompareExchange(&g_debugChecked, 1, 0) == 0) {
        char buf[8];
        DWORD n = GetEnvironmentVariableA("NUCLEUS_TAO_A11Y_DEBUG", buf, sizeof(buf));
        g_debugEnabled = (n > 0 && buf[0] == '1');
    }
    if (!g_debugEnabled) return;
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = wvsprintfA(line, fmt, ap);
    va_end(ap);
    HANDLE h = CreateFileA("C:\\temp\\nucleus_a11y.log",
                           FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h != INVALID_HANDLE_VALUE) {
        DWORD written;
        WriteFile(h, line, n, &written, NULL);
        WriteFile(h, "\r\n", 2, &written, NULL);
        CloseHandle(h);
    }
}

/* ── Allocation helpers ───────────────────────────────────────────────────── */

static void *xalloc(size_t size) {
    return HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, size);
}
static void *xrealloc(void *p, size_t size) {
    if (!p) return xalloc(size);
    return HeapReAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, p, size);
}
static void xfree(void *p) {
    if (p) HeapFree(GetProcessHeap(), 0, p);
}

static wchar_t *utf8_to_utf16_alloc(const uint8_t *bytes, int len) {
    if (len <= 0) {
        wchar_t *empty = (wchar_t *)xalloc(sizeof(wchar_t));
        if (empty) empty[0] = 0;
        return empty;
    }
    int needed = MultiByteToWideChar(CP_UTF8, 0, (const char *)bytes, len, NULL, 0);
    if (needed <= 0) {
        wchar_t *empty = (wchar_t *)xalloc(sizeof(wchar_t));
        if (empty) empty[0] = 0;
        return empty;
    }
    wchar_t *out = (wchar_t *)xalloc(sizeof(wchar_t) * (needed + 1));
    if (!out) return NULL;
    MultiByteToWideChar(CP_UTF8, 0, (const char *)bytes, len, out, needed);
    out[needed] = 0;
    return out;
}

/* ── Element / Root constructors ──────────────────────────────────────────── */

static NucleusUiaElement *element_new(void) {
    NucleusUiaElement *el = (NucleusUiaElement *)xalloc(sizeof(NucleusUiaElement));
    if (!el) return NULL;
    el->lpSimpleVtbl         = &g_elementSimpleVtbl;
    el->lpFragmentVtbl       = &g_elementFragmentVtbl;
    el->lpInvokeVtbl         = &g_elementInvokeVtbl;
    el->lpToggleVtbl         = &g_elementToggleVtbl;
    el->lpValueVtbl          = &g_elementValueVtbl;
    el->lpRangeValueVtbl     = &g_elementRangeValueVtbl;
    el->lpSelectionItemVtbl  = &g_elementSelectionItemVtbl;
    el->lpScrollVtbl         = &g_elementScrollVtbl;
    el->lpExpandCollapseVtbl = &g_elementExpandCollapseVtbl;
    el->refCount = 1;
    return el;
}

static void element_release_data(NucleusUiaElement *el) {
    if (!el) return;
    xfree(el->label);
    xfree(el->valueStr);
    xfree(el->children);
    if (el->customActions) {
        for (int i = 0; i < el->customActionCount; i++) xfree(el->customActions[i]);
        xfree(el->customActions);
    }
    el->label = NULL;
    el->valueStr = NULL;
    el->children = NULL;
    el->childCount = 0;
    el->customActions = NULL;
    el->customActionCount = 0;
}

static NucleusUiaRoot *root_new(HWND hwnd, NucleusUiaProjection *proj) {
    NucleusUiaRoot *r = (NucleusUiaRoot *)xalloc(sizeof(NucleusUiaRoot));
    if (!r) return NULL;
    r->lpSimpleVtbl       = &g_rootSimpleVtbl;
    r->lpFragmentVtbl     = &g_rootFragmentVtbl;
    r->lpFragmentRootVtbl = &g_rootFragmentRootVtbl;
    r->refCount = 1;
    r->hwnd = hwnd;
    r->projection = proj;
    return r;
}

/* ── Hash table by nodeId ─────────────────────────────────────────────────── */

static unsigned hash_u64(uint64_t k) {
    k ^= k >> 33; k *= 0xff51afd7ed558ccdULL;
    k ^= k >> 33; k *= 0xc4ceb9fe1a85ec53ULL;
    k ^= k >> 33;
    return (unsigned)k;
}

static void byid_grow(NucleusUiaProjection *p, int newCap) {
    NucleusUiaElement **old = p->byId;
    int oldCap = p->byIdCapacity;
    NucleusUiaElement **nu = (NucleusUiaElement **)xalloc(sizeof(*nu) * newCap);
    if (!nu) return;
    for (int i = 0; i < oldCap; i++) {
        NucleusUiaElement *e = old[i];
        if (!e) continue;
        unsigned h = hash_u64(e->nodeId) & (newCap - 1);
        while (nu[h]) h = (h + 1) & (newCap - 1);
        nu[h] = e;
    }
    p->byId = nu;
    p->byIdCapacity = newCap;
    xfree(old);
}

static void byid_put(NucleusUiaProjection *p, NucleusUiaElement *el) {
    if (p->byIdCapacity == 0) byid_grow(p, 64);
    if ((p->byIdCount + 1) * 2 > p->byIdCapacity) byid_grow(p, p->byIdCapacity * 2);
    unsigned h = hash_u64(el->nodeId) & (p->byIdCapacity - 1);
    while (p->byId[h] && p->byId[h]->nodeId != el->nodeId) {
        h = (h + 1) & (p->byIdCapacity - 1);
    }
    if (!p->byId[h]) p->byIdCount++;
    p->byId[h] = el;
}

static NucleusUiaElement *byid_get(NucleusUiaProjection *p, uint64_t nodeId) {
    if (p->byIdCapacity == 0) return NULL;
    unsigned h = hash_u64(nodeId) & (p->byIdCapacity - 1);
    for (int probes = 0; probes < p->byIdCapacity; probes++) {
        NucleusUiaElement *e = p->byId[h];
        if (!e) return NULL;
        if (e->nodeId == nodeId) return e;
        h = (h + 1) & (p->byIdCapacity - 1);
    }
    return NULL;
}

static void byid_clear(NucleusUiaProjection *p) {
    if (!p->byId) return;
    for (int i = 0; i < p->byIdCapacity; i++) {
        NucleusUiaElement *e = p->byId[i];
        if (!e) continue;
        element_release_data(e);
        IUnknown_Release((IUnknown *)e);
    }
    xfree(p->byId);
    p->byId = NULL;
    p->byIdCapacity = 0;
    p->byIdCount = 0;
}

/* Frees an isolated byId map (not attached to a projection). Used for the old
 * map after diffing, once events have been raised against the new tree. */
static void byid_free_table(NucleusUiaElement **table, int cap) {
    if (!table) return;
    for (int i = 0; i < cap; i++) {
        NucleusUiaElement *e = table[i];
        if (!e) continue;
        element_release_data(e);
        IUnknown_Release((IUnknown *)e);
    }
    xfree(table);
}

static NucleusUiaElement *byid_lookup_in(
    NucleusUiaElement **table, int cap, uint64_t nodeId)
{
    if (!table || cap == 0) return NULL;
    unsigned h = hash_u64(nodeId) & (cap - 1);
    for (int probes = 0; probes < cap; probes++) {
        NucleusUiaElement *e = table[h];
        if (!e) return NULL;
        if (e->nodeId == nodeId) return e;
        h = (h + 1) & (cap - 1);
    }
    return NULL;
}

static int wstr_equals(const wchar_t *a, const wchar_t *b) {
    if (a == b) return 1;
    if (!a || !b) return 0;
    while (*a && *b && *a == *b) { a++; b++; }
    return *a == *b;
}

/* ── Activity tracking ────────────────────────────────────────────────────── */

#define ACTIVE_WINDOW_MS (5 * 60 * 1000)  /* 5 minutes — mirrors macOS */

static void note_a11y_query(NucleusUiaProjection *p) {
    if (!p) return;
    LONG64 now = (LONG64)GetTickCount64();
    InterlockedExchange64(&p->lastQueryTickMs, now);
    InterlockedExchange(&p->resyncRequested, 1);
}

/* ── Wire-format parser ───────────────────────────────────────────────────── */

#define READ_OR_RETURN(dst, n) do { \
        if (offset + (n) > len) return FALSE; \
        memcpy((dst), bytes + offset, (n)); \
        offset += (n); \
    } while (0)

static BOOL apply_snapshot(NucleusUiaProjection *proj, const uint8_t *bytes, size_t len) {
    if (!proj || !bytes) return FALSE;
    debug_log("apply_snapshot: len=%u", (unsigned)len);
    size_t offset = 0;
    uint32_t magic = 0;
    READ_OR_RETURN(&magic, 4);
    if (magic != SNAPSHOT_MAGIC) return FALSE;
    uint16_t version = 0, reserved = 0;
    READ_OR_RETURN(&version, 2);
    READ_OR_RETURN(&reserved, 2);
    if (version != SNAPSHOT_VERSION) return FALSE;
    uint32_t nodeCount = 0;
    READ_OR_RETURN(&nodeCount, 4);

    EnterCriticalSection(&proj->lock);

    /* Stash the previous tree so we can diff once the new one is built and
     * fire UIA events on what actually changed. The old map is freed at the
     * end (after events have been raised against the new providers). */
    NucleusUiaElement **oldById = proj->byId;
    int oldByIdCapacity = proj->byIdCapacity;
    uint64_t prevFocusedNodeId = proj->focusedNodeId;
    proj->byId = NULL;
    proj->byIdCapacity = 0;
    proj->byIdCount = 0;
    xfree(proj->roots);
    proj->roots = NULL;
    proj->rootCount = 0;

    /* First pass: allocate elements + populate scalar fields. */
    NucleusUiaElement **ordered =
        (NucleusUiaElement **)xalloc(sizeof(*ordered) * (nodeCount > 0 ? nodeCount : 1));
    if (!ordered) { LeaveCriticalSection(&proj->lock); return FALSE; }
    /* Captured fields from recycled elements, used for diffing after the new
     * data is in place. `present=0` means "no previous element / new node". */
    struct PriorSnapshot {
        int present;
        uint16_t flags;
        float numericValue;
        wchar_t *label;     /* owned; freed after diff */
        wchar_t *valueStr;  /* owned; freed after diff */
    };
    struct PriorSnapshot *priorSnapshots = (struct PriorSnapshot *)
        xalloc(sizeof(struct PriorSnapshot) * (nodeCount > 0 ? nodeCount : 1));
    if (!priorSnapshots) { xfree(ordered); LeaveCriticalSection(&proj->lock); return FALSE; }

    BOOL ok = TRUE;
    for (uint32_t i = 0; i < nodeCount; i++) {
        uint64_t nodeId = 0, parentId = 0;
        uint16_t role = 0, flags = 0, actions = 0, reserved2 = 0;
        float frame[4] = {0};
        float range[3] = {0};
        uint32_t selStart = 0, selEnd = 0;
        float scroll[4] = {0};
        uint16_t labelLen = 0, valueLen = 0;
        if (offset + 8 > len) { ok = FALSE; break; }
        memcpy(&nodeId, bytes + offset, 8); offset += 8;
        if (offset + 8 > len) { ok = FALSE; break; }
        memcpy(&parentId, bytes + offset, 8); offset += 8;
        if (offset + 8 > len) { ok = FALSE; break; }
        memcpy(&role, bytes + offset, 2); offset += 2;
        memcpy(&flags, bytes + offset, 2); offset += 2;
        memcpy(&actions, bytes + offset, 2); offset += 2;
        memcpy(&reserved2, bytes + offset, 2); offset += 2;
        if (offset + sizeof(frame) > len) { ok = FALSE; break; }
        memcpy(frame, bytes + offset, sizeof(frame)); offset += sizeof(frame);
        if (offset + sizeof(range) > len) { ok = FALSE; break; }
        memcpy(range, bytes + offset, sizeof(range)); offset += sizeof(range);
        if (offset + 8 > len) { ok = FALSE; break; }
        memcpy(&selStart, bytes + offset, 4); offset += 4;
        memcpy(&selEnd, bytes + offset, 4); offset += 4;
        if (offset + sizeof(scroll) > len) { ok = FALSE; break; }
        memcpy(scroll, bytes + offset, sizeof(scroll)); offset += sizeof(scroll);
        if (offset + 2 > len) { ok = FALSE; break; }
        memcpy(&labelLen, bytes + offset, 2); offset += 2;
        if (offset + labelLen > len) { ok = FALSE; break; }
        wchar_t *label = utf8_to_utf16_alloc(bytes + offset, labelLen);
        offset += labelLen;
        if (offset + 2 > len) { xfree(label); ok = FALSE; break; }
        memcpy(&valueLen, bytes + offset, 2); offset += 2;
        if (offset + valueLen > len) { xfree(label); ok = FALSE; break; }
        wchar_t *valueStr = utf8_to_utf16_alloc(bytes + offset, valueLen);
        offset += valueLen;
        uint16_t customCount = 0;
        if (offset + 2 > len) { xfree(label); xfree(valueStr); ok = FALSE; break; }
        memcpy(&customCount, bytes + offset, 2); offset += 2;
        wchar_t **customActions = NULL;
        if (customCount > 0) {
            customActions = (wchar_t **)xalloc(sizeof(wchar_t *) * customCount);
        }
        BOOL caOk = TRUE;
        for (uint16_t k = 0; k < customCount; k++) {
            uint16_t nameLen = 0;
            if (offset + 2 > len) { caOk = FALSE; break; }
            memcpy(&nameLen, bytes + offset, 2); offset += 2;
            if (offset + nameLen > len) { caOk = FALSE; break; }
            customActions[k] = utf8_to_utf16_alloc(bytes + offset, nameLen);
            offset += nameLen;
        }
        if (!caOk) {
            if (customActions) {
                for (int k = 0; k < customCount; k++) xfree(customActions[k]);
                xfree(customActions);
            }
            xfree(label); xfree(valueStr); ok = FALSE; break;
        }

        /* Identity reuse: if the old map carries an element with the same
         * nodeId, reuse the COM object so UIA sees a stable identity across
         * snapshots. Reusing the object means events keyed off it are matched
         * by UIA's client-side proxy cache (which fingerprints by IUnknown
         * identity in addition to runtime ID). */
        NucleusUiaElement *recycled = byid_lookup_in(oldById, oldByIdCapacity, nodeId);
        NucleusUiaElement *el;
        if (recycled) {
            /* Capture old fields BEFORE element_release_data nukes them. The
             * captured data feeds the diff loop after this pass completes. */
            priorSnapshots[i].present = 1;
            priorSnapshots[i].flags = recycled->flags;
            priorSnapshots[i].numericValue = recycled->numericValue;
            priorSnapshots[i].label = recycled->label;       /* take ownership */
            priorSnapshots[i].valueStr = recycled->valueStr; /* take ownership */
            recycled->label = NULL;       /* prevent release_data from freeing */
            recycled->valueStr = NULL;
            /* Pop the recycled element out of the old map by zeroing its
             * slot — this prevents byid_free_table from releasing it. */
            unsigned h = hash_u64(nodeId) & (oldByIdCapacity - 1);
            for (int probes = 0; probes < oldByIdCapacity; probes++) {
                if (oldById[h] == recycled) { oldById[h] = NULL; break; }
                h = (h + 1) & (oldByIdCapacity - 1);
            }
            element_release_data(recycled);
            recycled->parent = NULL;
            recycled->children = NULL;
            recycled->childCount = 0;
            recycled->siblingIndex = 0;
            el = recycled;
        } else {
            priorSnapshots[i].present = 0;
            el = element_new();
            if (!el) { xfree(label); xfree(valueStr); ok = FALSE; break; }
        }
        el->nodeId = nodeId;
        el->parentId = parentId;
        el->role = role;
        el->flags = flags;
        el->actions = actions;
        el->frameX = frame[0]; el->frameY = frame[1];
        el->frameW = frame[2]; el->frameH = frame[3];
        el->minValue = range[0]; el->maxValue = range[1]; el->numericValue = range[2];
        el->selectionStart = selStart; el->selectionEnd = selEnd;
        el->hScrollMax = scroll[0]; el->hScrollValue = scroll[1];
        el->vScrollMax = scroll[2]; el->vScrollValue = scroll[3];
        el->label = label;
        el->valueStr = valueStr;
        el->customActions = customActions;
        el->customActionCount = customCount;
        el->projection = proj;

        ordered[i] = el;
        byid_put(proj, el);
    }

    if (!ok) {
        for (uint32_t i = 0; i < nodeCount; i++) {
            if (ordered[i]) {
                element_release_data(ordered[i]);
                IUnknown_Release((IUnknown *)ordered[i]);
            }
            xfree(priorSnapshots[i].label);
            xfree(priorSnapshots[i].valueStr);
        }
        xfree(ordered);
        xfree(priorSnapshots);
        byid_clear(proj);
        /* Restore old state on parse failure — better to keep the previous
         * tree visible than tear everything down. */
        byid_free_table(oldById, oldByIdCapacity);
        LeaveCriticalSection(&proj->lock);
        return FALSE;
    }

    /* Second pass: link parents + collect roots. Kotlin emits parents before
     * children so single-pass works. */
    int rootCap = 8, rootCnt = 0;
    NucleusUiaElement **roots = (NucleusUiaElement **)xalloc(sizeof(*roots) * rootCap);

    /* First, count children per parent so we can allocate exactly. */
    for (uint32_t i = 0; i < nodeCount; i++) {
        NucleusUiaElement *el = ordered[i];
        if (el->parentId == 0) {
            if (rootCnt >= rootCap) {
                rootCap *= 2;
                roots = (NucleusUiaElement **)xrealloc(roots, sizeof(*roots) * rootCap);
            }
            el->siblingIndex = rootCnt;
            roots[rootCnt++] = el;
        } else {
            NucleusUiaElement *parent = byid_get(proj, el->parentId);
            if (parent) {
                el->parent = parent;
                parent->childCount++;
            } else {
                /* Orphan — promote to root. */
                if (rootCnt >= rootCap) {
                    rootCap *= 2;
                    roots = (NucleusUiaElement **)xrealloc(roots, sizeof(*roots) * rootCap);
                }
                el->siblingIndex = rootCnt;
                roots[rootCnt++] = el;
            }
        }
    }
    /* Allocate child arrays + reset counters for fill pass. */
    for (uint32_t i = 0; i < nodeCount; i++) {
        NucleusUiaElement *el = ordered[i];
        if (el->childCount > 0) {
            el->children = (NucleusUiaElement **)xalloc(sizeof(*el->children) * el->childCount);
            el->childCount = 0;
        }
    }
    for (uint32_t i = 0; i < nodeCount; i++) {
        NucleusUiaElement *el = ordered[i];
        if (el->parent) {
            el->siblingIndex = el->parent->childCount;
            el->parent->children[el->parent->childCount++] = el;
        }
    }

    proj->roots = roots;
    proj->rootCount = rootCnt;

    /* ── Diff against old snapshot and queue UIA events ───────────────────
     * Identity is keyed on nodeId. For each new node that existed before,
     * compare the relevant fields and fire UiaRaiseAutomationPropertyChanged
     * accordingly. Focus-changed and live-region notifications are also
     * collected here so we can fire them after committing.
     */
    typedef struct PendingEvent {
        NucleusUiaElement *target;
        int kind;          /* 0=property, 1=live region, 2=focus */
        int propertyId;    /* for kind=0 */
        VARIANT oldValue;  /* for kind=0 */
        VARIANT newValue;  /* for kind=0 */
        wchar_t *announceText; /* for kind=1; SysAllocString'd, freed after raise */
        int announcePriority;  /* for kind=1: 1=polite, 2=assertive */
    } PendingEvent;
    PendingEvent *events = NULL;
    int eventCap = 0, eventCount = 0;
#define APPEND_EVENT() do { \
        if (eventCount >= eventCap) { \
            eventCap = eventCap == 0 ? 16 : eventCap * 2; \
            events = (PendingEvent *)xrealloc(events, sizeof(PendingEvent) * eventCap); \
        } \
    } while (0)

    /* Use the prior snapshots (captured before overwrite) for diffing. The
     * `priorSnapshots` array is populated from the recycle path during the
     * first pass — see element creation block above where we capture old
     * fields into priorSnapshots[i] before overwriting. */
    uint64_t newFocusedNodeId = 0;
    for (uint32_t i = 0; i < nodeCount; i++) {
        NucleusUiaElement *neu = ordered[i];
        if (neu->flags & A11Y_FLAG_FOCUSED) newFocusedNodeId = neu->nodeId;
        if (!priorSnapshots[i].present) continue;

        struct PriorSnapshot *old = &priorSnapshots[i];
        BOOL labelChanged = !wstr_equals(old->label, neu->label);
        BOOL valueChanged = !wstr_equals(old->valueStr, neu->valueStr);
        BOOL toggleChanged =
            (old->flags & (A11Y_FLAG_CHECKED | A11Y_FLAG_MIXED)) !=
            (neu->flags & (A11Y_FLAG_CHECKED | A11Y_FLAG_MIXED));
        BOOL selectedChanged =
            (old->flags & A11Y_FLAG_SELECTED) != (neu->flags & A11Y_FLAG_SELECTED);
        BOOL numericChanged = old->numericValue != neu->numericValue;

        if (labelChanged) {
            APPEND_EVENT();
            PendingEvent *e = &events[eventCount++];
            ZeroMemory(e, sizeof(*e));
            e->target = neu;
            e->kind = 0;
            e->propertyId = UIA_NamePropertyId;
            VariantInit(&e->oldValue);
            VariantInit(&e->newValue);
            e->oldValue.vt = VT_BSTR;
            e->oldValue.bstrVal = SysAllocString(old->label ? old->label : L"");
            e->newValue.vt = VT_BSTR;
            e->newValue.bstrVal = SysAllocString(neu->label ? neu->label : L"");
        }
        if (valueChanged && has_value(neu)) {
            APPEND_EVENT();
            PendingEvent *e = &events[eventCount++];
            ZeroMemory(e, sizeof(*e));
            e->target = neu;
            e->kind = 0;
            e->propertyId = UIA_ValueValuePropertyId;
            VariantInit(&e->oldValue);
            VariantInit(&e->newValue);
            e->oldValue.vt = VT_BSTR;
            e->oldValue.bstrVal = SysAllocString(old->valueStr ? old->valueStr : L"");
            e->newValue.vt = VT_BSTR;
            e->newValue.bstrVal = SysAllocString(neu->valueStr ? neu->valueStr : L"");
        }
        if (toggleChanged && has_toggle(neu)) {
            APPEND_EVENT();
            PendingEvent *e = &events[eventCount++];
            ZeroMemory(e, sizeof(*e));
            e->target = neu;
            e->kind = 0;
            e->propertyId = UIA_ToggleToggleStatePropertyId;
            VariantInit(&e->oldValue);
            VariantInit(&e->newValue);
            e->oldValue.vt = VT_I4;
            e->oldValue.lVal = (old->flags & A11Y_FLAG_MIXED) ? 2
                              : (old->flags & A11Y_FLAG_CHECKED) ? 1 : 0;
            e->newValue.vt = VT_I4;
            e->newValue.lVal = (neu->flags & A11Y_FLAG_MIXED) ? 2
                              : (neu->flags & A11Y_FLAG_CHECKED) ? 1 : 0;
        }
        if (numericChanged && has_range_value(neu)) {
            APPEND_EVENT();
            PendingEvent *e = &events[eventCount++];
            ZeroMemory(e, sizeof(*e));
            e->target = neu;
            e->kind = 0;
            e->propertyId = UIA_RangeValueValuePropertyId;
            VariantInit(&e->oldValue);
            VariantInit(&e->newValue);
            e->oldValue.vt = VT_R8;
            e->oldValue.dblVal = (double)old->numericValue;
            e->newValue.vt = VT_R8;
            e->newValue.dblVal = (double)neu->numericValue;
        }
        if (selectedChanged && has_selection_item(neu)) {
            APPEND_EVENT();
            PendingEvent *e = &events[eventCount++];
            ZeroMemory(e, sizeof(*e));
            e->target = neu;
            e->kind = 0;
            e->propertyId = UIA_SelectionItemIsSelectedPropertyId;
            VariantInit(&e->oldValue);
            VariantInit(&e->newValue);
            e->oldValue.vt = VT_BOOL;
            e->oldValue.boolVal = (old->flags & A11Y_FLAG_SELECTED) ? VARIANT_TRUE : VARIANT_FALSE;
            e->newValue.vt = VT_BOOL;
            e->newValue.boolVal = (neu->flags & A11Y_FLAG_SELECTED) ? VARIANT_TRUE : VARIANT_FALSE;
        }
        /* Live region: fire a NotificationEvent (Win10 1709+) when the
         * announceable text on a live region changed. Prefer value over
         * label since that's what carries the dynamic part (e.g. counters).
         */
        if ((labelChanged || valueChanged) &&
            (neu->flags & (A11Y_FLAG_LIVE_POLITE | A11Y_FLAG_LIVE_ASSERTIVE))) {
            const wchar_t *text = (neu->valueStr && neu->valueStr[0]) ? neu->valueStr : neu->label;
            if (text && text[0]) {
                APPEND_EVENT();
                PendingEvent *e = &events[eventCount++];
                ZeroMemory(e, sizeof(*e));
                e->target = neu;
                e->kind = 1;
                int len = lstrlenW(text);
                e->announceText = (wchar_t *)xalloc(sizeof(wchar_t) * (len + 1));
                if (e->announceText) {
                    for (int j = 0; j <= len; j++) e->announceText[j] = text[j];
                }
                e->announcePriority = (neu->flags & A11Y_FLAG_LIVE_ASSERTIVE) ? 2 : 1;
            }
        }
    }
    proj->focusedNodeId = newFocusedNodeId;

    /* Focus change event: only when the focused node id actually flipped. */
    NucleusUiaElement *focusTarget = NULL;
    if (newFocusedNodeId != prevFocusedNodeId && newFocusedNodeId != 0) {
        focusTarget = byid_get(proj, newFocusedNodeId);
    }

    xfree(ordered);
    LeaveCriticalSection(&proj->lock);

    BOOL clientsListening = UiaClientsAreListening();
    debug_log("apply_snapshot: nodeCount=%u newFocused=%I64u prevFocused=%I64u eventCount=%d listening=%d",
              nodeCount, newFocusedNodeId, prevFocusedNodeId, eventCount, clientsListening ? 1 : 0);

    /* ── Defer event raises to the WndProc ────────────────────────────────
     * Queue every event and PostMessage(WM_FLUSH) — UIA's post-raise
     * callbacks (Navigate/scope resolution back into our STA) only succeed
     * once the JNI call has returned and the Tao message pump resumes.
     * Raising inline here, while the pump is blocked inside JNI, causes
     * silent event drops. */
    HWND eventHwnd = proj->hwnd;
    for (int i = 0; i < eventCount; i++) {
        PendingEvent *e = &events[i];
        DeferredEvent *de = (DeferredEvent *)xalloc(sizeof(DeferredEvent));
        if (!de) {
            if (e->kind == 0) { VariantClear(&e->oldValue); VariantClear(&e->newValue); }
            xfree(e->announceText);
            continue;
        }
        de->target = e->target;
        InterlockedIncrement(&e->target->refCount);  /* hold the element until flush */
        de->kind = e->kind;
        if (e->kind == 0) {
            de->propertyId = e->propertyId;
            de->oldValue = e->oldValue;     /* transfer ownership */
            de->newValue = e->newValue;
        } else if (e->kind == 1) {
            de->announceText = e->announceText
                ? SysAllocString(e->announceText) : NULL;
            de->announcePriority = e->announcePriority;
            xfree(e->announceText);
        }
        defer_enqueue(eventHwnd, de);
    }
    xfree(events);

    if (focusTarget) {
        DeferredEvent *de = (DeferredEvent *)xalloc(sizeof(DeferredEvent));
        if (de) {
            de->target = focusTarget;
            InterlockedIncrement(&focusTarget->refCount);
            de->kind = 2;
            defer_enqueue(eventHwnd, de);
        }
    }

    if (proj->root) {
        DeferredEvent *de = (DeferredEvent *)xalloc(sizeof(DeferredEvent));
        if (de) {
            de->target = (NucleusUiaElement *)proj->root;  /* root has same vtbl prefix */
            InterlockedIncrement(&proj->root->refCount);
            de->kind = 3;
            defer_enqueue(eventHwnd, de);
        }
    }

    /* Free old map last — events have been delivered, runtime IDs of the new
     * elements have been used for routing. */
    byid_free_table(oldById, oldByIdCapacity);

    /* Free the per-node prior-snapshot strings (we took ownership when
     * recycling). */
    for (uint32_t i = 0; i < nodeCount; i++) {
        xfree(priorSnapshots[i].label);
        xfree(priorSnapshots[i].valueStr);
    }
    xfree(priorSnapshots);

    return TRUE;
#undef APPEND_EVENT
}

/* ── Element vtable: IRawElementProviderSimple ────────────────────────────── */

#define ELEMENT_FROM_SIMPLE(p)          ((NucleusUiaElement *)(p))
#define ELEMENT_FROM_FRAGMENT(p)        ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpFragmentVtbl)))
#define ELEMENT_FROM_INVOKE(p)          ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpInvokeVtbl)))
#define ELEMENT_FROM_TOGGLE(p)          ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpToggleVtbl)))
#define ELEMENT_FROM_VALUE(p)           ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpValueVtbl)))
#define ELEMENT_FROM_RANGEVALUE(p)      ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpRangeValueVtbl)))
#define ELEMENT_FROM_SELECTIONITEM(p)   ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpSelectionItemVtbl)))
#define ELEMENT_FROM_SCROLL(p)          ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpScrollVtbl)))
#define ELEMENT_FROM_EXPANDCOLLAPSE(p)  ((NucleusUiaElement *)((char *)(p) - offsetof(NucleusUiaElement, lpExpandCollapseVtbl)))

/* Pattern eligibility helpers — encapsulate the role/flag/action gates so
 * QueryInterface and GetPatternProvider stay in sync. */
static int has_toggle(const NucleusUiaElement *el) {
    return el->role == A11Y_ROLE_CHECKBOX || el->role == A11Y_ROLE_SWITCH;
}
static int has_value(const NucleusUiaElement *el) {
    return el->role == A11Y_ROLE_TEXT_FIELD || el->role == A11Y_ROLE_TEXT_AREA;
}
static int has_range_value(const NucleusUiaElement *el) {
    return el->role == A11Y_ROLE_SLIDER || el->role == A11Y_ROLE_PROGRESS;
}
static int has_selection_item(const NucleusUiaElement *el) {
    return el->role == A11Y_ROLE_RADIOBUTTON || el->role == A11Y_ROLE_TAB;
}
static int has_scroll(const NucleusUiaElement *el) {
    return (el->actions & (A11Y_ACTION_SCROLL_UP | A11Y_ACTION_SCROLL_DOWN |
                           A11Y_ACTION_SCROLL_LEFT | A11Y_ACTION_SCROLL_RIGHT)) != 0;
}
static int has_expand_collapse(const NucleusUiaElement *el) {
    return el->role == A11Y_ROLE_POPUP_MENU;
}

static HRESULT STDMETHODCALLTYPE Element_Simple_QueryInterface(
    IRawElementProviderSimple *This, REFIID riid, void **ppv)
{
    NucleusUiaElement *el = ELEMENT_FROM_SIMPLE(This);
    if (!ppv) return E_POINTER;
    *ppv = NULL;
    if (IsEqualIID(riid, &IID_IUnknown) ||
        IsEqualIID(riid, &IID_IRawElementProviderSimple)) {
        *ppv = &el->lpSimpleVtbl;
    } else if (IsEqualIID(riid, &IID_IRawElementProviderFragment)) {
        *ppv = &el->lpFragmentVtbl;
    } else if (IsEqualIID(riid, &IID_IInvokeProvider) &&
               (el->actions & A11Y_ACTION_CLICK)) {
        *ppv = &el->lpInvokeVtbl;
    } else if (IsEqualIID(riid, &IID_IToggleProvider) && has_toggle(el)) {
        *ppv = &el->lpToggleVtbl;
    } else if (IsEqualIID(riid, &IID_IValueProvider) && has_value(el)) {
        *ppv = &el->lpValueVtbl;
    } else if (IsEqualIID(riid, &IID_IRangeValueProvider) && has_range_value(el)) {
        *ppv = &el->lpRangeValueVtbl;
    } else if (IsEqualIID(riid, &IID_ISelectionItemProvider) && has_selection_item(el)) {
        *ppv = &el->lpSelectionItemVtbl;
    } else if (IsEqualIID(riid, &IID_IScrollProvider) && has_scroll(el)) {
        *ppv = &el->lpScrollVtbl;
    } else if (IsEqualIID(riid, &IID_IExpandCollapseProvider) && has_expand_collapse(el)) {
        *ppv = &el->lpExpandCollapseVtbl;
    } else {
        return E_NOINTERFACE;
    }
    InterlockedIncrement(&el->refCount);
    return S_OK;
}

static ULONG STDMETHODCALLTYPE Element_Simple_AddRef(IRawElementProviderSimple *This) {
    NucleusUiaElement *el = ELEMENT_FROM_SIMPLE(This);
    return (ULONG)InterlockedIncrement(&el->refCount);
}

static ULONG STDMETHODCALLTYPE Element_Simple_Release(IRawElementProviderSimple *This) {
    NucleusUiaElement *el = ELEMENT_FROM_SIMPLE(This);
    LONG c = InterlockedDecrement(&el->refCount);
    if (c == 0) {
        element_release_data(el);
        xfree(el);
    }
    return (ULONG)c;
}

static HRESULT STDMETHODCALLTYPE Element_Simple_get_ProviderOptions(
    IRawElementProviderSimple *This, enum ProviderOptions *pRetVal)
{
    (void)This;
    if (!pRetVal) return E_POINTER;
    /* ServerSideProvider | UseComThreading. The latter is critical: our
     * provider lives on a JVM-owned STA thread (java.desktop / AWT init
     * already CoInitialized it), so UIA must marshal cross-apartment calls
     * through the COM proxy/stub system rather than calling us directly
     * from RPC pool threads. Without this flag, UIA event delivery
     * silently drops because the post-raise callbacks (Navigate/scope
     * resolution) hit a non-marshaled provider on the wrong thread. */
    *pRetVal = ProviderOptions_ServerSideProvider | ProviderOptions_UseComThreading;
    return S_OK;
}

static int role_to_uia_control_type(uint16_t role, uint16_t flags) {
    switch (role) {
        case A11Y_ROLE_BUTTON:      return UIA_ButtonControlTypeId;
        case A11Y_ROLE_STATIC_TEXT: return UIA_TextControlTypeId;
        case A11Y_ROLE_HEADING:     return UIA_TextControlTypeId;
        case A11Y_ROLE_CHECKBOX:    return UIA_CheckBoxControlTypeId;
        case A11Y_ROLE_SWITCH:      return UIA_CheckBoxControlTypeId;
        case A11Y_ROLE_RADIOBUTTON: return UIA_RadioButtonControlTypeId;
        case A11Y_ROLE_TEXT_FIELD:  return UIA_EditControlTypeId;
        case A11Y_ROLE_TEXT_AREA:   return UIA_EditControlTypeId;
        case A11Y_ROLE_SLIDER:      return UIA_SliderControlTypeId;
        case A11Y_ROLE_PROGRESS:    return UIA_ProgressBarControlTypeId;
        case A11Y_ROLE_IMAGE:       return UIA_ImageControlTypeId;
        case A11Y_ROLE_SCROLL_AREA: return UIA_PaneControlTypeId;
        case A11Y_ROLE_TAB:         return UIA_TabItemControlTypeId;
        case A11Y_ROLE_POPUP_MENU:  return UIA_MenuControlTypeId;
        case A11Y_ROLE_TABLE:       return UIA_DataGridControlTypeId;
        case A11Y_ROLE_OUTLINE:     return UIA_TreeControlTypeId;
        case A11Y_ROLE_ROW:         return UIA_DataItemControlTypeId;
        case A11Y_ROLE_CELL:        return UIA_DataItemControlTypeId;
        case A11Y_ROLE_GROUP:
        default:                    return UIA_GroupControlTypeId;
    }
    (void)flags;
}

static HRESULT STDMETHODCALLTYPE Element_Simple_GetPatternProvider(
    IRawElementProviderSimple *This, PATTERNID patternId, IUnknown **pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SIMPLE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    void *vtbl = NULL;
    switch (patternId) {
        case UIA_InvokePatternId:
            if (el->actions & A11Y_ACTION_CLICK) vtbl = &el->lpInvokeVtbl;
            break;
        case UIA_TogglePatternId:
            if (has_toggle(el)) vtbl = &el->lpToggleVtbl;
            break;
        case UIA_ValuePatternId:
            if (has_value(el)) vtbl = &el->lpValueVtbl;
            break;
        case UIA_RangeValuePatternId:
            if (has_range_value(el)) vtbl = &el->lpRangeValueVtbl;
            break;
        case UIA_SelectionItemPatternId:
            if (has_selection_item(el)) vtbl = &el->lpSelectionItemVtbl;
            break;
        case UIA_ScrollPatternId:
            if (has_scroll(el)) vtbl = &el->lpScrollVtbl;
            break;
        case UIA_ExpandCollapsePatternId:
            if (has_expand_collapse(el)) vtbl = &el->lpExpandCollapseVtbl;
            break;
        default:
            break;
    }
    if (vtbl) {
        *pRetVal = (IUnknown *)vtbl;
        InterlockedIncrement(&el->refCount);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Simple_GetPropertyValue(
    IRawElementProviderSimple *This, PROPERTYID propertyId, VARIANT *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SIMPLE(This);
    note_a11y_query(el->projection);
    if (!pRetVal) return E_POINTER;
    VariantInit(pRetVal);
    switch (propertyId) {
        case UIA_NamePropertyId: {
            if (el->label && el->label[0]) {
                pRetVal->vt = VT_BSTR;
                pRetVal->bstrVal = SysAllocString(el->label);
            }
            break;
        }
        case UIA_ControlTypePropertyId: {
            pRetVal->vt = VT_I4;
            pRetVal->lVal = role_to_uia_control_type(el->role, el->flags);
            break;
        }
        case UIA_IsControlElementPropertyId:
        case UIA_IsContentElementPropertyId: {
            pRetVal->vt = VT_BOOL;
            pRetVal->boolVal = (el->flags & A11Y_FLAG_IS_ELEMENT) ? VARIANT_TRUE : VARIANT_FALSE;
            break;
        }
        case UIA_IsEnabledPropertyId: {
            pRetVal->vt = VT_BOOL;
            pRetVal->boolVal = (el->flags & A11Y_FLAG_ENABLED) ? VARIANT_TRUE : VARIANT_FALSE;
            break;
        }
        case UIA_IsKeyboardFocusablePropertyId: {
            pRetVal->vt = VT_BOOL;
            pRetVal->boolVal = (el->actions & A11Y_ACTION_REQUEST_FOCUS) ? VARIANT_TRUE : VARIANT_FALSE;
            break;
        }
        case UIA_HasKeyboardFocusPropertyId: {
            pRetVal->vt = VT_BOOL;
            pRetVal->boolVal = (el->flags & A11Y_FLAG_FOCUSED) ? VARIANT_TRUE : VARIANT_FALSE;
            break;
        }
        case UIA_AutomationIdPropertyId: {
            /* wsprintfW supports %I64d (NOT %lld) for 64-bit ints. */
            wchar_t buf[32];
            wsprintfW(buf, L"node-%I64d", (long long)el->nodeId);
            pRetVal->vt = VT_BSTR;
            pRetVal->bstrVal = SysAllocString(buf);
            break;
        }
        case UIA_IsPasswordPropertyId: {
            pRetVal->vt = VT_BOOL;
            pRetVal->boolVal = (el->flags & A11Y_FLAG_PASSWORD) ? VARIANT_TRUE : VARIANT_FALSE;
            break;
        }
        case UIA_HelpTextPropertyId: {
            /* Surface custom-action labels as help text — UIA has no native
             * equivalent of macOS's NSAccessibilityCustomAction, so we
             * concatenate them into a hint that screen readers can announce. */
            if (el->customActionCount > 0) {
                int total = 0;
                for (int i = 0; i < el->customActionCount; i++) {
                    if (el->customActions[i]) total += lstrlenW(el->customActions[i]) + 2;
                }
                if (total > 0) {
                    wchar_t *buf = (wchar_t *)xalloc(sizeof(wchar_t) * (total + 1));
                    if (buf) {
                        int pos = 0;
                        for (int i = 0; i < el->customActionCount; i++) {
                            if (!el->customActions[i]) continue;
                            int len = lstrlenW(el->customActions[i]);
                            if (pos > 0) { buf[pos++] = L','; buf[pos++] = L' '; }
                            for (int j = 0; j < len; j++) buf[pos++] = el->customActions[i][j];
                        }
                        buf[pos] = 0;
                        pRetVal->vt = VT_BSTR;
                        pRetVal->bstrVal = SysAllocString(buf);
                        xfree(buf);
                    }
                }
            }
            break;
        }
        case UIA_LevelPropertyId: {
            if (el->flags & A11Y_FLAG_HEADING) {
                pRetVal->vt = VT_I4;
                pRetVal->lVal = 2; /* Generic heading; Compose doesn't carry a level. */
            }
            break;
        }
        case UIA_OrientationPropertyId: {
            if (el->role == A11Y_ROLE_SLIDER) {
                pRetVal->vt = VT_I4;
                /* Compose sliders are horizontal by default; we have no
                 * dedicated flag to differentiate. Default to horizontal. */
                pRetVal->lVal = 1; /* OrientationType_Horizontal */
            }
            break;
        }
        /* Pattern-driven properties — UIA clients sometimes query these
         * directly instead of going through the pattern interface. Mirror
         * what the pattern would return so both code paths stay consistent. */
        case UIA_ToggleToggleStatePropertyId: {
            if (has_toggle(el)) {
                pRetVal->vt = VT_I4;
                if (el->flags & A11Y_FLAG_MIXED) pRetVal->lVal = 2;
                else if (el->flags & A11Y_FLAG_CHECKED) pRetVal->lVal = 1;
                else pRetVal->lVal = 0;
            }
            break;
        }
        case UIA_ValueValuePropertyId: {
            if (has_value(el) && el->valueStr) {
                pRetVal->vt = VT_BSTR;
                pRetVal->bstrVal = SysAllocString(el->valueStr);
            }
            break;
        }
        case UIA_ValueIsReadOnlyPropertyId: {
            if (has_value(el)) {
                pRetVal->vt = VT_BOOL;
                pRetVal->boolVal = (el->actions & A11Y_ACTION_SET_TEXT) ? VARIANT_FALSE : VARIANT_TRUE;
            }
            break;
        }
        case UIA_RangeValueValuePropertyId: {
            if (has_range_value(el)) {
                pRetVal->vt = VT_R8;
                pRetVal->dblVal = (double)el->numericValue;
            }
            break;
        }
        case UIA_RangeValueMinimumPropertyId: {
            if (has_range_value(el)) {
                pRetVal->vt = VT_R8;
                pRetVal->dblVal = (double)el->minValue;
            }
            break;
        }
        case UIA_RangeValueMaximumPropertyId: {
            if (has_range_value(el)) {
                pRetVal->vt = VT_R8;
                pRetVal->dblVal = (double)el->maxValue;
            }
            break;
        }
        case UIA_SelectionItemIsSelectedPropertyId: {
            if (has_selection_item(el)) {
                pRetVal->vt = VT_BOOL;
                pRetVal->boolVal = (el->flags & A11Y_FLAG_SELECTED) ? VARIANT_TRUE : VARIANT_FALSE;
            }
            break;
        }
        default:
            break;
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Simple_get_HostRawElementProvider(
    IRawElementProviderSimple *This, IRawElementProviderSimple **pRetVal)
{
    (void)This;
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;  /* Not HWND-hosted. */
    return S_OK;
}

static IRawElementProviderSimpleVtbl g_elementSimpleVtbl = {
    Element_Simple_QueryInterface,
    Element_Simple_AddRef,
    Element_Simple_Release,
    Element_Simple_get_ProviderOptions,
    Element_Simple_GetPatternProvider,
    Element_Simple_GetPropertyValue,
    Element_Simple_get_HostRawElementProvider
};

/* ── Element vtable: IRawElementProviderFragment ──────────────────────────── */

static HRESULT STDMETHODCALLTYPE Element_Fragment_QueryInterface(
    IRawElementProviderFragment *This, REFIID riid, void **ppv)
{
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    return Element_Simple_QueryInterface((IRawElementProviderSimple *)&el->lpSimpleVtbl, riid, ppv);
}

static ULONG STDMETHODCALLTYPE Element_Fragment_AddRef(IRawElementProviderFragment *This) {
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    return (ULONG)InterlockedIncrement(&el->refCount);
}

static ULONG STDMETHODCALLTYPE Element_Fragment_Release(IRawElementProviderFragment *This) {
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    return Element_Simple_Release((IRawElementProviderSimple *)&el->lpSimpleVtbl);
}

static IRawElementProviderFragment *element_as_fragment(NucleusUiaElement *el) {
    if (!el) return NULL;
    InterlockedIncrement(&el->refCount);
    return (IRawElementProviderFragment *)&el->lpFragmentVtbl;
}

static HRESULT STDMETHODCALLTYPE Element_Fragment_Navigate(
    IRawElementProviderFragment *This, enum NavigateDirection direction,
    IRawElementProviderFragment **pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    NucleusUiaProjection *p = el->projection;
    if (!p) return S_OK;
    EnterCriticalSection(&p->lock);
    switch (direction) {
        case NavigateDirection_Parent: {
            if (el->parent) {
                *pRetVal = element_as_fragment(el->parent);
            } else if (p->root) {
                InterlockedIncrement(&p->root->refCount);
                *pRetVal = (IRawElementProviderFragment *)&p->root->lpFragmentVtbl;
            }
            break;
        }
        case NavigateDirection_NextSibling: {
            NucleusUiaElement **list; int count;
            if (el->parent) { list = el->parent->children; count = el->parent->childCount; }
            else            { list = p->roots; count = p->rootCount; }
            int idx = el->siblingIndex + 1;
            if (idx >= 0 && idx < count) *pRetVal = element_as_fragment(list[idx]);
            break;
        }
        case NavigateDirection_PreviousSibling: {
            NucleusUiaElement **list; int count;
            if (el->parent) { list = el->parent->children; count = el->parent->childCount; }
            else            { list = p->roots; count = p->rootCount; }
            int idx = el->siblingIndex - 1;
            if (idx >= 0 && idx < count) *pRetVal = element_as_fragment(list[idx]);
            break;
        }
        case NavigateDirection_FirstChild: {
            if (el->childCount > 0) *pRetVal = element_as_fragment(el->children[0]);
            break;
        }
        case NavigateDirection_LastChild: {
            if (el->childCount > 0) *pRetVal = element_as_fragment(el->children[el->childCount - 1]);
            break;
        }
    }
    LeaveCriticalSection(&p->lock);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Fragment_GetRuntimeId(
    IRawElementProviderFragment *This, SAFEARRAY **pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    SAFEARRAY *sa = SafeArrayCreateVector(VT_I4, 0, 3);
    if (!sa) return E_OUTOFMEMORY;
    int idx = 0;
    int values[3] = { UiaAppendRuntimeId, (int)(el->nodeId & 0xFFFFFFFF), (int)(el->nodeId >> 32) };
    for (idx = 0; idx < 3; idx++) {
        LONG li = idx;
        SafeArrayPutElement(sa, &li, &values[idx]);
    }
    *pRetVal = sa;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Fragment_get_BoundingRectangle(
    IRawElementProviderFragment *This, struct UiaRect *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    if (!pRetVal) return E_POINTER;
    pRetVal->left = pRetVal->top = pRetVal->width = pRetVal->height = 0;
    NucleusUiaProjection *p = el->projection;
    if (!p) return S_OK;
    HWND hwnd = p->hwnd;
    if (!hwnd || !IsWindow(hwnd)) return S_OK;
    /* Frame is in window-local logical points. Convert to physical screen px. */
    UINT dpi = 96;
    HMODULE u32 = GetModuleHandleW(L"user32.dll");
    if (u32) {
        UINT (WINAPI *pGetDpiForWindow)(HWND) =
            (UINT (WINAPI *)(HWND))GetProcAddress(u32, "GetDpiForWindow");
        if (pGetDpiForWindow) dpi = pGetDpiForWindow(hwnd);
    }
    float scale = (float)dpi / 96.0f;
    POINT origin = { 0, 0 };
    ClientToScreen(hwnd, &origin);
    pRetVal->left   = origin.x + (double)(el->frameX * scale);
    pRetVal->top    = origin.y + (double)(el->frameY * scale);
    pRetVal->width  = (double)(el->frameW * scale);
    pRetVal->height = (double)(el->frameH * scale);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Fragment_GetEmbeddedFragmentRoots(
    IRawElementProviderFragment *This, SAFEARRAY **pRetVal)
{
    (void)This;
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Fragment_SetFocus(IRawElementProviderFragment *This) {
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    if (el->actions & A11Y_ACTION_REQUEST_FOCUS) {
        nucleus_tao_a11y_invoke_action_win((int64_t)(uintptr_t)el->projection->hwnd,
                                            el->nodeId, A11Y_ACTION_REQUEST_FOCUS);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Fragment_get_FragmentRoot(
    IRawElementProviderFragment *This, IRawElementProviderFragmentRoot **pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_FRAGMENT(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    if (el->projection && el->projection->root) {
        InterlockedIncrement(&el->projection->root->refCount);
        *pRetVal = (IRawElementProviderFragmentRoot *)&el->projection->root->lpFragmentRootVtbl;
    }
    return S_OK;
}

static IRawElementProviderFragmentVtbl g_elementFragmentVtbl = {
    Element_Fragment_QueryInterface,
    Element_Fragment_AddRef,
    Element_Fragment_Release,
    Element_Fragment_Navigate,
    Element_Fragment_GetRuntimeId,
    Element_Fragment_get_BoundingRectangle,
    Element_Fragment_GetEmbeddedFragmentRoots,
    Element_Fragment_SetFocus,
    Element_Fragment_get_FragmentRoot
};

/* ── Element vtable: IInvokeProvider ──────────────────────────────────────── */

static HRESULT STDMETHODCALLTYPE Element_Invoke_QueryInterface(
    IInvokeProvider *This, REFIID riid, void **ppv)
{
    NucleusUiaElement *el = ELEMENT_FROM_INVOKE(This);
    return Element_Simple_QueryInterface((IRawElementProviderSimple *)&el->lpSimpleVtbl, riid, ppv);
}
static ULONG STDMETHODCALLTYPE Element_Invoke_AddRef(IInvokeProvider *This) {
    NucleusUiaElement *el = ELEMENT_FROM_INVOKE(This);
    return (ULONG)InterlockedIncrement(&el->refCount);
}
static ULONG STDMETHODCALLTYPE Element_Invoke_Release(IInvokeProvider *This) {
    NucleusUiaElement *el = ELEMENT_FROM_INVOKE(This);
    return Element_Simple_Release((IRawElementProviderSimple *)&el->lpSimpleVtbl);
}

static HRESULT STDMETHODCALLTYPE Element_Invoke_Invoke(IInvokeProvider *This) {
    NucleusUiaElement *el = ELEMENT_FROM_INVOKE(This);
    if (el->projection && el->projection->hwnd) {
        nucleus_tao_a11y_invoke_action_win((int64_t)(uintptr_t)el->projection->hwnd,
                                            el->nodeId, A11Y_ACTION_CLICK);
    }
    return S_OK;
}

static IInvokeProviderVtbl g_elementInvokeVtbl = {
    Element_Invoke_QueryInterface,
    Element_Invoke_AddRef,
    Element_Invoke_Release,
    Element_Invoke_Invoke
};

/* ── Pattern: Toggle (Checkbox/Switch) ────────────────────────────────────── */

#define DEFINE_PATTERN_IUNKNOWN(NAME, IFACE, FROM_MACRO) \
    static HRESULT STDMETHODCALLTYPE Element_##NAME##_QueryInterface( \
        IFACE *This, REFIID riid, void **ppv) { \
        NucleusUiaElement *el = FROM_MACRO(This); \
        return Element_Simple_QueryInterface((IRawElementProviderSimple *)&el->lpSimpleVtbl, riid, ppv); \
    } \
    static ULONG STDMETHODCALLTYPE Element_##NAME##_AddRef(IFACE *This) { \
        NucleusUiaElement *el = FROM_MACRO(This); \
        return (ULONG)InterlockedIncrement(&el->refCount); \
    } \
    static ULONG STDMETHODCALLTYPE Element_##NAME##_Release(IFACE *This) { \
        NucleusUiaElement *el = FROM_MACRO(This); \
        return Element_Simple_Release((IRawElementProviderSimple *)&el->lpSimpleVtbl); \
    }

DEFINE_PATTERN_IUNKNOWN(Toggle, IToggleProvider, ELEMENT_FROM_TOGGLE)

static HRESULT STDMETHODCALLTYPE Element_Toggle_get_ToggleState(
    IToggleProvider *This, enum ToggleState *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_TOGGLE(This);
    if (!pRetVal) return E_POINTER;
    if (el->flags & A11Y_FLAG_MIXED)        *pRetVal = ToggleState_Indeterminate;
    else if (el->flags & A11Y_FLAG_CHECKED) *pRetVal = ToggleState_On;
    else                                    *pRetVal = ToggleState_Off;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Toggle_Toggle(IToggleProvider *This) {
    NucleusUiaElement *el = ELEMENT_FROM_TOGGLE(This);
    if (el->projection && el->projection->hwnd) {
        nucleus_tao_a11y_invoke_action_win((int64_t)(uintptr_t)el->projection->hwnd,
                                            el->nodeId, A11Y_ACTION_CLICK);
    }
    return S_OK;
}

static IToggleProviderVtbl g_elementToggleVtbl = {
    Element_Toggle_QueryInterface,
    Element_Toggle_AddRef,
    Element_Toggle_Release,
    Element_Toggle_Toggle,
    Element_Toggle_get_ToggleState
};

/* ── Pattern: Value (TextField/TextArea) ──────────────────────────────────── */

DEFINE_PATTERN_IUNKNOWN(Value, IValueProvider, ELEMENT_FROM_VALUE)

static HRESULT STDMETHODCALLTYPE Element_Value_SetValue(
    IValueProvider *This, LPCWSTR val)
{
    NucleusUiaElement *el = ELEMENT_FROM_VALUE(This);
    if (!val) return E_POINTER;
    if (!g_setTextCb) return UIA_E_NOTSUPPORTED;
    int wlen = lstrlenW(val);
    int needed = WideCharToMultiByte(CP_UTF8, 0, val, wlen, NULL, 0, NULL, NULL);
    if (needed < 0) return E_FAIL;
    char *utf8 = (char *)xalloc(needed > 0 ? needed : 1);
    if (!utf8) return E_OUTOFMEMORY;
    WideCharToMultiByte(CP_UTF8, 0, val, wlen, utf8, needed, NULL, NULL);
    g_setTextCb((int64_t)(uintptr_t)el->projection->hwnd, el->nodeId, utf8, needed);
    xfree(utf8);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Value_get_Value(
    IValueProvider *This, BSTR *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_VALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = SysAllocString(el->valueStr ? el->valueStr : L"");
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Value_get_IsReadOnly(
    IValueProvider *This, BOOL *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_VALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (el->actions & A11Y_ACTION_SET_TEXT) ? FALSE : TRUE;
    return S_OK;
}

static IValueProviderVtbl g_elementValueVtbl = {
    Element_Value_QueryInterface,
    Element_Value_AddRef,
    Element_Value_Release,
    Element_Value_SetValue,
    Element_Value_get_Value,
    Element_Value_get_IsReadOnly
};

/* ── Pattern: RangeValue (Slider/Progress) ────────────────────────────────── */

DEFINE_PATTERN_IUNKNOWN(RangeValue, IRangeValueProvider, ELEMENT_FROM_RANGEVALUE)

static HRESULT STDMETHODCALLTYPE Element_RangeValue_SetValue(
    IRangeValueProvider *This, double val)
{
    NucleusUiaElement *el = ELEMENT_FROM_RANGEVALUE(This);
    /* Compose's SetProgress is invoked through Increment/Decrement on the
     * Kotlin side; UIA's SetValue is exposed for screen readers but the
     * dispatch goes through the same channel. We call Increment/Decrement
     * relative to current value for parity with macOS (where VoiceOver also
     * uses inc/dec). */
    if (!el->projection || !el->projection->hwnd) return S_OK;
    uint16_t action = (val > el->numericValue) ? A11Y_ACTION_INCREMENT : A11Y_ACTION_DECREMENT;
    if (val == el->numericValue) return S_OK;
    nucleus_tao_a11y_invoke_action_win((int64_t)(uintptr_t)el->projection->hwnd,
                                        el->nodeId, action);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_RangeValue_get_Value(
    IRangeValueProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_RANGEVALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (double)el->numericValue;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_RangeValue_get_IsReadOnly(
    IRangeValueProvider *This, BOOL *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_RANGEVALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (el->actions & (A11Y_ACTION_INCREMENT | A11Y_ACTION_DECREMENT)) ? FALSE : TRUE;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_RangeValue_get_Maximum(
    IRangeValueProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_RANGEVALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (double)el->maxValue;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_RangeValue_get_Minimum(
    IRangeValueProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_RANGEVALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (double)el->minValue;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_RangeValue_get_LargeChange(
    IRangeValueProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_RANGEVALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (double)((el->maxValue - el->minValue) * 0.1f);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_RangeValue_get_SmallChange(
    IRangeValueProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_RANGEVALUE(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (double)((el->maxValue - el->minValue) * 0.01f);
    return S_OK;
}

static IRangeValueProviderVtbl g_elementRangeValueVtbl = {
    Element_RangeValue_QueryInterface,
    Element_RangeValue_AddRef,
    Element_RangeValue_Release,
    Element_RangeValue_SetValue,
    Element_RangeValue_get_Value,
    Element_RangeValue_get_IsReadOnly,
    Element_RangeValue_get_Maximum,
    Element_RangeValue_get_Minimum,
    Element_RangeValue_get_LargeChange,
    Element_RangeValue_get_SmallChange
};

/* ── Pattern: SelectionItem (RadioButton/Tab) ─────────────────────────────── */

DEFINE_PATTERN_IUNKNOWN(SelectionItem, ISelectionItemProvider, ELEMENT_FROM_SELECTIONITEM)

static HRESULT STDMETHODCALLTYPE Element_SelectionItem_Select(ISelectionItemProvider *This) {
    NucleusUiaElement *el = ELEMENT_FROM_SELECTIONITEM(This);
    if (el->projection && el->projection->hwnd) {
        nucleus_tao_a11y_invoke_action_win((int64_t)(uintptr_t)el->projection->hwnd,
                                            el->nodeId, A11Y_ACTION_CLICK);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_SelectionItem_AddToSelection(
    ISelectionItemProvider *This)
{
    /* Compose has no AddToSelection — single-select only for radio/tab. Fall
     * back to plain Select. */
    return Element_SelectionItem_Select(This);
}

static HRESULT STDMETHODCALLTYPE Element_SelectionItem_RemoveFromSelection(
    ISelectionItemProvider *This)
{
    (void)This;
    /* No-op; radio/tab selection is exclusive and managed by the parent. */
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_SelectionItem_get_IsSelected(
    ISelectionItemProvider *This, BOOL *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SELECTIONITEM(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (el->flags & A11Y_FLAG_SELECTED) ? TRUE : FALSE;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_SelectionItem_get_SelectionContainer(
    ISelectionItemProvider *This, IRawElementProviderSimple **pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SELECTIONITEM(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    if (el->parent) {
        InterlockedIncrement(&el->parent->refCount);
        *pRetVal = (IRawElementProviderSimple *)&el->parent->lpSimpleVtbl;
    }
    return S_OK;
}

static ISelectionItemProviderVtbl g_elementSelectionItemVtbl = {
    Element_SelectionItem_QueryInterface,
    Element_SelectionItem_AddRef,
    Element_SelectionItem_Release,
    Element_SelectionItem_Select,
    Element_SelectionItem_AddToSelection,
    Element_SelectionItem_RemoveFromSelection,
    Element_SelectionItem_get_IsSelected,
    Element_SelectionItem_get_SelectionContainer
};

/* ── Pattern: Scroll (ScrollArea) ─────────────────────────────────────────── */

DEFINE_PATTERN_IUNKNOWN(Scroll, IScrollProvider, ELEMENT_FROM_SCROLL)

static HRESULT STDMETHODCALLTYPE Element_Scroll_Scroll(
    IScrollProvider *This, enum ScrollAmount horizontal, enum ScrollAmount vertical)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!el->projection || !el->projection->hwnd) return S_OK;
    int64_t hwnd = (int64_t)(uintptr_t)el->projection->hwnd;
    /* Map ScrollAmount enum to the SCROLL_xxx actions. UIA's
     * LargeIncrement/LargeDecrement = page; SmallIncrement/SmallDecrement =
     * one line. We treat both as "page" for simplicity, mirroring macOS. */
    if (vertical == ScrollAmount_LargeDecrement || vertical == ScrollAmount_SmallDecrement) {
        nucleus_tao_a11y_invoke_action_win(hwnd, el->nodeId, A11Y_ACTION_SCROLL_UP);
    } else if (vertical == ScrollAmount_LargeIncrement || vertical == ScrollAmount_SmallIncrement) {
        nucleus_tao_a11y_invoke_action_win(hwnd, el->nodeId, A11Y_ACTION_SCROLL_DOWN);
    }
    if (horizontal == ScrollAmount_LargeDecrement || horizontal == ScrollAmount_SmallDecrement) {
        nucleus_tao_a11y_invoke_action_win(hwnd, el->nodeId, A11Y_ACTION_SCROLL_LEFT);
    } else if (horizontal == ScrollAmount_LargeIncrement || horizontal == ScrollAmount_SmallIncrement) {
        nucleus_tao_a11y_invoke_action_win(hwnd, el->nodeId, A11Y_ACTION_SCROLL_RIGHT);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Scroll_SetScrollPercent(
    IScrollProvider *This, double horizontalPercent, double verticalPercent)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!el->projection || !el->projection->hwnd) return S_OK;
    if (!g_scrollByCb) return S_OK;
    /* Compute absolute target from percent → delta from current. */
    float dx = 0.0f, dy = 0.0f;
    if (horizontalPercent >= 0.0 && el->hScrollMax > 0.0f) {
        float target = (float)horizontalPercent * 0.01f * el->hScrollMax;
        dx = target - el->hScrollValue;
    }
    if (verticalPercent >= 0.0 && el->vScrollMax > 0.0f) {
        float target = (float)verticalPercent * 0.01f * el->vScrollMax;
        dy = target - el->vScrollValue;
    }
    if (dx != 0.0f || dy != 0.0f) {
        g_scrollByCb((int64_t)(uintptr_t)el->projection->hwnd, el->nodeId, dx, dy);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Scroll_get_HorizontalScrollPercent(
    IScrollProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!pRetVal) return E_POINTER;
    if (el->hScrollMax > 0.0f) {
        *pRetVal = (double)(el->hScrollValue / el->hScrollMax) * 100.0;
    } else {
        *pRetVal = -1.0; /* UIA_ScrollPatternNoScroll */
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Scroll_get_VerticalScrollPercent(
    IScrollProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!pRetVal) return E_POINTER;
    if (el->vScrollMax > 0.0f) {
        *pRetVal = (double)(el->vScrollValue / el->vScrollMax) * 100.0;
    } else {
        *pRetVal = -1.0;
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Scroll_get_HorizontalViewSize(
    IScrollProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!pRetVal) return E_POINTER;
    /* Approximation: viewport = framewidth ≈ visible portion of total. */
    if (el->hScrollMax + el->frameW > 0.0f) {
        *pRetVal = (double)(el->frameW / (el->frameW + el->hScrollMax)) * 100.0;
    } else {
        *pRetVal = 100.0;
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Scroll_get_VerticalViewSize(
    IScrollProvider *This, double *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!pRetVal) return E_POINTER;
    if (el->vScrollMax + el->frameH > 0.0f) {
        *pRetVal = (double)(el->frameH / (el->frameH + el->vScrollMax)) * 100.0;
    } else {
        *pRetVal = 100.0;
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Scroll_get_HorizontallyScrollable(
    IScrollProvider *This, BOOL *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (el->hScrollMax > 0.0f) ? TRUE : FALSE;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_Scroll_get_VerticallyScrollable(
    IScrollProvider *This, BOOL *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_SCROLL(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = (el->vScrollMax > 0.0f) ? TRUE : FALSE;
    return S_OK;
}

static IScrollProviderVtbl g_elementScrollVtbl = {
    Element_Scroll_QueryInterface,
    Element_Scroll_AddRef,
    Element_Scroll_Release,
    Element_Scroll_Scroll,
    Element_Scroll_SetScrollPercent,
    Element_Scroll_get_HorizontalScrollPercent,
    Element_Scroll_get_VerticalScrollPercent,
    Element_Scroll_get_HorizontalViewSize,
    Element_Scroll_get_VerticalViewSize,
    Element_Scroll_get_HorizontallyScrollable,
    Element_Scroll_get_VerticallyScrollable
};

/* ── Pattern: ExpandCollapse (PopupMenu) ──────────────────────────────────── */

DEFINE_PATTERN_IUNKNOWN(ExpandCollapse, IExpandCollapseProvider, ELEMENT_FROM_EXPANDCOLLAPSE)

static HRESULT STDMETHODCALLTYPE Element_ExpandCollapse_Expand(IExpandCollapseProvider *This) {
    NucleusUiaElement *el = ELEMENT_FROM_EXPANDCOLLAPSE(This);
    if (el->projection && el->projection->hwnd) {
        nucleus_tao_a11y_invoke_action_win((int64_t)(uintptr_t)el->projection->hwnd,
                                            el->nodeId, A11Y_ACTION_CLICK);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_ExpandCollapse_Collapse(IExpandCollapseProvider *This) {
    NucleusUiaElement *el = ELEMENT_FROM_EXPANDCOLLAPSE(This);
    if (el->projection && el->projection->hwnd) {
        nucleus_tao_a11y_invoke_action_win((int64_t)(uintptr_t)el->projection->hwnd,
                                            el->nodeId, A11Y_ACTION_DISMISS);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Element_ExpandCollapse_get_ExpandCollapseState(
    IExpandCollapseProvider *This, enum ExpandCollapseState *pRetVal)
{
    NucleusUiaElement *el = ELEMENT_FROM_EXPANDCOLLAPSE(This);
    if (!pRetVal) return E_POINTER;
    /* Compose doesn't carry an explicit "expanded" flag for popup menus.
     * Approximate via SELECTED (treated as "shown"). */
    *pRetVal = (el->flags & A11Y_FLAG_SELECTED)
        ? ExpandCollapseState_Expanded
        : ExpandCollapseState_Collapsed;
    return S_OK;
}

static IExpandCollapseProviderVtbl g_elementExpandCollapseVtbl = {
    Element_ExpandCollapse_QueryInterface,
    Element_ExpandCollapse_AddRef,
    Element_ExpandCollapse_Release,
    Element_ExpandCollapse_Expand,
    Element_ExpandCollapse_Collapse,
    Element_ExpandCollapse_get_ExpandCollapseState
};

/* ── Root vtable: IRawElementProviderSimple ───────────────────────────────── */

#define ROOT_FROM_SIMPLE(p)        ((NucleusUiaRoot *)(p))
#define ROOT_FROM_FRAGMENT(p)      ((NucleusUiaRoot *)((char *)(p) - offsetof(NucleusUiaRoot, lpFragmentVtbl)))
#define ROOT_FROM_FRAGMENT_ROOT(p) ((NucleusUiaRoot *)((char *)(p) - offsetof(NucleusUiaRoot, lpFragmentRootVtbl)))

static HRESULT STDMETHODCALLTYPE Root_Simple_QueryInterface(
    IRawElementProviderSimple *This, REFIID riid, void **ppv)
{
    NucleusUiaRoot *r = ROOT_FROM_SIMPLE(This);
    if (!ppv) return E_POINTER;
    *ppv = NULL;
    if (IsEqualIID(riid, &IID_IUnknown) ||
        IsEqualIID(riid, &IID_IRawElementProviderSimple)) {
        *ppv = &r->lpSimpleVtbl;
    } else if (IsEqualIID(riid, &IID_IRawElementProviderFragment)) {
        *ppv = &r->lpFragmentVtbl;
    } else if (IsEqualIID(riid, &IID_IRawElementProviderFragmentRoot)) {
        *ppv = &r->lpFragmentRootVtbl;
    } else {
        return E_NOINTERFACE;
    }
    InterlockedIncrement(&r->refCount);
    return S_OK;
}

static ULONG STDMETHODCALLTYPE Root_Simple_AddRef(IRawElementProviderSimple *This) {
    NucleusUiaRoot *r = ROOT_FROM_SIMPLE(This);
    return (ULONG)InterlockedIncrement(&r->refCount);
}
static ULONG STDMETHODCALLTYPE Root_Simple_Release(IRawElementProviderSimple *This) {
    NucleusUiaRoot *r = ROOT_FROM_SIMPLE(This);
    LONG c = InterlockedDecrement(&r->refCount);
    if (c == 0) xfree(r);
    return (ULONG)c;
}

static HRESULT STDMETHODCALLTYPE Root_Simple_get_ProviderOptions(
    IRawElementProviderSimple *This, enum ProviderOptions *pRetVal)
{
    (void)This;
    if (!pRetVal) return E_POINTER;
    *pRetVal = ProviderOptions_ServerSideProvider | ProviderOptions_UseComThreading;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Simple_GetPatternProvider(
    IRawElementProviderSimple *This, PATTERNID patternId, IUnknown **pRetVal)
{
    (void)This; (void)patternId;
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Simple_GetPropertyValue(
    IRawElementProviderSimple *This, PROPERTYID propertyId, VARIANT *pRetVal)
{
    NucleusUiaRoot *r = ROOT_FROM_SIMPLE(This);
    note_a11y_query(r->projection);
    if (!pRetVal) return E_POINTER;
    VariantInit(pRetVal);
    switch (propertyId) {
        case UIA_NamePropertyId: {
            wchar_t title[256] = {0};
            int n = GetWindowTextW(r->hwnd, title, 255);
            if (n > 0) {
                pRetVal->vt = VT_BSTR;
                pRetVal->bstrVal = SysAllocString(title);
            }
            break;
        }
        case UIA_ControlTypePropertyId: {
            pRetVal->vt = VT_I4;
            pRetVal->lVal = UIA_PaneControlTypeId;
            break;
        }
        case UIA_IsControlElementPropertyId:
        case UIA_IsContentElementPropertyId: {
            pRetVal->vt = VT_BOOL;
            pRetVal->boolVal = VARIANT_TRUE;
            break;
        }
        case UIA_NativeWindowHandlePropertyId: {
            pRetVal->vt = VT_I4;
            pRetVal->lVal = (LONG)(LONG_PTR)r->hwnd;
            break;
        }
        case UIA_IsEnabledPropertyId: {
            pRetVal->vt = VT_BOOL;
            pRetVal->boolVal = VARIANT_TRUE;
            break;
        }
        case UIA_AutomationIdPropertyId: {
            pRetVal->vt = VT_BSTR;
            pRetVal->bstrVal = SysAllocString(L"NucleusComposeRoot");
            break;
        }
        case UIA_FrameworkIdPropertyId: {
            pRetVal->vt = VT_BSTR;
            pRetVal->bstrVal = SysAllocString(L"Nucleus.Compose");
            break;
        }
        default:
            break;
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Simple_get_HostRawElementProvider(
    IRawElementProviderSimple *This, IRawElementProviderSimple **pRetVal)
{
    NucleusUiaRoot *r = ROOT_FROM_SIMPLE(This);
    if (!pRetVal) return E_POINTER;
    /* Returning the HWND's default provider lets UIA chain to it for the
     * window-frame metadata (name from WM_GETTEXT, transform pattern, etc.). */
    return UiaHostProviderFromHwnd(r->hwnd, pRetVal);
}

static IRawElementProviderSimpleVtbl g_rootSimpleVtbl = {
    Root_Simple_QueryInterface,
    Root_Simple_AddRef,
    Root_Simple_Release,
    Root_Simple_get_ProviderOptions,
    Root_Simple_GetPatternProvider,
    Root_Simple_GetPropertyValue,
    Root_Simple_get_HostRawElementProvider
};

/* ── Root vtable: IRawElementProviderFragment ─────────────────────────────── */

static HRESULT STDMETHODCALLTYPE Root_Fragment_QueryInterface(
    IRawElementProviderFragment *This, REFIID riid, void **ppv)
{
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT(This);
    return Root_Simple_QueryInterface((IRawElementProviderSimple *)&r->lpSimpleVtbl, riid, ppv);
}
static ULONG STDMETHODCALLTYPE Root_Fragment_AddRef(IRawElementProviderFragment *This) {
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT(This);
    return (ULONG)InterlockedIncrement(&r->refCount);
}
static ULONG STDMETHODCALLTYPE Root_Fragment_Release(IRawElementProviderFragment *This) {
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT(This);
    return Root_Simple_Release((IRawElementProviderSimple *)&r->lpSimpleVtbl);
}

static HRESULT STDMETHODCALLTYPE Root_Fragment_Navigate(
    IRawElementProviderFragment *This, enum NavigateDirection direction,
    IRawElementProviderFragment **pRetVal)
{
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    NucleusUiaProjection *p = r->projection;
    if (!p) return S_OK;
    EnterCriticalSection(&p->lock);
    switch (direction) {
        case NavigateDirection_Parent:
        case NavigateDirection_NextSibling:
        case NavigateDirection_PreviousSibling:
            break;
        case NavigateDirection_FirstChild:
            if (p->rootCount > 0) *pRetVal = element_as_fragment(p->roots[0]);
            break;
        case NavigateDirection_LastChild:
            if (p->rootCount > 0) *pRetVal = element_as_fragment(p->roots[p->rootCount - 1]);
            break;
    }
    LeaveCriticalSection(&p->lock);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Fragment_GetRuntimeId(
    IRawElementProviderFragment *This, SAFEARRAY **pRetVal)
{
    (void)This;
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;  /* Root: UIA derives RuntimeId from the HWND. */
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Fragment_get_BoundingRectangle(
    IRawElementProviderFragment *This, struct UiaRect *pRetVal)
{
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT(This);
    if (!pRetVal) return E_POINTER;
    pRetVal->left = pRetVal->top = pRetVal->width = pRetVal->height = 0;
    if (!r->hwnd) return S_OK;
    RECT rc;
    if (GetWindowRect(r->hwnd, &rc)) {
        pRetVal->left   = rc.left;
        pRetVal->top    = rc.top;
        pRetVal->width  = rc.right - rc.left;
        pRetVal->height = rc.bottom - rc.top;
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Fragment_GetEmbeddedFragmentRoots(
    IRawElementProviderFragment *This, SAFEARRAY **pRetVal)
{
    (void)This;
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Fragment_SetFocus(IRawElementProviderFragment *This) {
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT(This);
    if (r->hwnd) SetFocus(r->hwnd);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_Fragment_get_FragmentRoot(
    IRawElementProviderFragment *This, IRawElementProviderFragmentRoot **pRetVal)
{
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT(This);
    if (!pRetVal) return E_POINTER;
    InterlockedIncrement(&r->refCount);
    *pRetVal = (IRawElementProviderFragmentRoot *)&r->lpFragmentRootVtbl;
    return S_OK;
}

static IRawElementProviderFragmentVtbl g_rootFragmentVtbl = {
    Root_Fragment_QueryInterface,
    Root_Fragment_AddRef,
    Root_Fragment_Release,
    Root_Fragment_Navigate,
    Root_Fragment_GetRuntimeId,
    Root_Fragment_get_BoundingRectangle,
    Root_Fragment_GetEmbeddedFragmentRoots,
    Root_Fragment_SetFocus,
    Root_Fragment_get_FragmentRoot
};

/* ── Root vtable: IRawElementProviderFragmentRoot ─────────────────────────── */

static HRESULT STDMETHODCALLTYPE Root_FragmentRoot_QueryInterface(
    IRawElementProviderFragmentRoot *This, REFIID riid, void **ppv)
{
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT_ROOT(This);
    return Root_Simple_QueryInterface((IRawElementProviderSimple *)&r->lpSimpleVtbl, riid, ppv);
}
static ULONG STDMETHODCALLTYPE Root_FragmentRoot_AddRef(IRawElementProviderFragmentRoot *This) {
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT_ROOT(This);
    return (ULONG)InterlockedIncrement(&r->refCount);
}
static ULONG STDMETHODCALLTYPE Root_FragmentRoot_Release(IRawElementProviderFragmentRoot *This) {
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT_ROOT(This);
    return Root_Simple_Release((IRawElementProviderSimple *)&r->lpSimpleVtbl);
}

static HRESULT STDMETHODCALLTYPE Root_FragmentRoot_ElementProviderFromPoint(
    IRawElementProviderFragmentRoot *This, double x, double y,
    IRawElementProviderFragment **pRetVal)
{
    /* Hit-test: walk roots → children for the deepest element whose frame
     * contains the point. Coordinates are in screen space. */
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT_ROOT(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    NucleusUiaProjection *p = r->projection;
    if (!p || !r->hwnd) return S_OK;
    UINT dpi = 96;
    HMODULE u32 = GetModuleHandleW(L"user32.dll");
    if (u32) {
        UINT (WINAPI *pGetDpiForWindow)(HWND) =
            (UINT (WINAPI *)(HWND))GetProcAddress(u32, "GetDpiForWindow");
        if (pGetDpiForWindow) dpi = pGetDpiForWindow(r->hwnd);
    }
    float scale = (float)dpi / 96.0f;
    POINT origin = { 0, 0 };
    ClientToScreen(r->hwnd, &origin);
    double localX = (x - origin.x) / scale;
    double localY = (y - origin.y) / scale;

    NucleusUiaElement *best = NULL;
    EnterCriticalSection(&p->lock);
    /* Linear scan — frame test on every node. Adequate for trees up to a few
     * hundred nodes; can be replaced with a quadtree later. */
    for (int i = 0; i < p->byIdCapacity; i++) {
        NucleusUiaElement *e = p->byId[i];
        if (!e) continue;
        if (!(e->flags & A11Y_FLAG_IS_ELEMENT)) continue;
        if (localX < e->frameX || localX > e->frameX + e->frameW) continue;
        if (localY < e->frameY || localY > e->frameY + e->frameH) continue;
        /* Prefer the deepest hit (smallest area). */
        if (!best ||
            (e->frameW * e->frameH) < (best->frameW * best->frameH)) {
            best = e;
        }
    }
    if (best) *pRetVal = element_as_fragment(best);
    LeaveCriticalSection(&p->lock);
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE Root_FragmentRoot_GetFocus(
    IRawElementProviderFragmentRoot *This, IRawElementProviderFragment **pRetVal)
{
    NucleusUiaRoot *r = ROOT_FROM_FRAGMENT_ROOT(This);
    if (!pRetVal) return E_POINTER;
    *pRetVal = NULL;
    NucleusUiaProjection *p = r->projection;
    if (!p) return S_OK;
    EnterCriticalSection(&p->lock);
    for (int i = 0; i < p->byIdCapacity; i++) {
        NucleusUiaElement *e = p->byId[i];
        if (!e) continue;
        if (e->flags & A11Y_FLAG_FOCUSED) {
            *pRetVal = element_as_fragment(e);
            break;
        }
    }
    LeaveCriticalSection(&p->lock);
    return S_OK;
}

static IRawElementProviderFragmentRootVtbl g_rootFragmentRootVtbl = {
    Root_FragmentRoot_QueryInterface,
    Root_FragmentRoot_AddRef,
    Root_FragmentRoot_Release,
    Root_FragmentRoot_ElementProviderFromPoint,
    Root_FragmentRoot_GetFocus
};

/* WndProc subclass installed on attach. Intercepts WM_GETOBJECT to return
 * our UIA provider, and WM_DESTROY to disconnect cleanly. Also handles a
 * deferred-event message (WM_APP+1) that flushes the pending UIA event
 * queue from inside the message pump — see DeferredEvent below. */
#define A11Y_SUBCLASS_ID 0x4E55436CULL  /* 'NUCl' */
#define A11Y_WM_FLUSH_EVENTS (WM_APP + 1)

/* Deferred event: queued by apply_snapshot, flushed by the WndProc after
 * the JNI call returns and the Tao message pump resumes. Raising events
 * from inside the JNI-blocked tick prevents UIA's post-raise callbacks
 * (Navigate/scope resolution) from being serviced — STA needs to be
 * actively pumping during/after the raise. */
typedef struct DeferredEvent {
    /* The provider (root or element). AddRef'd by enqueuer; Released by
     * defer_flush. Stored as IRawElementProviderSimple* so root/element
     * heterogeneous queue is type-safe — the vtbl dispatch picks the right
     * Release implementation. */
    IRawElementProviderSimple *provider;
    int kind;                    /* 0=property, 1=notification, 2=focus, 3=structure */
    int propertyId;              /* kind=0 */
    VARIANT oldValue;            /* kind=0 */
    VARIANT newValue;            /* kind=0 */
    BSTR announceText;           /* kind=1; freed after raise */
    int announcePriority;        /* kind=1 */
    struct DeferredEvent *next;
} DeferredEvent;

static CRITICAL_SECTION g_deferQueueLock;
static DeferredEvent *g_deferQueueHead = NULL;
static DeferredEvent *g_deferQueueTail = NULL;
static volatile LONG g_deferInited = 0;

static void defer_init(void) {
    if (InterlockedCompareExchange(&g_deferInited, 1, 0) == 0) {
        InitializeCriticalSection(&g_deferQueueLock);
    }
}

static void defer_enqueue(HWND hwnd, DeferredEvent *e) {
    defer_init();
    e->next = NULL;
    EnterCriticalSection(&g_deferQueueLock);
    if (g_deferQueueTail) g_deferQueueTail->next = e;
    else g_deferQueueHead = e;
    g_deferQueueTail = e;
    LeaveCriticalSection(&g_deferQueueLock);
    PostMessageW(hwnd, A11Y_WM_FLUSH_EVENTS, 0, 0);
}

static DeferredEvent *defer_drain(void) {
    defer_init();
    EnterCriticalSection(&g_deferQueueLock);
    DeferredEvent *head = g_deferQueueHead;
    g_deferQueueHead = NULL;
    g_deferQueueTail = NULL;
    LeaveCriticalSection(&g_deferQueueLock);
    return head;
}

static void defer_flush(void) {
    DeferredEvent *cur = defer_drain();
    while (cur) {
        DeferredEvent *next = cur->next;
        IRawElementProviderSimple *prov =
            (IRawElementProviderSimple *)&cur->target->lpSimpleVtbl;
        HRESULT hr = S_OK;
        if (cur->kind == 0) {
            hr = UiaRaiseAutomationPropertyChangedEvent(
                prov, cur->propertyId, cur->oldValue, cur->newValue);
            VariantClear(&cur->oldValue);
            VariantClear(&cur->newValue);
        } else if (cur->kind == 1) {
            typedef HRESULT (WINAPI *PFN_RaiseNotif)(
                IRawElementProviderSimple *, int, int, BSTR, BSTR);
            static PFN_RaiseNotif pRaise = NULL;
            static volatile LONG resolved = 0;
            if (InterlockedCompareExchange(&resolved, 1, 0) == 0) {
                HMODULE m = GetModuleHandleW(L"uiautomationcore.dll");
                if (m) pRaise = (PFN_RaiseNotif)GetProcAddress(m, "UiaRaiseNotificationEvent");
            }
            if (pRaise) {
                hr = pRaise(prov,
                            NotificationKind_Other,
                            cur->announcePriority == 2
                                ? NotificationProcessing_ImportantAll
                                : NotificationProcessing_All,
                            cur->announceText, NULL);
            }
            if (cur->announceText) SysFreeString(cur->announceText);
        } else if (cur->kind == 2) {
            hr = UiaRaiseAutomationEvent(prov, UIA_AutomationFocusChangedEventId);
        } else if (cur->kind == 3) {
            hr = UiaRaiseStructureChangedEvent(
                prov, StructureChangeType_ChildrenInvalidated, NULL, 0);
        }
        debug_log("defer_flush: kind=%d propId=%d hr=0x%x", cur->kind, cur->propertyId, (unsigned)hr);
        IUnknown_Release((IUnknown *)prov);
        xfree(cur);
        cur = next;
    }
}

static LRESULT CALLBACK a11ySubclassProc(
    HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam,
    UINT_PTR uIdSubclass, DWORD_PTR dwRefData)
{
    (void)uIdSubclass;
    NucleusUiaProjection *p = (NucleusUiaProjection *)dwRefData;
    switch (msg) {
        case WM_GETOBJECT: {
            if (!p || !p->root) break;
            if ((DWORD)lParam != (DWORD)UiaRootObjectId) break;
            note_a11y_query(p);
            return UiaReturnRawElementProvider(
                hwnd, wParam, lParam,
                (IRawElementProviderSimple *)&p->root->lpSimpleVtbl);
        }
        case A11Y_WM_FLUSH_EVENTS: {
            defer_flush();
            return 0;
        }
        case WM_DESTROY: {
            if (p && p->root) {
                UiaReturnRawElementProvider(hwnd, 0, 0, NULL);
                UiaDisconnectProvider((IRawElementProviderSimple *)&p->root->lpSimpleVtbl);
            }
            break;
        }
    }
    return DefSubclassProc(hwnd, msg, wParam, lParam);
}

/* ── Public C API (called from Rust JNI exports in lib.rs) ────────────────── */

__declspec(dllexport) void nucleus_tao_a11y_attach_win(int64_t hwnd_handle) {
    HWND hwnd = (HWND)(uintptr_t)hwnd_handle;
    if (!hwnd || !IsWindow(hwnd)) return;
    if (getProjection(hwnd)) return; /* already attached */

    NucleusUiaProjection *p = (NucleusUiaProjection *)xalloc(sizeof(NucleusUiaProjection));
    if (!p) return;
    InitializeCriticalSection(&p->lock);
    p->hwnd = hwnd;
    p->root = root_new(hwnd, p);
    if (!p->root) {
        DeleteCriticalSection(&p->lock);
        xfree(p);
        return;
    }
    SetPropW(hwnd, PROP_PROJECTION, (HANDLE)p);
    SetWindowSubclass(hwnd, a11ySubclassProc, A11Y_SUBCLASS_ID, (DWORD_PTR)p);
}

__declspec(dllexport) void nucleus_tao_a11y_detach_win(int64_t hwnd_handle) {
    HWND hwnd = (HWND)(uintptr_t)hwnd_handle;
    if (!hwnd) return;
    NucleusUiaProjection *p = getProjection(hwnd);
    if (!p) return;
    RemoveWindowSubclass(hwnd, a11ySubclassProc, A11Y_SUBCLASS_ID);
    RemovePropW(hwnd, PROP_PROJECTION);
    EnterCriticalSection(&p->lock);
    /* Disconnect the providers from UIA so any pending client refs are released. */
    if (p->root) {
        UiaDisconnectProvider((IRawElementProviderSimple *)&p->root->lpSimpleVtbl);
    }
    byid_clear(p);
    xfree(p->roots);
    p->roots = NULL;
    p->rootCount = 0;
    NucleusUiaRoot *root = p->root;
    p->root = NULL;
    LeaveCriticalSection(&p->lock);
    if (root) {
        IUnknown_Release((IUnknown *)&root->lpSimpleVtbl);
    }
    DeleteCriticalSection(&p->lock);
    xfree(p);
}

__declspec(dllexport) int nucleus_tao_a11y_apply_snapshot_win(
    int64_t hwnd_handle, const uint8_t *bytes, size_t len)
{
    HWND hwnd = (HWND)(uintptr_t)hwnd_handle;
    NucleusUiaProjection *p = getProjection(hwnd);
    if (!p) return 0;
    return apply_snapshot(p, bytes, len) ? 1 : 0;
}

__declspec(dllexport) int nucleus_tao_a11y_is_active_win(int64_t hwnd_handle) {
    HWND hwnd = (HWND)(uintptr_t)hwnd_handle;
    NucleusUiaProjection *p = getProjection(hwnd);
    if (!p) return 0;
    LONG64 last = InterlockedCompareExchange64(&p->lastQueryTickMs, 0, 0);
    if (last == 0) return 0;
    LONG64 now = (LONG64)GetTickCount64();
    return (now - last) < ACTIVE_WINDOW_MS ? 1 : 0;
}

__declspec(dllexport) int nucleus_tao_a11y_consume_resync_win(int64_t hwnd_handle) {
    HWND hwnd = (HWND)(uintptr_t)hwnd_handle;
    NucleusUiaProjection *p = getProjection(hwnd);
    if (!p) return 0;
    return InterlockedExchange(&p->resyncRequested, 0) ? 1 : 0;
}

__declspec(dllexport) void nucleus_tao_a11y_note_pushed_win(int64_t hwnd_handle) {
    /* No-op for now; resync flag is consumed lazily. */
    (void)hwnd_handle;
}

/* DLL entry. */
BOOL WINAPI DllMain(HINSTANCE h, DWORD r, LPVOID l) {
    (void)h; (void)r; (void)l;
    return TRUE;
}
