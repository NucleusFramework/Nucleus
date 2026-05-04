// a11y.m
//
// NSAccessibility projection of Compose's SemanticsOwner for the Tao backend.
//
// Architecture (per the macOS a11y design report):
//   - The Kotlin side observes Compose's SemanticsTree and pushes a flat
//     binary snapshot (NodeDescriptor[]) to native via JNI on every change.
//   - This file owns the per-NSView projection: parses the snapshot once
//     per push, builds a stable NSDictionary<NSNumber*, NucleusA11yElement*>
//     keyed by node id, and answers AppKit accessibility queries from it.
//   - TaoView is monkey-patched (class_replaceMethod) to act as an a11y
//     group whose children are the top-level NucleusA11yElements; it
//     forwards accessibilityFocusedUIElement to the focused element.
//   - The hosting NSWindow's class is also patched once with a focus
//     forwarder (AccessKit's `add_focus_forwarder_to_window_class` pattern)
//     so AppKit lands on the contentView's a11y tree even when Tao keeps
//     the firstResponder on the NSWindow itself.
//
// Threading: every entry point runs on the macOS main thread. Snapshot push
// is funnelled through TaoMainDispatcher (Kotlin side) so we never see
// concurrent reads / writes to the projection state.

#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
#include <mach/mach_time.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

// ── Wire format v7 (must match TaoA11ySnapshotSerializer on the Kotlin side) ──
//
//   Header (24 bytes):
//     u32 magic    = 0xA110A11A
//     u16 version  = 7
//     u16 flags    (bit 0 = partial; rejected here — partials are Linux-only)
//     u32 nodeCount
//     u64 focusId  (0 = no explicit focus, fall back to root or per-node bit)
//     u32 reserved
//
//   Per node:
//     u64 nodeId
//     u64 parentId       (0 = root → child of TaoView)
//     u16 role           (NucleusA11yRole)
//     u16 flags          (NucleusA11yFlag)
//     u16 actions        (NucleusA11yAction)
//     u16 extraFlags     (bit 0 = READ_ONLY, bit 1 = INVALID)
//     f32 frameX,Y,W,H   (window-local logical points, top-left origin)
//     f32 minValue, maxValue, value
//     u32 selectionStart (UTF-16 code unit, 0 if not a text field)
//     u32 selectionEnd
//     f32 hScrollMax, hScrollValue
//     f32 vScrollMax, vScrollValue
//     u16 labelLen + label
//     u16 valueLen + value
//     u16 customCount
//       per custom action: u16 nameLen + name
//     u16 testTagLen + testTag       (Compose Modifier.testTag → AXIdentifier)
//     u32 childCount + u64 childIds  (explicit topology — partials only)
//
// All multi-byte fields little-endian.

static const uint32_t kSnapshotMagic = 0xA110A11A;
static const uint16_t kSnapshotVersion = 7;
#define NUCLEUS_A11Y_FLAG_PARTIAL 0x0001u
#define NUCLEUS_A11Y_EFLAG_READ_ONLY  0x0001u
#define NUCLEUS_A11Y_EFLAG_INVALID    0x0002u

// Forward declaration — definition lives at the bottom alongside the JNI
// entry points. Used by the TaoView swizzles to bump the "AX recently used"
// timestamp consumed by `nucleus_tao_a11y_is_active`.
static void note_a11y_query(void);

typedef NS_ENUM(uint16_t, NucleusA11yRole) {
    NucleusA11yRoleUnknown = 0,
    NucleusA11yRoleGroup,
    NucleusA11yRoleButton,
    NucleusA11yRoleStaticText,
    NucleusA11yRoleCheckbox,
    NucleusA11yRoleRadioButton,
    NucleusA11yRoleSwitch,
    NucleusA11yRoleTextField,
    NucleusA11yRoleTextArea,
    NucleusA11yRoleSlider,
    NucleusA11yRoleProgress,
    NucleusA11yRoleImage,
    NucleusA11yRoleScrollArea,
    NucleusA11yRoleHeading,
    NucleusA11yRoleTab,
    NucleusA11yRolePopupMenu,
    NucleusA11yRoleTable,
    NucleusA11yRoleOutline,
    NucleusA11yRoleRow,
    NucleusA11yRoleCell,
};

typedef NS_OPTIONS(uint16_t, NucleusA11yFlag) {
    NucleusA11yFlagIsElement       = 1 << 0,
    NucleusA11yFlagEnabled         = 1 << 1,
    NucleusA11yFlagFocused         = 1 << 2,
    NucleusA11yFlagSelected        = 1 << 3,
    NucleusA11yFlagChecked         = 1 << 4,
    NucleusA11yFlagMixed           = 1 << 5,
    NucleusA11yFlagHeading         = 1 << 6,
    NucleusA11yFlagPassword        = 1 << 7,
    NucleusA11yFlagMultiline       = 1 << 8,
    NucleusA11yFlagModal           = 1 << 9,   // IsDialog → isAccessibilityModal
    NucleusA11yFlagLiveRegionPolite    = 1 << 10,
    NucleusA11yFlagLiveRegionAssertive = 1 << 11,
};

typedef NS_OPTIONS(uint16_t, NucleusA11yAction) {
    NucleusA11yActionClick        = 1 << 0,
    NucleusA11yActionIncrement    = 1 << 1,
    NucleusA11yActionDecrement    = 1 << 2,
    NucleusA11yActionSetText      = 1 << 3,
    NucleusA11yActionRequestFocus = 1 << 4,
    NucleusA11yActionScrollUp     = 1 << 5,
    NucleusA11yActionScrollDown   = 1 << 6,
    NucleusA11yActionScrollLeft   = 1 << 7,
    NucleusA11yActionScrollRight  = 1 << 8,
    NucleusA11yActionDismiss      = 1 << 9,
};

// Forward-declared callbacks into Kotlin (defined in lib.rs). We weak-link
// them so the native dylib still loads in environments where the JNI side
// isn't wired (tests / standalone load).
__attribute__((weak)) extern void
nucleus_tao_a11y_invoke_action(int64_t ns_view_handle, uint64_t node_id, uint16_t action);

__attribute__((weak)) extern void
nucleus_tao_a11y_set_text(int64_t ns_view_handle, uint64_t node_id,
                          const char *utf8, int32_t len);

__attribute__((weak)) extern void
nucleus_tao_a11y_set_selection(int64_t ns_view_handle, uint64_t node_id,
                               int32_t start, int32_t end);

__attribute__((weak)) extern void
nucleus_tao_a11y_invoke_custom_action(int64_t ns_view_handle, uint64_t node_id,
                                      int32_t action_index);

__attribute__((weak)) extern void
nucleus_tao_a11y_scroll_by(int64_t ns_view_handle, uint64_t node_id,
                           float dx, float dy);

// ────────────────────────────────────────────────────────────────────────────
// NucleusA11yElement — backing object for one Compose semantic node.
// ────────────────────────────────────────────────────────────────────────────

@class NucleusA11yProjection;
@class NucleusA11yScrollBar;

@interface NucleusA11yElement : NSAccessibilityElement
@property(nonatomic, weak) NSView *taoView;
@property(nonatomic, weak) NucleusA11yProjection *projection;
@property(nonatomic, assign) uint64_t nodeId;
@property(nonatomic, assign) uint64_t parentId;
@property(nonatomic, assign) uint16_t role;
@property(nonatomic, assign) uint16_t flags;
@property(nonatomic, assign) uint16_t actions;
@property(nonatomic, assign) uint16_t extraFlags;      // EFLAG_READ_ONLY | EFLAG_INVALID
@property(nonatomic, assign) BOOL readOnly;            // mirror of EFLAG_READ_ONLY
@property(nonatomic, assign) BOOL invalidEntry;        // mirror of EFLAG_INVALID
@property(nonatomic, copy) NSString *testTag;          // Compose Modifier.testTag → AXIdentifier
@property(nonatomic, assign) NSRect frameInView;       // top-left origin, points
@property(nonatomic, assign) float minValue;
@property(nonatomic, assign) float maxValue;
@property(nonatomic, assign) float numericValue;
@property(nonatomic, copy) NSString *label;
@property(nonatomic, copy) NSString *valueString;
@property(nonatomic, assign) NSUInteger selectionStart;  // UTF-16 code units
@property(nonatomic, assign) NSUInteger selectionEnd;
// Scroll axes — current scroll position + total scrollable extent in
// pixels. `Max == 0` means the axis has no scroll range and no virtual
// AXScrollBar child should be exposed.
@property(nonatomic, assign) float hScrollMax;
@property(nonatomic, assign) float hScrollValue;
@property(nonatomic, assign) float vScrollMax;
@property(nonatomic, assign) float vScrollValue;
@property(nonatomic, strong) NSMutableArray<NucleusA11yElement *> *childElements;
// Custom action names (Compose `CustomAccessibilityAction.label`). The order
// is the dispatch index — invoked back via
// `nucleus_tao_a11y_invoke_custom_action(view, nodeId, index)`.
@property(nonatomic, copy) NSArray<NSString *> *customActionNames;
// Cached `NSAccessibilityCustomAction` array, lazily built from
// `customActionNames` on first read.
@property(nonatomic, strong) NSArray<NSAccessibilityCustomAction *> *customActionsCache;
@property(nonatomic, strong) NucleusA11yScrollBar *cachedHScrollBar;
@property(nonatomic, strong) NucleusA11yScrollBar *cachedVScrollBar;
@end

/**
 * Synthetic scroll-bar element backing an AXScrollBar role inside a
 * NucleusA11yElement of role ScrollArea. VoiceOver scroll commands
 * (VO+Cmd+arrow on a scroll area) translate to `setAccessibilityValue:`
 * with a value in 0..1, which we map back to a Compose `ScrollBy(dx, dy)`.
 *
 * The scroll bar's frame mirrors the parent scroll area in the orientation
 * axis and uses a 0-thickness rect on the cross axis — VoiceOver doesn't
 * render the bar visually anyway, it just needs a frame to anchor on.
 */
@interface NucleusA11yScrollBar : NSAccessibilityElement
@property(nonatomic, weak) NucleusA11yElement *owner;
@property(nonatomic, assign) BOOL horizontal;
@end

@interface NucleusA11yProjection : NSObject
@property(nonatomic, weak) NSView *taoView;
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, NucleusA11yElement *> *byId;
@property(nonatomic, strong) NSMutableArray<NucleusA11yElement *> *roots;
@property(nonatomic, assign) uint64_t focusedNodeId;
// Notification observers for backing-properties / screen change. Stored so
// we can remove them on detach.
@property(nonatomic, strong) NSMutableArray<id> *observers;
// Cached rotors + their delegates. Built lazily on first
// `accessibilityCustomRotors` query, invalidated on snapshot apply (the
// projection's element set may have changed) only via the cachedRotors=nil
// reset performed in `apply_snapshot_bytes`.
@property(nonatomic, strong) NSArray<NSAccessibilityCustomRotor *> *cachedRotors;
@property(nonatomic, strong) NSArray *rotorDelegates;
- (NSArray<NucleusA11yElement *> *)rootChildrenForView;
- (NucleusA11yElement *)focusedElement;
- (NucleusA11yElement *)hitTestPointInView:(NSPoint)pointInView;
@end

// ── Per-NSView projection store ────────────────────────────────────────────
// Keyed by the integer NSView handle so detach can run safely even after Tao
// has released the underlying view (no `(__bridge NSView *)` on a dangling
// pointer). When live AX queries arrive they come through the swizzled
// TaoView methods which ALWAYS receive a still-live `self` — only there do
// we resolve `self → handle → projection` and bridge.

static NSMutableDictionary<NSNumber *, NucleusA11yProjection *> *gProjections(void) {
    static NSMutableDictionary *d;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ d = [NSMutableDictionary new]; });
    return d;
}

static NucleusA11yProjection *projection_for_handle(int64_t handle) {
    return gProjections()[@(handle)];
}

static NucleusA11yProjection *projection_for_view(NSView *view) {
    if (!view) return nil;
    return projection_for_handle((int64_t)(uintptr_t)view);
}

static NucleusA11yProjection *ensure_projection_for_view(NSView *view) {
    if (!view) return nil;
    NSNumber *key = @((int64_t)(uintptr_t)view);
    NucleusA11yProjection *p = gProjections()[key];
    if (!p) {
        p = [NucleusA11yProjection new];
        p.taoView = view;
        p.byId = [NSMutableDictionary new];
        p.roots = [NSMutableArray new];
        gProjections()[key] = p;
    }
    return p;
}

// ────────────────────────────────────────────────────────────────────────────
// Frame conversion helpers.
//
// The wire format gives us window-local logical points with top-left origin
// (matching Compose's coordinate system). NSAccessibility wants screen-space
// points with bottom-left origin. We do the conversion lazily inside
// `accessibilityFrame` so display moves and window drags don't require us to
// re-push the snapshot.
// ────────────────────────────────────────────────────────────────────────────

static NSRect rect_to_screen(NSView *view, NSRect rectInView) {
    NSWindow *window = view.window;
    if (!window) return NSZeroRect;
    // The input rect uses Compose's top-left origin (y grows downward).
    // We need to deliver an AppKit screen rect (bottom-left, y grows upward).
    //
    // - If the source view is `isFlipped == YES`, its own coordinate space is
    //   already top-left, so we hand the rect to `convertRect:toView:nil`
    //   verbatim — AppKit reconciles flipped→non-flipped during conversion.
    // - If the source view is `isFlipped == NO` (default `NSView`, which is
    //   what Tao 0.35 ships), we must flip Y manually first so the rect
    //   matches the view's bottom-left coordinate system before conversion.
    NSRect rectInOwnSpace;
    if (view.isFlipped) {
        rectInOwnSpace = rectInView;
    } else {
        NSRect bounds = view.bounds;
        rectInOwnSpace = NSMakeRect(
            rectInView.origin.x,
            bounds.size.height - rectInView.origin.y - rectInView.size.height,
            rectInView.size.width,
            rectInView.size.height);
    }
    NSRect inWindow = [view convertRect:rectInOwnSpace toView:nil];
    return [window convertRectToScreen:inWindow];
}

// ────────────────────────────────────────────────────────────────────────────
// Role mapping.
// ────────────────────────────────────────────────────────────────────────────

static NSString *role_to_ns_role(uint16_t role) {
    switch (role) {
        case NucleusA11yRoleButton:      return NSAccessibilityButtonRole;
        case NucleusA11yRoleStaticText:  return NSAccessibilityStaticTextRole;
        case NucleusA11yRoleHeading:     return NSAccessibilityStaticTextRole;
        case NucleusA11yRoleCheckbox:    return NSAccessibilityCheckBoxRole;
        case NucleusA11yRoleSwitch:      return NSAccessibilityCheckBoxRole;
        case NucleusA11yRoleRadioButton: return NSAccessibilityRadioButtonRole;
        case NucleusA11yRoleTab:         return NSAccessibilityRadioButtonRole;
        case NucleusA11yRoleTextField:   return NSAccessibilityTextFieldRole;
        case NucleusA11yRoleTextArea:    return NSAccessibilityTextAreaRole;
        case NucleusA11yRoleSlider:      return NSAccessibilitySliderRole;
        case NucleusA11yRoleProgress:    return NSAccessibilityProgressIndicatorRole;
        case NucleusA11yRoleImage:       return NSAccessibilityImageRole;
        case NucleusA11yRoleScrollArea:  return NSAccessibilityScrollAreaRole;
        case NucleusA11yRolePopupMenu:   return NSAccessibilityPopUpButtonRole;
        case NucleusA11yRoleTable:       return NSAccessibilityTableRole;
        case NucleusA11yRoleOutline:     return NSAccessibilityOutlineRole;
        case NucleusA11yRoleRow:         return NSAccessibilityRowRole;
        case NucleusA11yRoleCell:        return NSAccessibilityCellRole;
        case NucleusA11yRoleGroup:
        default:                         return NSAccessibilityGroupRole;
    }
}

static NSString *role_to_ns_subrole(uint16_t role, uint16_t flags) {
    if (role == NucleusA11yRoleSwitch) return NSAccessibilitySwitchSubrole;
    if (role == NucleusA11yRoleTab) return @"AXTabSubrole";
    if (role == NucleusA11yRoleHeading) return @"AXHeading";
    if (role == NucleusA11yRoleTextField && (flags & NucleusA11yFlagPassword)) {
        return NSAccessibilitySecureTextFieldSubrole;
    }
    return nil;
}

// ────────────────────────────────────────────────────────────────────────────
// NucleusA11yElement implementation.
// ────────────────────────────────────────────────────────────────────────────

@implementation NucleusA11yElement

- (BOOL)isAccessibilityElement {
    return (self.flags & NucleusA11yFlagIsElement) != 0;
}

- (NSAccessibilityRole)accessibilityRole {
    return role_to_ns_role(self.role);
}

- (NSAccessibilitySubrole)accessibilitySubrole {
    return role_to_ns_subrole(self.role, self.flags);
}

- (NSString *)accessibilityRoleDescription {
    NSString *sub = [self accessibilitySubrole];
    NSString *desc = NSAccessibilityRoleDescription([self accessibilityRole], sub);
    return desc ?: [self accessibilityRole];
}

- (NSString *)accessibilityLabel {
    return self.label ?: @"";
}

- (NSString *)accessibilityTitle {
    // For elements that visually render their label (button, checkbox, tab),
    // AppKit prefers `accessibilityTitle`. For others (image, text field), it
    // prefers `accessibilityLabel`. Returning the label for both is what
    // AccessKit/Mozilla do; AppKit dedups when announcing.
    switch (self.role) {
        case NucleusA11yRoleButton:
        case NucleusA11yRoleCheckbox:
        case NucleusA11yRoleSwitch:
        case NucleusA11yRoleRadioButton:
        case NucleusA11yRoleTab:
            return self.label ?: @"";
        default:
            return nil;
    }
}

- (NSNumber *)accessibilityMinValue {
    if (self.role == NucleusA11yRoleSlider || self.role == NucleusA11yRoleProgress) {
        return @(self.minValue);
    }
    return nil;
}

- (NSNumber *)accessibilityMaxValue {
    if (self.role == NucleusA11yRoleSlider || self.role == NucleusA11yRoleProgress) {
        return @(self.maxValue);
    }
    return nil;
}

- (BOOL)isAccessibilityEnabled {
    return (self.flags & NucleusA11yFlagEnabled) != 0;
}

// Test/automation hook — Compose `Modifier.testTag(...)` round-tripped to AT
// clients. macOS's `AXIdentifier` is the primary handle for XCUITest / UI
// Automation. VoiceOver itself rarely reads it; it must always be present
// (empty when not set, never nil) so AT clients see a stable selector.
- (NSString *)accessibilityIdentifier { return self.testTag ?: @""; }

// `accessibilityInvalid` (macOS 11+) carries the `NSAccessibilityInvalidValue`
// shape: `@"true"` / `@"false"` / `@"grammar"` / `@"spelling"`. We map the
// Compose `SemanticsProperties.Error` flag to `@"true"` when set, otherwise
// returning `@"false"` keeps VoiceOver's "valid" state explicit (returning
// nil makes some VO builds skip the announcement).
- (NSString *)accessibilityInvalid {
    return self.invalidEntry ? @"true" : @"false";
}

// Modern NSAccessibility per-selector gate. AppKit calls this to decide
// which setters / getters to advertise; returning NO for the value mutators
// on a read-only text field tells VoiceOver to skip the "edit text"
// affordance and makes AT scripting reject `setAccessibilityValue:` /
// `setAccessibilitySelectedTextRange:`.
- (BOOL)isAccessibilitySelectorAllowed:(SEL)selector {
    if (self.readOnly && [self isTextElement] &&
        (selector == @selector(setAccessibilityValue:) ||
         selector == @selector(setAccessibilitySelectedText:) ||
         selector == @selector(setAccessibilitySelectedTextRange:))) {
        return NO;
    }
    return [super isAccessibilitySelectorAllowed:selector];
}

- (BOOL)isAccessibilityFocused {
    return (self.flags & NucleusA11yFlagFocused) != 0;
}

// Modal dialogs scope VoiceOver focus: with this YES, VO won't navigate to
// elements outside the dialog. Compose's `Dialog` composable carries
// `SemanticsProperties.IsDialog` which we map to this flag.
- (BOOL)isAccessibilityModal {
    return (self.flags & NucleusA11yFlagModal) != 0;
}

- (NSRect)accessibilityFrame {
    NSView *v = self.taoView;
    if (!v) return NSZeroRect;
    return rect_to_screen(v, self.frameInView);
}

- (id)accessibilityParent {
    NSView *v = self.taoView;
    if (self.parentId == 0) {
        return v ? NSAccessibilityUnignoredAncestor(v) : nil;
    }
    NucleusA11yElement *parent = self.projection.byId[@(self.parentId)];
    return parent ?: (v ? NSAccessibilityUnignoredAncestor(v) : nil);
}

- (NSArray *)accessibilityChildren {
    if (self.role != NucleusA11yRoleScrollArea) return self.childElements ?: @[];
    NSMutableArray *all = [NSMutableArray arrayWithArray:self.childElements ?: @[]];
    if (self.hScrollMax > 0 || self.vScrollMax > 0) {
        NucleusA11yScrollBar *h = [self accessibilityHorizontalScrollBar];
        NucleusA11yScrollBar *v = [self accessibilityVerticalScrollBar];
        if (h) [all addObject:h];
        if (v) [all addObject:v];
    }
    return all;
}

- (NSArray *)accessibilityChildrenInNavigationOrder {
    return [self accessibilityChildren];
}

// Lazily allocate scroll bars when the parent is a scroll area with scroll
// extent. Cached on the element so VoiceOver gets stable identity across
// queries.
- (NucleusA11yScrollBar *)accessibilityHorizontalScrollBar {
    if (self.role != NucleusA11yRoleScrollArea) return nil;
    if (self.hScrollMax <= 0) return nil;
    if (!self.cachedHScrollBar) {
        NucleusA11yScrollBar *bar = [NucleusA11yScrollBar new];
        bar.owner = self;
        bar.horizontal = YES;
        self.cachedHScrollBar = bar;
    }
    return self.cachedHScrollBar;
}

- (NucleusA11yScrollBar *)accessibilityVerticalScrollBar {
    if (self.role != NucleusA11yRoleScrollArea) return nil;
    if (self.vScrollMax <= 0) return nil;
    if (!self.cachedVScrollBar) {
        NucleusA11yScrollBar *bar = [NucleusA11yScrollBar new];
        bar.owner = self;
        bar.horizontal = NO;
        self.cachedVScrollBar = bar;
    }
    return self.cachedVScrollBar;
}

// For scroll areas, return only the children whose frames intersect the
// scroll area's own visible rect. VoiceOver uses this to skip off-screen
// items during fast navigation. For other roles AppKit defaults to
// accessibilityChildren which is what we want.
- (NSArray *)accessibilityVisibleChildren {
    if (self.role != NucleusA11yRoleScrollArea) return self.childElements ?: @[];
    NSRect viewport = self.frameInView;
    NSMutableArray *visible = [NSMutableArray new];
    for (NucleusA11yElement *child in self.childElements) {
        if (NSIntersectsRect(viewport, child.frameInView)) {
            [visible addObject:child];
        }
    }
    return visible;
}

- (id)accessibilityHitTest:(NSPoint)pointInScreen {
    NSView *v = self.taoView;
    if (!v) return self;
    // Recurse into children first (deepest match wins).
    for (NucleusA11yElement *child in self.childElements) {
        NSRect childScreen = [child accessibilityFrame];
        if (NSPointInRect(pointInScreen, childScreen)) {
            id deeper = [child accessibilityHitTest:pointInScreen];
            return deeper ?: child;
        }
    }
    return self;
}

// ── Actions ────────────────────────────────────────────────────────────────

- (BOOL)accessibilityPerformPress {
    if (self.actions & NucleusA11yActionClick) {
        if (nucleus_tao_a11y_invoke_action) {
            nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                           self.nodeId,
                                           NucleusA11yActionClick);
        }
        return YES;
    }
    return NO;
}

- (BOOL)accessibilityPerformIncrement {
    if (self.actions & NucleusA11yActionIncrement) {
        if (nucleus_tao_a11y_invoke_action) {
            nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                           self.nodeId,
                                           NucleusA11yActionIncrement);
        }
        return YES;
    }
    return NO;
}

- (BOOL)accessibilityPerformDecrement {
    if (self.actions & NucleusA11yActionDecrement) {
        if (nucleus_tao_a11y_invoke_action) {
            nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                           self.nodeId,
                                           NucleusA11yActionDecrement);
        }
        return YES;
    }
    return NO;
}

// VoiceOver / external AX clients can request focus via this setter. We
// translate the YES case into a Compose `SemanticsActions.RequestFocus`.
- (void)setAccessibilityFocused:(BOOL)focused {
    if (!focused) return;
    if (!(self.actions & NucleusA11yActionRequestFocus)) return;
    if (nucleus_tao_a11y_invoke_action) {
        nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                       self.nodeId,
                                       NucleusA11yActionRequestFocus);
    }
}

// VoiceOver scroll commands (VO+Cmd+arrow when on a scroll area) route
// through these per-direction selectors. Compose's `SemanticsActions.PageUp`
// / `PageDown` etc. cover each direction; we map them through the matching
// action bit so a single invoke_action call dispatches to the right Compose
// handler.
- (BOOL)accessibilityPerformScrollUp {
    if (!(self.actions & NucleusA11yActionScrollUp)) return NO;
    if (nucleus_tao_a11y_invoke_action) {
        nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                       self.nodeId, NucleusA11yActionScrollUp);
    }
    return YES;
}

- (BOOL)accessibilityPerformScrollDown {
    if (!(self.actions & NucleusA11yActionScrollDown)) return NO;
    if (nucleus_tao_a11y_invoke_action) {
        nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                       self.nodeId, NucleusA11yActionScrollDown);
    }
    return YES;
}

- (BOOL)accessibilityPerformScrollLeft {
    if (!(self.actions & NucleusA11yActionScrollLeft)) return NO;
    if (nucleus_tao_a11y_invoke_action) {
        nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                       self.nodeId, NucleusA11yActionScrollLeft);
    }
    return YES;
}

- (BOOL)accessibilityPerformScrollRight {
    if (!(self.actions & NucleusA11yActionScrollRight)) return NO;
    if (nucleus_tao_a11y_invoke_action) {
        nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                       self.nodeId, NucleusA11yActionScrollRight);
    }
    return YES;
}

// AppKit advertises an action as "available" whenever its `accessibilityPerform<Action>`
// selector exists on the class. We implement all of them so that one
// NSAccessibilityElement subclass can serve every role, but we want VoiceOver
// to only show actions that are actually wired for *this particular node*.
// Returning nil from `accessibilityActionDescription:` for unsupported names
// hides them from VoiceOver's action menu.
- (NSString *)accessibilityActionDescription:(NSAccessibilityActionName)action {
    BOOL supported = NO;
    if ([action isEqualToString:NSAccessibilityPressAction]) {
        supported = (self.actions & NucleusA11yActionClick) != 0;
    } else if ([action isEqualToString:NSAccessibilityIncrementAction]) {
        supported = (self.actions & NucleusA11yActionIncrement) != 0;
    } else if ([action isEqualToString:NSAccessibilityDecrementAction]) {
        supported = (self.actions & NucleusA11yActionDecrement) != 0;
    } else if ([action isEqualToString:NSAccessibilityCancelAction]) {
        supported = (self.actions & NucleusA11yActionDismiss) != 0;
    } else if ([action isEqualToString:NSAccessibilityShowMenuAction]) {
        supported = NO;
    }
    return supported ? NSAccessibilityActionDescription(action) : nil;
}

// VoiceOver dismisses dialogs via VO+Esc, which AppKit routes to
// `accessibilityPerformCancel`. We forward to Compose's `Dismiss` action.
- (BOOL)accessibilityPerformCancel {
    if (!(self.actions & NucleusA11yActionDismiss)) return NO;
    if (nucleus_tao_a11y_invoke_action) {
        nucleus_tao_a11y_invoke_action((int64_t)(uintptr_t)self.taoView,
                                       self.nodeId, NucleusA11yActionDismiss);
    }
    return YES;
}

// 10.9 contract for NSObject-backed elements: we must answer YES so AppKit
// posts NSAccessibilityUIElementDestroyedNotification for us on dealloc.
- (BOOL)accessibilityNotifiesWhenDestroyed { return YES; }

// VoiceOver invokes its rotor (VO+U) by querying the *focused* element for
// custom rotors. Each element therefore returns the projection's standard set
// (Headings + Form Controls). Without this, the rotors live only on the
// container view and VO can't reach them when a leaf is focused.
- (NSArray<NSAccessibilityCustomRotor *> *)accessibilityCustomRotors {
    return self.projection.cachedRotors ?: @[];
}

// Per-element custom actions. VoiceOver users hit VO+Cmd+. on the focused
// element to open the action menu; this is also surfaced to AT scripting via
// `AXCustomActions`. Each NSAccessibilityCustomAction wraps the Compose-side
// `CustomAccessibilityAction.label` and dispatches by index back through the
// JNI callback.
//
// Implementation note: we use the `initWithName:target:selector:` pattern
// rather than `initWithName:handler:` because the handler form is documented
// as "in-process only" — handler blocks cannot be marshalled across the
// AX/IPC boundary. With target/selector, AppKit routes the invocation back
// through our element class, and we look up the action index via the
// selector name suffix.
- (NSArray<NSAccessibilityCustomAction *> *)accessibilityCustomActions {
    if (self.customActionsCache) return self.customActionsCache;
    if (self.customActionNames.count == 0) return @[];
    NSMutableArray<NSAccessibilityCustomAction *> *built =
        [NSMutableArray arrayWithCapacity:self.customActionNames.count];
    [self.customActionNames enumerateObjectsUsingBlock:^(NSString *name, NSUInteger idx, BOOL *stop) {
        (void)stop;
        // Encode the action index in the selector name so the dispatch can
        // recover it. The action receiver method is generated dynamically
        // below via `+resolveInstanceMethod:`.
        NSString *selStr = [NSString stringWithFormat:@"_nucleusInvokeCustomAction%lu:", (unsigned long)idx];
        SEL sel = NSSelectorFromString(selStr);
        NSAccessibilityCustomAction *a = [[NSAccessibilityCustomAction alloc]
            initWithName:name
                  target:self
                selector:sel];
        [built addObject:a];
    }];
    self.customActionsCache = built;
    return built;
}

// (Reverted) Custom actions cross-process serialisation:
// Attempts to bridge via the deprecated `accessibilityAttributeValue:` /
// `accessibilityAttributeNames` path break attribute marshalling for the
// other attributes (AppKit's NSAccessibilityElement implements them via the
// modern selectors, not the legacy informal protocol). VoiceOver reads
// custom actions in-process and works correctly; cross-process AXUIElement
// reads remain limited by AppKit. This is a known boundary.

// Dynamically resolve the per-index custom-action selectors. Each one is
// `_nucleusInvokeCustomActionN:` with the action index N — we extract N
// from the selector name and dispatch through the JNI callback.
+ (BOOL)resolveInstanceMethod:(SEL)sel {
    NSString *name = NSStringFromSelector(sel);
    NSString *prefix = @"_nucleusInvokeCustomAction";
    if (![name hasPrefix:prefix] || ![name hasSuffix:@":"]) {
        return [super resolveInstanceMethod:sel];
    }
    NSString *digits = [name substringWithRange:NSMakeRange(prefix.length,
        name.length - prefix.length - 1)];
    NSInteger idx = digits.integerValue;
    IMP imp = imp_implementationWithBlock(^BOOL(NucleusA11yElement *self, id sender) {
        (void)sender;
        if (nucleus_tao_a11y_invoke_custom_action) {
            nucleus_tao_a11y_invoke_custom_action(
                (int64_t)(uintptr_t)self.taoView, self.nodeId, (int32_t)idx);
        }
        return YES;
    });
    class_addMethod([self class], sel, imp, "B@:@");
    return YES;
}

// ── NSAccessibilityNavigableStaticText (text fields & areas) ──────────────
//
// The minimum methods VoiceOver needs to navigate a text field with VO+arrow:
//   - accessibilityNumberOfCharacters    (length in UTF-16 code units)
//   - accessibilityRangeForLine:         (single-line: line 0 = whole text)
//   - accessibilityLineForIndex:         (always 0 for single-line)
//   - accessibilityStringForRange:       (substring access)
//   - accessibilitySelectedTextRange     (caret + selection)
//   - accessibilityInsertionPointLineNumber
// Multi-line wrap support is left for a follow-up; AppKit announces the
// whole text on focus regardless, which is enough for stock TextField UX.

- (BOOL)isTextElement {
    return self.role == NucleusA11yRoleTextField || self.role == NucleusA11yRoleTextArea;
}

- (NSInteger)accessibilityNumberOfCharacters {
    if (![self isTextElement]) return 0;
    return (NSInteger)(self.valueString.length);
}

// Builds the table of line ranges (start, length) by scanning valueString for
// `\n`. The terminating newline ends the previous line; an empty trailing
// line is reported when the string ends with `\n` (matches Cocoa's convention
// for `NSLayoutManager.rangeOfLineForCharacterAtIndex:`).
- (NSArray<NSValue *> *)_lineRanges {
    NSString *s = self.valueString ?: @"";
    NSMutableArray<NSValue *> *ranges = [NSMutableArray new];
    NSUInteger len = s.length;
    NSUInteger lineStart = 0;
    for (NSUInteger i = 0; i < len; i++) {
        unichar c = [s characterAtIndex:i];
        if (c == '\n') {
            [ranges addObject:[NSValue valueWithRange:NSMakeRange(lineStart, i - lineStart)]];
            lineStart = i + 1;
        }
    }
    // Trailing line (or the only line if no `\n` present).
    [ranges addObject:[NSValue valueWithRange:NSMakeRange(lineStart, len - lineStart)]];
    return ranges;
}

- (NSRange)accessibilityRangeForLine:(NSInteger)line {
    if (![self isTextElement]) return NSMakeRange(NSNotFound, 0);
    NSArray<NSValue *> *ranges = [self _lineRanges];
    if (line < 0 || (NSUInteger)line >= ranges.count) return NSMakeRange(NSNotFound, 0);
    return [ranges[line] rangeValue];
}

- (NSInteger)accessibilityLineForIndex:(NSInteger)index {
    if (![self isTextElement]) return -1;
    NSUInteger idx = (NSUInteger)index;
    if (index < 0 || idx > self.valueString.length) return -1;
    NSArray<NSValue *> *ranges = [self _lineRanges];
    for (NSUInteger i = 0; i < ranges.count; i++) {
        NSRange r = [ranges[i] rangeValue];
        // A position at the very end of a line (on its trailing `\n`) is
        // reported as belonging to that line, matching NSTextView's behavior.
        if (idx >= r.location && idx <= r.location + r.length) return (NSInteger)i;
    }
    return (NSInteger)(ranges.count - 1);
}

- (NSString *)accessibilityStringForRange:(NSRange)range {
    if (![self isTextElement]) return nil;
    NSString *s = self.valueString ?: @"";
    if (range.location > s.length) return @"";
    NSUInteger maxLen = s.length - range.location;
    NSRange clamped = NSMakeRange(range.location, MIN(range.length, maxLen));
    return [s substringWithRange:clamped];
}

- (NSAttributedString *)accessibilityAttributedStringForRange:(NSRange)range {
    NSString *plain = [self accessibilityStringForRange:range];
    if (!plain) return nil;
    return [[NSAttributedString alloc] initWithString:plain];
}

- (NSRange)accessibilitySelectedTextRange {
    if (![self isTextElement]) return NSMakeRange(NSNotFound, 0);
    NSUInteger start = self.selectionStart;
    NSUInteger end   = self.selectionEnd;
    if (end < start) end = start;
    return NSMakeRange(start, end - start);
}

// VoiceOver places the caret / extends the selection via this setter (e.g.
// VO+arrow during text navigation). We forward to Compose's
// `SemanticsActions.SetSelection`. Compose mutates state, our snapshot
// observer will push the new selection back through the wire format on the
// next recomposition.
- (void)setAccessibilitySelectedTextRange:(NSRange)range {
    if (![self isTextElement]) return;
    if (self.readOnly) return;
    if (nucleus_tao_a11y_set_selection) {
        int32_t start = (int32_t)range.location;
        int32_t end = (int32_t)(range.location + range.length);
        nucleus_tao_a11y_set_selection((int64_t)(uintptr_t)self.taoView,
                                       self.nodeId, start, end);
    }
}

- (NSString *)accessibilitySelectedText {
    return [self accessibilityStringForRange:[self accessibilitySelectedTextRange]];
}

- (NSInteger)accessibilityInsertionPointLineNumber {
    if (![self isTextElement]) return -1;
    return [self accessibilityLineForIndex:(NSInteger)self.selectionStart];
}

- (NSRange)accessibilityVisibleCharacterRange {
    if (![self isTextElement]) return NSMakeRange(NSNotFound, 0);
    return NSMakeRange(0, self.valueString.length);
}

// Per-line bounding rect approximation. Without a Compose `TextLayoutResult`
// in the wire format we can't pinpoint individual glyphs, but we *can* tell
// VoiceOver which line is being read by:
//   - splitting the element's frame vertically by line count
//   - within a line, returning a horizontally-proportional sub-rect based on
//     the substring's character index range
// This is enough for VO line-tracking ("VO+arrow next line" highlights the
// row) without claiming sub-pixel precision.
- (NSRect)accessibilityFrameForRange:(NSRange)range {
    if (![self isTextElement]) return NSZeroRect;
    NSArray<NSValue *> *lines = [self _lineRanges];
    if (lines.count == 0) return NSZeroRect;
    NSRect frame = self.frameInView;
    if (range.location > self.valueString.length) return NSZeroRect;
    // Find the first and last line touched by `range`.
    NSInteger firstLine = -1, lastLine = -1;
    for (NSUInteger i = 0; i < lines.count; i++) {
        NSRange lr = [lines[i] rangeValue];
        NSUInteger lineEnd = lr.location + lr.length;
        NSUInteger rangeEnd = range.location + range.length;
        BOOL touches = (range.location <= lineEnd) && (rangeEnd >= lr.location);
        if (touches) {
            if (firstLine < 0) firstLine = (NSInteger)i;
            lastLine = (NSInteger)i;
        }
    }
    if (firstLine < 0) return NSZeroRect;
    CGFloat lineHeight = frame.size.height / lines.count;
    CGFloat top = frame.origin.y + lineHeight * firstLine;
    CGFloat height = lineHeight * (lastLine - firstLine + 1);
    NSRect localRect = NSMakeRect(frame.origin.x, top, frame.size.width, height);
    NSView *v = self.taoView;
    return v ? rect_to_screen(v, localRect) : NSZeroRect;
}

// VO uses this when reading text: returns the screen rect of the substring at
// `range`. We map to the per-line approximation above.
- (NSRect)accessibilityBoundsForRange:(NSRange)range {
    return [self accessibilityFrameForRange:range];
}

// Inverse: given a screen point, find the character index it maps to. We
// approximate using line-height / line-width division. Good enough for VO+
// rotor "Read From Cursor" landing on the right paragraph.
// IME marked-text protocol stubs. The actual marked-text plumbing lives in
// `main_thread_dispatch.m` (NucleusTextOverlay forwards setMarkedText: to
// Tao). VoiceOver reads `accessibilityMarkedRange` to know if text is being
// composed (e.g. CJK IME); we return NSNotFound to mean "no active
// composition" — Compose doesn't surface an explicit composition range as a
// SemanticsProperty, so a fully reactive marked-text announce would need to
// pipe state from NucleusTextOverlay back through the wire format. The stub
// is correct (no false-positive announcements) and prevents -25200 errors
// when AT clients query the attribute on a text field.
- (NSRange)accessibilityMarkedRange {
    return NSMakeRange(NSNotFound, 0);
}

- (NSAttributedString *)accessibilityAttributedStringForMarkedRange:(NSRange)range {
    (void)range;
    return nil;
}

- (NSRange)accessibilityRangeForPosition:(NSPoint)point {
    if (![self isTextElement]) return NSMakeRange(NSNotFound, 0);
    NSView *v = self.taoView;
    if (!v) return NSMakeRange(NSNotFound, 0);
    NSWindow *window = v.window;
    if (!window) return NSMakeRange(NSNotFound, 0);
    NSPoint pInWindow = [window convertPointFromScreen:point];
    NSPoint pInView = [v convertPoint:pInWindow fromView:nil];
    if (!v.isFlipped) pInView.y = v.bounds.size.height - pInView.y;
    NSRect frame = self.frameInView;
    if (!NSPointInRect(pInView, frame)) return NSMakeRange(NSNotFound, 0);
    NSArray<NSValue *> *lines = [self _lineRanges];
    if (lines.count == 0) return NSMakeRange(0, 0);
    CGFloat localY = pInView.y - frame.origin.y;
    CGFloat lineHeight = frame.size.height / lines.count;
    NSInteger lineIdx = (NSInteger)(localY / lineHeight);
    if (lineIdx < 0) lineIdx = 0;
    if (lineIdx >= (NSInteger)lines.count) lineIdx = (NSInteger)lines.count - 1;
    NSRange lineRange = [lines[lineIdx] rangeValue];
    CGFloat localX = pInView.x - frame.origin.x;
    CGFloat fracX = frame.size.width > 0 ? (localX / frame.size.width) : 0;
    if (fracX < 0) fracX = 0; else if (fracX > 1) fracX = 1;
    NSUInteger charInLine = (NSUInteger)(fracX * lineRange.length);
    return NSMakeRange(lineRange.location + charInLine, 1);
}

- (id)accessibilityValue { return [self _axValue]; }

// Note: the redirect through `_axValue` keeps the existing role-driven
// implementation intact while letting `setAccessibilityValue:` know which
// shape to expect.
- (id)_axValue {
    switch (self.role) {
        case NucleusA11yRoleCheckbox:
        case NucleusA11yRoleSwitch:
        case NucleusA11yRoleRadioButton:
            if (self.flags & NucleusA11yFlagMixed) return @2;
            return (self.flags & NucleusA11yFlagChecked) ? @1 : @0;
        case NucleusA11yRoleSlider:
        case NucleusA11yRoleProgress:
            return @(self.numericValue);
        case NucleusA11yRoleHeading:
            return @(1);
        case NucleusA11yRoleTextField:
        case NucleusA11yRoleTextArea:
            return self.valueString ?: @"";
        default:
            return self.valueString.length > 0 ? self.valueString : nil;
    }
}

- (void)setAccessibilityValue:(id)newValue {
    if (![self isTextElement]) return;
    if (self.readOnly) return;
    if (!(self.actions & NucleusA11yActionSetText)) return;
    NSString *str = nil;
    if ([newValue isKindOfClass:[NSString class]]) {
        str = newValue;
    } else if ([newValue isKindOfClass:[NSAttributedString class]]) {
        str = [newValue string];
    }
    if (!str) return;
    if (nucleus_tao_a11y_set_text) {
        const char *utf8 = [str UTF8String] ?: "";
        int32_t bytes = (int32_t)strlen(utf8);
        nucleus_tao_a11y_set_text((int64_t)(uintptr_t)self.taoView,
                                  self.nodeId, utf8, bytes);
    }
}

@end

// ────────────────────────────────────────────────────────────────────────────
// NucleusA11yScrollBar implementation.
// ────────────────────────────────────────────────────────────────────────────

@implementation NucleusA11yScrollBar

- (BOOL)isAccessibilityElement { return YES; }
- (NSAccessibilityRole)accessibilityRole { return NSAccessibilityScrollBarRole; }
- (NSAccessibilityOrientation)accessibilityOrientation {
    return self.horizontal ? NSAccessibilityOrientationHorizontal
                           : NSAccessibilityOrientationVertical;
}

- (NSString *)accessibilityRoleDescription {
    return NSAccessibilityRoleDescription(NSAccessibilityScrollBarRole, nil);
}

- (NSRect)accessibilityFrame {
    NucleusA11yElement *o = self.owner;
    if (!o) return NSZeroRect;
    NSRect parent = [o accessibilityFrame];
    // Place the scroll bar along the bottom (horizontal) or right (vertical)
    // edge with a token thickness — VoiceOver doesn't render it, only uses
    // the rect for hit-test fallback / focus visualization.
    const CGFloat thickness = 12.0;
    if (self.horizontal) {
        return NSMakeRect(parent.origin.x,
                          parent.origin.y - thickness,
                          parent.size.width, thickness);
    } else {
        return NSMakeRect(parent.origin.x + parent.size.width,
                          parent.origin.y,
                          thickness, parent.size.height);
    }
}

- (id)accessibilityParent {
    return self.owner ? NSAccessibilityUnignoredAncestor(self.owner) : nil;
}

- (NSNumber *)accessibilityValue {
    NucleusA11yElement *o = self.owner;
    if (!o) return @0;
    float total = self.horizontal ? o.hScrollMax : o.vScrollMax;
    float cur   = self.horizontal ? o.hScrollValue : o.vScrollValue;
    if (total <= 0) return @0;
    float frac = cur / total;
    if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
    return @(frac);
}

- (NSNumber *)accessibilityMinValue { return @0; }
- (NSNumber *)accessibilityMaxValue { return @1; }

- (void)setAccessibilityValue:(id)newValue {
    NucleusA11yElement *o = self.owner;
    if (!o) return;
    float frac = 0;
    if ([newValue isKindOfClass:[NSNumber class]]) frac = [newValue floatValue];
    if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
    float total = self.horizontal ? o.hScrollMax : o.vScrollMax;
    float cur   = self.horizontal ? o.hScrollValue : o.vScrollValue;
    if (total <= 0) return;
    float target = frac * total;
    float delta = target - cur;
    // Precise scroll: send the exact delta through `nucleus_tao_a11y_scroll_by`,
    // which Compose dispatches to `SemanticsActions.ScrollBy(dx, dy)`. This
    // gives VoiceOver an absolute setValue rather than a directional page step.
    if (nucleus_tao_a11y_scroll_by) {
        float dx = self.horizontal ? delta : 0.0f;
        float dy = self.horizontal ? 0.0f : delta;
        nucleus_tao_a11y_scroll_by((int64_t)(uintptr_t)o.taoView, o.nodeId, dx, dy);
    }
}

- (BOOL)accessibilityNotifiesWhenDestroyed { return YES; }

@end

// ────────────────────────────────────────────────────────────────────────────
// NucleusA11yProjection implementation.
// ────────────────────────────────────────────────────────────────────────────

@implementation NucleusA11yProjection

- (NSArray<NucleusA11yElement *> *)rootChildrenForView {
    return self.roots ?: @[];
}

- (NucleusA11yElement *)focusedElement {
    if (self.focusedNodeId == 0) return nil;
    return self.byId[@(self.focusedNodeId)];
}

- (NucleusA11yElement *)hitTestPointInView:(NSPoint)pointInView {
    // Walk top-down; deepest hit wins.
    NucleusA11yElement *best = nil;
    for (NucleusA11yElement *root in self.roots) {
        if (NSPointInRect(pointInView, root.frameInView)) {
            best = root;
            // Recurse.
            BOOL descended = YES;
            while (descended) {
                descended = NO;
                for (NucleusA11yElement *child in best.childElements) {
                    if (NSPointInRect(pointInView, child.frameInView)) {
                        best = child;
                        descended = YES;
                        break;
                    }
                }
            }
        }
    }
    return best;
}

@end

// ────────────────────────────────────────────────────────────────────────────
// Wire-format parser.
// ────────────────────────────────────────────────────────────────────────────

#define READ_OR_FAIL(dst, n) \
    do { if (offset + (n) > len) return NO; memcpy((dst), bytes + offset, (n)); offset += (n); } while (0)

// Diffing parser. Reuses element pointers across pushes (so VoiceOver's
// element identity survives), then emits the minimal set of notifications:
//   - NSAccessibilityCreated         for new elements
//   - NSAccessibilityUIElementDestroyed for removed elements (10.9 contract,
//                                       paired with `accessibilityNotifiesWhenDestroyed`)
//   - NSAccessibilityValueChanged    when value / numericValue changed
//   - NSAccessibilityTitleChanged    when label changed
//   - NSAccessibilityLayoutChanged   on the root if any frame changed
// Mirrors the AccessKit `QueuedEvents` model: collect during parse, post
// once at the end so VoiceOver sees a coherent batched state change.
static BOOL apply_snapshot_bytes(NucleusA11yProjection *proj,
                                 const uint8_t *bytes,
                                 size_t len) {
    size_t offset = 0;
    uint32_t magic = 0;
    READ_OR_FAIL(&magic, 4);
    if (magic != kSnapshotMagic) return NO;
    uint16_t version = 0, headerFlags = 0;
    READ_OR_FAIL(&version, 2);
    READ_OR_FAIL(&headerFlags, 2);
    if (version != kSnapshotVersion) return NO;
    uint32_t nodeCount = 0;
    READ_OR_FAIL(&nodeCount, 4);
    uint64_t headerFocusId = 0;
    READ_OR_FAIL(&headerFocusId, 8);
    uint32_t headerReserved = 0;
    READ_OR_FAIL(&headerReserved, 4);
    (void)headerReserved;

    // Partial updates are Linux-only on this branch. The Kotlin controller
    // only emits them via `nativeA11yApplyPartialSnapshot`, which is a
    // no-op on macOS — but defensive logging keeps future encoder drift
    // visible during dev.
    if (headerFlags & NUCLEUS_A11Y_FLAG_PARTIAL) {
        NSLog(@"[nucleus.a11y] full apply rejected: buffer carries FLAG_PARTIAL");
        return NO;
    }

    NSMutableDictionary<NSNumber *, NucleusA11yElement *> *previous = proj.byId;
    NSMutableDictionary<NSNumber *, NucleusA11yElement *> *next =
        [NSMutableDictionary dictionaryWithCapacity:nodeCount];
    NSMutableArray<NucleusA11yElement *> *roots = [NSMutableArray new];

    // Queued notifications — flushed after the whole snapshot is consistent.
    NSMutableArray<NucleusA11yElement *> *valueChanged = [NSMutableArray new];
    NSMutableArray<NucleusA11yElement *> *titleChanged = [NSMutableArray new];
    NSMutableArray<NucleusA11yElement *> *createdNodes = [NSMutableArray new];
    // Live-region announcements collected during the parse pass. Each entry is
    // (priority, text). Flushed at the end via NSAccessibilityAnnouncementRequested.
    NSMutableArray<NSDictionary *> *announcements = [NSMutableArray new];
    BOOL anyFrameChanged = NO;
    BOOL selectionSetChanged = NO;
    uint64_t previousFocusedId = proj.focusedNodeId;
    uint64_t newFocusedId = 0;

    // First pass: parse, reuse-or-create, diff against previous values.
    for (uint32_t i = 0; i < nodeCount; i++) {
        uint64_t nodeId = 0, parentId = 0;
        uint16_t role = 0, flags = 0, actions = 0, extraFlags = 0;
        float frame[4] = {0};
        float range[3] = {0};
        uint16_t labelLen = 0, valueLen = 0;
        READ_OR_FAIL(&nodeId, 8);
        READ_OR_FAIL(&parentId, 8);
        READ_OR_FAIL(&role, 2);
        READ_OR_FAIL(&flags, 2);
        READ_OR_FAIL(&actions, 2);
        READ_OR_FAIL(&extraFlags, 2);
        READ_OR_FAIL(frame, sizeof(frame));
        READ_OR_FAIL(range, sizeof(range));
        uint32_t selStart = 0, selEnd = 0;
        READ_OR_FAIL(&selStart, 4);
        READ_OR_FAIL(&selEnd, 4);
        float scroll[4] = {0};
        READ_OR_FAIL(scroll, sizeof(scroll));
        READ_OR_FAIL(&labelLen, 2);
        if (offset + labelLen > len) return NO;
        NSString *label = [[NSString alloc] initWithBytes:bytes + offset
                                                   length:labelLen
                                                 encoding:NSUTF8StringEncoding] ?: @"";
        offset += labelLen;
        READ_OR_FAIL(&valueLen, 2);
        if (offset + valueLen > len) return NO;
        NSString *valueStr = [[NSString alloc] initWithBytes:bytes + offset
                                                      length:valueLen
                                                    encoding:NSUTF8StringEncoding] ?: @"";
        offset += valueLen;
        // Custom actions: count + per-action UTF-8 name.
        uint16_t customCount = 0;
        READ_OR_FAIL(&customCount, 2);
        NSMutableArray<NSString *> *customNames = nil;
        if (customCount > 0) {
            customNames = [NSMutableArray arrayWithCapacity:customCount];
            for (uint16_t k = 0; k < customCount; k++) {
                uint16_t nameLen = 0;
                READ_OR_FAIL(&nameLen, 2);
                if (offset + nameLen > len) return NO;
                NSString *nm = [[NSString alloc] initWithBytes:bytes + offset
                                                        length:nameLen
                                                      encoding:NSUTF8StringEncoding] ?: @"";
                offset += nameLen;
                [customNames addObject:nm];
            }
        }

        // testTag (Compose `Modifier.testTag`) — exposed as `AXIdentifier`
        // for XCUITest / UI Automation. Always present in v7 (length 0 when
        // unset).
        uint16_t testTagLen = 0;
        READ_OR_FAIL(&testTagLen, 2);
        if (offset + testTagLen > len) return NO;
        NSString *testTag = [[NSString alloc] initWithBytes:bytes + offset
                                                     length:testTagLen
                                                   encoding:NSUTF8StringEncoding] ?: @"";
        offset += testTagLen;

        // Explicit children list (v7+). We still derive topology from the
        // `parentId` linkage in pass 2 below, but the bytes must be consumed
        // here to keep the cursor aligned with subsequent records.
        uint32_t childCount = 0;
        READ_OR_FAIL(&childCount, 4);
        if (offset + (size_t)childCount * 8 > len) return NO;
        offset += (size_t)childCount * 8;

        NSNumber *key = @(nodeId);
        NucleusA11yElement *el = previous[key];
        BOOL wasNew = (el == nil);
        if (wasNew) {
            el = [NucleusA11yElement new];
            el.nodeId = nodeId;
        } else {
            // Diff *before* mutating the element.
            NSRect newFrame = NSMakeRect(frame[0], frame[1], frame[2], frame[3]);
            if (!NSEqualRects(el.frameInView, newFrame)) anyFrameChanged = YES;
            BOOL labelDiff = ![el.label isEqualToString:label];
            BOOL valueDiff = (el.numericValue != range[2]) ||
                             ![el.valueString isEqualToString:valueStr];
            if (labelDiff) [titleChanged addObject:el];
            if (valueDiff) [valueChanged addObject:el];
            // Selected toggled? Track at the projection level so the post-loop
            // pass can fire a single SelectedChildrenChanged on the root.
            BOOL prevSelected = (el.flags & NucleusA11yFlagSelected) != 0;
            BOOL nextSelected = (flags & NucleusA11yFlagSelected) != 0;
            if (prevSelected != nextSelected) selectionSetChanged = YES;
            // Live region: when the announceable text (label/value) of a
            // LiveRegion node changes, queue an announcement at the matching
            // priority. We announce the new value if present, else the label.
            BOOL isLive = (flags & (NucleusA11yFlagLiveRegionPolite |
                                    NucleusA11yFlagLiveRegionAssertive)) != 0;
            if (isLive && (labelDiff || valueDiff)) {
                NSString *text = (valueStr.length > 0 ? valueStr : label);
                if (text.length > 0) {
                    NSAccessibilityPriorityLevel pri =
                        (flags & NucleusA11yFlagLiveRegionAssertive)
                            ? NSAccessibilityPriorityHigh
                            : NSAccessibilityPriorityMedium;
                    [announcements addObject:@{
                        NSAccessibilityAnnouncementKey: text,
                        NSAccessibilityPriorityKey: @(pri),
                    }];
                }
            }
        }
        el.taoView = proj.taoView;
        el.projection = proj;
        el.parentId = parentId;
        el.role = role;
        el.flags = flags;
        el.actions = actions;
        el.extraFlags = extraFlags;
        el.readOnly = (extraFlags & NUCLEUS_A11Y_EFLAG_READ_ONLY) != 0;
        el.invalidEntry = (extraFlags & NUCLEUS_A11Y_EFLAG_INVALID) != 0;
        el.testTag = testTag;
        el.frameInView = NSMakeRect(frame[0], frame[1], frame[2], frame[3]);
        el.minValue = range[0];
        el.maxValue = range[1];
        el.numericValue = range[2];
        el.label = label;
        el.valueString = valueStr;
        el.selectionStart = selStart;
        el.selectionEnd = selEnd;
        el.hScrollMax = scroll[0]; el.hScrollValue = scroll[1];
        el.vScrollMax = scroll[2]; el.vScrollValue = scroll[3];
        // Replace customActions only if the names list changed; this keeps
        // VoiceOver's identity stable on the NSAccessibilityCustomAction
        // objects across snapshot pushes when nothing custom changed.
        if (![el.customActionNames isEqualToArray:customNames]) {
            el.customActionNames = customNames;
            el.customActionsCache = nil;
        }
        el.childElements = [NSMutableArray new];
        next[key] = el;
        if (wasNew) [createdNodes addObject:el];
        if (flags & NucleusA11yFlagFocused) {
            newFocusedId = nodeId;
        }
    }
    // The header's `focusId` is authoritative when the encoder set it; the
    // per-node FOCUSED bit is the v4-era fallback for old encoders or for
    // snapshots emitted before focus is established.
    if (headerFocusId != 0 && next[@(headerFocusId)] != nil) {
        newFocusedId = headerFocusId;
    }
    proj.focusedNodeId = newFocusedId;

    // Second pass: link parent → children using the buffer's traversal order
    // (Kotlin emits parents before children via DFS, which lets us link in
    // one re-scan). Re-parsing is cheap; the alternative would be a parallel
    // ordering array allocated in pass 1.
    {
        size_t off2 = 24; // skip v7 header (magic + version + flags + count + focusId + reserved)
        for (uint32_t i = 0; i < nodeCount; i++) {
            uint64_t nodeId = 0, parentId = 0;
            memcpy(&nodeId, bytes + off2, 8); off2 += 8;
            memcpy(&parentId, bytes + off2, 8); off2 += 8;
            off2 += 36; // role + flags + actions + extraFlags + frame + range
            off2 += 8;  // selectionStart + selectionEnd
            off2 += 16; // hScrollMax + hScrollValue + vScrollMax + vScrollValue
            uint16_t labelLen = 0;
            memcpy(&labelLen, bytes + off2, 2); off2 += 2;
            off2 += labelLen;
            uint16_t valueLen = 0;
            memcpy(&valueLen, bytes + off2, 2); off2 += 2;
            off2 += valueLen;
            uint16_t customCount = 0;
            memcpy(&customCount, bytes + off2, 2); off2 += 2;
            for (uint16_t k = 0; k < customCount; k++) {
                uint16_t nameLen = 0;
                memcpy(&nameLen, bytes + off2, 2); off2 += 2;
                off2 += nameLen;
            }
            // testTag (v7+) — read & skip; topology still derived from parentId.
            uint16_t testTagLen = 0;
            memcpy(&testTagLen, bytes + off2, 2); off2 += 2;
            off2 += testTagLen;
            // Explicit children list (v7+) — same: read & skip.
            uint32_t childCount = 0;
            memcpy(&childCount, bytes + off2, 4); off2 += 4;
            off2 += (size_t)childCount * 8;

            NucleusA11yElement *el = next[@(nodeId)];
            if (!el) continue;
            if (parentId == 0) {
                [roots addObject:el];
            } else {
                NucleusA11yElement *parent = next[@(parentId)];
                if (parent) {
                    [parent.childElements addObject:el];
                } else {
                    [roots addObject:el];
                }
            }
        }
    }

    // Compute the "removed" set: elements that existed before but not now.
    NSMutableArray<NucleusA11yElement *> *removed = [NSMutableArray new];
    for (NSNumber *prevKey in previous) {
        if (next[prevKey] == nil) {
            NucleusA11yElement *gone = previous[prevKey];
            if (gone) [removed addObject:gone];
        }
    }

    // Commit the new state before posting notifications so that VoiceOver,
    // when it reacts to one of the events by re-querying the tree, sees the
    // post-update state.
    proj.byId = next;
    proj.roots = roots;

    // ── Flush queued notifications ─────────────────────────────────────────
    NSView *liveView = proj.taoView;
    BOOL canPost = liveView != nil && liveView.window != nil;

    for (NucleusA11yElement *el in createdNodes) {
        if (canPost) NSAccessibilityPostNotification(el, NSAccessibilityCreatedNotification);
    }
    for (NucleusA11yElement *el in removed) {
        // Pair with `accessibilityNotifiesWhenDestroyed = YES` — required
        // since 10.9 for NSObject-backed (i.e. non-NSView-backed) elements.
        // Sever back-pointers so any straggling AppKit query returns safe
        // defaults instead of dereferencing the now-orphan element.
        NSAccessibilityPostNotification(el, NSAccessibilityUIElementDestroyedNotification);
        el.taoView = nil;
        el.projection = nil;
        [el.childElements removeAllObjects];
    }
    for (NucleusA11yElement *el in valueChanged) {
        if (canPost) NSAccessibilityPostNotification(el, NSAccessibilityValueChangedNotification);
    }
    for (NucleusA11yElement *el in titleChanged) {
        if (canPost) NSAccessibilityPostNotification(el, NSAccessibilityTitleChangedNotification);
    }
    if (anyFrameChanged && canPost) {
        // Single layout-changed on the root view is enough — VoiceOver
        // re-queries frames it has cached. Posting per-element would
        // pessimise the announcement queue without extra information.
        NSAccessibilityPostNotification(liveView, NSAccessibilityLayoutChangedNotification);
    }
    // Focus change: post on the new focused element if one exists, else on
    // the view (which means "no focus" — VoiceOver lands on the window).
    if (canPost && newFocusedId != previousFocusedId) {
        NucleusA11yElement *focused = newFocusedId != 0 ? next[@(newFocusedId)] : nil;
        id target = focused ?: (id)liveView;
        NSAccessibilityPostNotification(target,
                                        NSAccessibilityFocusedUIElementChangedNotification);
    }
    if (canPost && selectionSetChanged) {
        // Posting on the root view triggers VO to re-query selection across
        // any container that owns selectable items (radio groups, tab bars,
        // list-style components).
        NSAccessibilityPostNotification(liveView,
                                        NSAccessibilitySelectedChildrenChangedNotification);
    }
    // Flush live-region announcements. The notification target should be the
    // window so VoiceOver picks up the announcement regardless of focus.
    if (canPost && announcements.count > 0) {
        NSWindow *win = liveView.window;
        for (NSDictionary *ann in announcements) {
            NSAccessibilityPostNotificationWithUserInfo(
                win, NSAccessibilityAnnouncementRequestedNotification, ann);
        }
    }
    return YES;
}

// ────────────────────────────────────────────────────────────────────────────
// Custom rotor delegate — generic next/prev finder over a filtered subset of
// a projection's elements, sorted in document (visual) order.
// ────────────────────────────────────────────────────────────────────────────

typedef BOOL (^NucleusElementPredicate)(NucleusA11yElement *);

@interface NucleusRotorDelegate : NSObject <NSAccessibilityCustomRotorItemSearchDelegate>
@property(nonatomic, weak) NucleusA11yProjection *projection;
@property(nonatomic, copy) NucleusElementPredicate predicate;
@end

@implementation NucleusRotorDelegate

- (nullable NSAccessibilityCustomRotorItemResult *)rotor:(NSAccessibilityCustomRotor *)rotor
                              resultForSearchParameters:(NSAccessibilityCustomRotorSearchParameters *)p
{
    (void)rotor;
    NucleusA11yProjection *proj = self.projection;
    if (!proj) return nil;

    // Collect every element whose taoView is alive and that matches the
    // rotor's filter. Sort by visual order (top-to-bottom, then left-to-right
    // within the same row) — this matches how VoiceOver presents results.
    NSMutableArray<NucleusA11yElement *> *matches = [NSMutableArray new];
    for (NucleusA11yElement *el in proj.byId.allValues) {
        if (self.predicate && self.predicate(el)) {
            [matches addObject:el];
        }
    }
    [matches sortUsingComparator:^NSComparisonResult(NucleusA11yElement *a, NucleusA11yElement *b) {
        CGFloat ya = a.frameInView.origin.y, yb = b.frameInView.origin.y;
        if (fabs(ya - yb) > 1.0) return ya < yb ? NSOrderedAscending : NSOrderedDescending;
        CGFloat xa = a.frameInView.origin.x, xb = b.frameInView.origin.x;
        if (xa == xb) return NSOrderedSame;
        return xa < xb ? NSOrderedAscending : NSOrderedDescending;
    }];
    if (matches.count == 0) return nil;

    id current = p.currentItem;
    BOOL forward = (p.searchDirection == NSAccessibilityCustomRotorSearchDirectionNext);
    NSUInteger currentIdx = current ? [matches indexOfObjectIdenticalTo:current] : NSNotFound;

    NSUInteger targetIdx;
    if (currentIdx == NSNotFound) {
        targetIdx = forward ? 0 : matches.count - 1;
    } else if (forward) {
        if (currentIdx + 1 >= matches.count) return nil;
        targetIdx = currentIdx + 1;
    } else {
        if (currentIdx == 0) return nil;
        targetIdx = currentIdx - 1;
    }
    return [[NSAccessibilityCustomRotorItemResult alloc]
        initWithTargetElement:(id<NSAccessibilityElement>)matches[targetIdx]];
}

@end

// ────────────────────────────────────────────────────────────────────────────
// TaoView a11y root: swizzles applied once.
// ────────────────────────────────────────────────────────────────────────────

static BOOL tao_view_is_accessibility_element(id self, SEL _cmd) {
    (void)self; (void)_cmd;
    // The container itself isn't an a11y element — its children are. AppKit
    // descends through it to the NucleusA11yElement leaves.
    return NO;
}

static NSAccessibilityRole tao_view_accessibility_role(id self, SEL _cmd) {
    (void)self; (void)_cmd;
    return NSAccessibilityGroupRole;
}

static NSArray *tao_view_accessibility_children(id self, SEL _cmd) {
    (void)_cmd;
    note_a11y_query();
    NucleusA11yProjection *proj = projection_for_view((NSView *)self);
    if (!proj) return @[];
    return NSAccessibilityUnignoredChildren([proj rootChildrenForView]);
}

static id tao_view_accessibility_focused_ui_element(id self, SEL _cmd) {
    (void)_cmd;
    note_a11y_query();
    NucleusA11yProjection *proj = projection_for_view((NSView *)self);
    NucleusA11yElement *focused = [proj focusedElement];
    return focused ?: self;
}

// Returns the rotors VoiceOver navigates via VO+U. Built eagerly at attach
// time (see `build_default_rotors`); per-element queries route to the same
// cached list via `NucleusA11yElement.accessibilityCustomRotors`.
static NSArray<NSAccessibilityCustomRotor *> *tao_view_accessibility_custom_rotors(
    id self, SEL _cmd
) {
    (void)_cmd;
    NucleusA11yProjection *proj = projection_for_view((NSView *)self);
    return proj.cachedRotors ?: @[];
}

static id tao_view_accessibility_hit_test(id self, SEL _cmd, NSPoint pointInScreen) {
    (void)_cmd;
    note_a11y_query();
    NSView *view = (NSView *)self;
    NSWindow *window = view.window;
    if (!window) return self;
    NSPoint pointInWindow = [window convertPointFromScreen:pointInScreen];
    NSPoint pointInView = [view convertPoint:pointInWindow fromView:nil];
    // `frameInView` is stored in Compose's top-left convention. If the view
    // is non-flipped (Tao default), `convertPoint` produces a bottom-left
    // point — flip Y here so the comparison against frameInView is in the
    // same orientation. Without this flip, hovering near the visual top of
    // the content selects an element whose frame is near the visual bottom.
    if (!view.isFlipped) {
        pointInView.y = view.bounds.size.height - pointInView.y;
    }
    NucleusA11yProjection *proj = projection_for_view(view);
    NucleusA11yElement *hit = [proj hitTestPointInView:pointInView];
    return hit ?: self;
}

static void nucleus_tao_swizzle_taoview_a11y_once(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Class c = objc_getClass("TaoView");
        if (!c) return;
        class_replaceMethod(c, @selector(isAccessibilityElement),
                            (IMP)tao_view_is_accessibility_element, "B@:");
        class_replaceMethod(c, @selector(accessibilityRole),
                            (IMP)tao_view_accessibility_role, "@@:");
        class_replaceMethod(c, @selector(accessibilityChildren),
                            (IMP)tao_view_accessibility_children, "@@:");
        class_replaceMethod(c, @selector(accessibilityFocusedUIElement),
                            (IMP)tao_view_accessibility_focused_ui_element, "@@:");
        class_replaceMethod(c, @selector(accessibilityHitTest:),
                            (IMP)tao_view_accessibility_hit_test,
                            "@@:{CGPoint=dd}");
        class_replaceMethod(c, @selector(accessibilityCustomRotors),
                            (IMP)tao_view_accessibility_custom_rotors, "@@:");
    });
}

// ── NSWindow focus forwarder (AccessKit pattern) ───────────────────────────
//
// Tao places the firstResponder on the NSWindow itself (rather than the
// content view) when no NSTextView overlay is engaged. AppKit's
// `accessibilityFocusedUIElement` defaults on NSWindow return the window
// itself, which means VoiceOver lands on the window chrome instead of our
// content. Forwarding the call to the contentView bridges that gap.

static id nucleus_tao_window_focused_ui_element(id self, SEL _cmd) {
    (void)_cmd;
    NSWindow *window = (NSWindow *)self;
    NSView *content = window.contentView;
    // Walk down to find a TaoView (the contentView may be a wrapper).
    NSView *taoView = nil;
    if (content && [NSStringFromClass([content class]) isEqualToString:@"TaoView"]) {
        taoView = content;
    } else if (content) {
        for (NSView *sub in content.subviews) {
            if ([NSStringFromClass([sub class]) isEqualToString:@"TaoView"]) {
                taoView = sub;
                break;
            }
        }
    }
    if (!taoView) return self;
    return tao_view_accessibility_focused_ui_element(taoView, _cmd);
}

static void nucleus_tao_install_window_focus_forwarder(NSWindow *window) {
    if (!window) return;
    Class wc = object_getClass(window);
    if (!wc) return;
    // Install once per concrete window class. NSWindow's default
    // implementation returns self; replacing on the (possibly subclassed)
    // class makes our content view the focused element when AppKit asks.
    static NSMutableSet<NSString *> *patched;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ patched = [NSMutableSet new]; });
    NSString *name = NSStringFromClass(wc);
    if ([patched containsObject:name]) return;
    [patched addObject:name];
    class_replaceMethod(wc,
                        @selector(accessibilityFocusedUIElement),
                        (IMP)nucleus_tao_window_focused_ui_element,
                        "@@:");
}

// ────────────────────────────────────────────────────────────────────────────
// C entry points (called from Rust / JNI).
// ────────────────────────────────────────────────────────────────────────────

// Posts NSAccessibilityLayoutChangedNotification so VoiceOver flushes its
// cached frames. Required when the window moves between displays whose
// backing scale factors differ — without this, VO reads stale screen
// coordinates and points users at the wrong location. Mirrors Chromium's
// `BrowserAccessibilityManagerMac::OnWindowDidChangeBackingProperties`.
static void nucleus_tao_install_screen_change_observers(NucleusA11yProjection *proj,
                                                        NSView *view) {
    NSWindow *window = view.window;
    if (!window) return;
    if (!proj.observers) proj.observers = [NSMutableArray new];

    void (^handler)(NSNotification *) = ^(NSNotification *_unused) {
        NSView *liveView = proj.taoView;
        if (liveView && liveView.window) {
            NSAccessibilityPostNotification(liveView,
                                            NSAccessibilityLayoutChangedNotification);
        }
    };
    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    [proj.observers addObject:
        [nc addObserverForName:NSWindowDidChangeBackingPropertiesNotification
                        object:window
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:handler]];
    [proj.observers addObject:
        [nc addObserverForName:NSWindowDidChangeScreenNotification
                        object:window
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:handler]];
}

// Pre-builds the standard rotor set so that NucleusA11yElement queries from
// VoiceOver hit a populated cache without round-tripping through the TaoView
// swizzle first.
static void build_default_rotors(NucleusA11yProjection *proj) {
    if (proj.cachedRotors != nil) return;
    NucleusRotorDelegate *headingsDelegate = [NucleusRotorDelegate new];
    headingsDelegate.projection = proj;
    headingsDelegate.predicate = ^BOOL(NucleusA11yElement *el) {
        return (el.flags & NucleusA11yFlagHeading) != 0;
    };
    NSAccessibilityCustomRotor *headings = [[NSAccessibilityCustomRotor alloc]
        initWithRotorType:NSAccessibilityCustomRotorTypeHeading
            itemSearchDelegate:headingsDelegate];

    NucleusRotorDelegate *formsDelegate = [NucleusRotorDelegate new];
    formsDelegate.projection = proj;
    formsDelegate.predicate = ^BOOL(NucleusA11yElement *el) {
        switch (el.role) {
            case NucleusA11yRoleButton:
            case NucleusA11yRoleCheckbox:
            case NucleusA11yRoleSwitch:
            case NucleusA11yRoleRadioButton:
            case NucleusA11yRoleTextField:
            case NucleusA11yRoleTextArea:
            case NucleusA11yRoleSlider:
                return (el.flags & NucleusA11yFlagIsElement) != 0;
            default:
                return NO;
        }
    };
    NSAccessibilityCustomRotor *forms = [[NSAccessibilityCustomRotor alloc]
        initWithRotorType:NSAccessibilityCustomRotorTypeAny
            itemSearchDelegate:formsDelegate];
    forms.label = @"Form Controls";

    proj.rotorDelegates = @[headingsDelegate, formsDelegate];
    proj.cachedRotors = @[headings, forms];
}

void nucleus_tao_a11y_attach(int64_t ns_view_handle) {
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_handle;
    if (!view) return;
    nucleus_tao_swizzle_taoview_a11y_once();
    NucleusA11yProjection *proj = ensure_projection_for_view(view);
    build_default_rotors(proj);
    if (view.window) {
        nucleus_tao_install_window_focus_forwarder(view.window);
        nucleus_tao_install_screen_change_observers(proj, view);
    }
}

void nucleus_tao_a11y_detach(int64_t ns_view_handle) {
    // Do NOT bridge the pointer — Tao may have already released the NSView.
    // The integer key is enough to evict the projection.
    NucleusA11yProjection *proj = gProjections()[@(ns_view_handle)];
    if (proj) {
        NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
        for (id obs in proj.observers ?: @[]) {
            [nc removeObserver:obs];
        }
        [proj.observers removeAllObjects];
        // Sever weak references so any straggling AppKit query returns
        // empty defaults rather than dereferencing a stale view.
        for (NucleusA11yElement *el in proj.byId.allValues) {
            el.taoView = nil;
            el.projection = nil;
        }
        proj.taoView = nil;
        [proj.byId removeAllObjects];
        [proj.roots removeAllObjects];
    }
    [gProjections() removeObjectForKey:@(ns_view_handle)];
}

int nucleus_tao_a11y_apply_snapshot(int64_t ns_view_handle,
                                    const uint8_t *bytes,
                                    size_t len) {
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_handle;
    if (!view) return 0;
    NucleusA11yProjection *proj = ensure_projection_for_view(view);
    return apply_snapshot_bytes(proj, bytes, len) ? 1 : 0;
}

// ── Accessibility-active detection ────────────────────────────────────────
// `NSAccessibilityShouldUseUniqueId()` returns YES whenever at least one
// accessibility client (VoiceOver, Switch Control, AppleScript / System
// Events, Accessibility Inspector, etc.) has queried our process. It's the
// closest macOS equivalent to iOS's `UIAccessibilityIsVoiceOverRunning`
// for the broader question "should we be paying for the a11y projection?".
//
// The VoiceOver-specific user default is also exposed below for callers
// that explicitly want to know whether VO itself is the listener.

int nucleus_tao_a11y_is_voiceover_running(void) {
    @autoreleasepool {
        CFPropertyListRef v = CFPreferencesCopyValue(
            CFSTR("voiceOverOnOffKey"),
            CFSTR("com.apple.universalaccess"),
            kCFPreferencesCurrentUser,
            kCFPreferencesAnyHost);
        if (v == NULL) return 0;
        BOOL on = NO;
        if (CFGetTypeID(v) == CFBooleanGetTypeID()) {
            on = CFBooleanGetValue((CFBooleanRef)v);
        } else if (CFGetTypeID(v) == CFNumberGetTypeID()) {
            int n = 0; CFNumberGetValue((CFNumberRef)v, kCFNumberIntType, &n);
            on = n != 0;
        }
        CFRelease(v);
        return on ? 1 : 0;
    }
}

// Tracks the most recent time an accessibility client touched our tree, plus
// a "force resync" flag the JVM observer reads at each tick. The flag is set
// here (in C) the moment any AX entry point fires while we've been skipping
// pushes — that flips `pendingForcedPush` on the JVM side at the next sync,
// guaranteeing the tree gets refreshed once after wake-up.
static _Atomic int64_t g_last_a11y_query_ns = 0;
// Last-pushed timestamp written by the JVM side via
// `nucleus_tao_a11y_note_pushed`. Lets `note_a11y_query` decide whether the
// query came in during an idle window (push needed) or not.
static _Atomic int64_t g_last_a11y_push_ns = 0;
// One-shot flag: a query has occurred since the last push, so the JVM should
// force the next snapshot push regardless of its own gating heuristic.
static _Atomic int g_a11y_force_resync = 1;

static int64_t monotonic_ns(void) {
    return (int64_t)(mach_absolute_time());
}

static void note_a11y_query(void) {
    int64_t now = monotonic_ns();
    atomic_store(&g_last_a11y_query_ns, now);
    int64_t lastPush = atomic_load(&g_last_a11y_push_ns);
    // If the last push is older than 1 raw-counter second, we were idle —
    // request a fresh resync. Threshold is the same as JVM-side staleness.
    if (lastPush == 0 || now - lastPush > 1000LL * 1000LL * 1000LL) {
        atomic_store(&g_a11y_force_resync, 1);
    }
}

int nucleus_tao_a11y_is_active(void) {
    int64_t last = atomic_load(&g_last_a11y_query_ns);
    if (last == 0) return 0;
    int64_t now = monotonic_ns();
    static const int64_t kIdleThresholdRaw = 300LL * 1000LL * 1000LL * 1000LL;
    return (now - last) < kIdleThresholdRaw ? 1 : 0;
}

/// Called by the JVM right after a successful snapshot push. Updates the
/// last-push timestamp and clears the resync flag.
void nucleus_tao_a11y_note_pushed(void) {
    atomic_store(&g_last_a11y_push_ns, monotonic_ns());
    atomic_store(&g_a11y_force_resync, 0);
}

/// Returns 1 (and clears the flag) if the JVM owes a forced push because an
/// AX query happened while pushes were being skipped. Returns 0 otherwise.
int nucleus_tao_a11y_consume_resync(void) {
    int expected = 1;
    if (atomic_compare_exchange_strong(&g_a11y_force_resync, &expected, 0)) {
        return 1;
    }
    return 0;
}

void nucleus_tao_a11y_post_focus_changed(int64_t ns_view_handle, uint64_t node_id) {
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_handle;
    if (!view) return;
    NucleusA11yProjection *proj = projection_for_view(view);
    if (!proj) return;
    proj.focusedNodeId = node_id;
    NucleusA11yElement *el = proj.byId[@(node_id)];
    if (el) {
        NSAccessibilityPostNotification(el, NSAccessibilityFocusedUIElementChangedNotification);
    }
}
