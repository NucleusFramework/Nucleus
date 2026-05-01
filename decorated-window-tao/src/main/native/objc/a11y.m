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
#include <stdint.h>
#include <string.h>

// ── Wire format (must match TaoAccessibilityController on the Kotlin side) ──
//
//   Header:
//     u32 magic    = 0xA110A11A (little-endian)
//     u16 version  = 1
//     u16 reserved
//     u32 nodeCount
//   Per node (version 2):
//     u64 nodeId
//     u64 parentId        (0 = root → child of TaoView)
//     u16 role            (NucleusA11yRole enum)
//     u16 flags           (bit0=isElement bit1=enabled bit2=focused
//                          bit3=selected bit4=checked bit5=mixed
//                          bit6=heading bit7=password bit8=multiline)
//     u16 actions         (bit0=click bit1=increment bit2=decrement bit3=setText)
//     u16 reserved2
//     f32 frameX,Y,W,H    (window-local logical points, top-left origin)
//     f32 minValue, maxValue, value
//     u32 selectionStart  (UTF-16 code unit, 0 if not a text field)
//     u32 selectionEnd    (UTF-16 code unit, ≥ selectionStart)
//     u16 labelLen        (UTF-8); bytes
//     u16 valueLen        (UTF-8); bytes
//
// All multi-byte fields little-endian.

static const uint32_t kSnapshotMagic = 0xA110A11A;
static const uint16_t kSnapshotVersion = 2;

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
};

typedef NS_OPTIONS(uint16_t, NucleusA11yFlag) {
    NucleusA11yFlagIsElement   = 1 << 0,
    NucleusA11yFlagEnabled     = 1 << 1,
    NucleusA11yFlagFocused     = 1 << 2,
    NucleusA11yFlagSelected    = 1 << 3,
    NucleusA11yFlagChecked     = 1 << 4,
    NucleusA11yFlagMixed       = 1 << 5,
    NucleusA11yFlagHeading     = 1 << 6,
    NucleusA11yFlagPassword    = 1 << 7,
    NucleusA11yFlagMultiline   = 1 << 8,
};

typedef NS_OPTIONS(uint16_t, NucleusA11yAction) {
    NucleusA11yActionClick     = 1 << 0,
    NucleusA11yActionIncrement = 1 << 1,
    NucleusA11yActionDecrement = 1 << 2,
    NucleusA11yActionSetText   = 1 << 3,
};

// Forward-declared callbacks into Kotlin (defined in lib.rs). We weak-link
// them so the native dylib still loads in environments where the JNI side
// isn't wired (tests / standalone load).
__attribute__((weak)) extern void
nucleus_tao_a11y_invoke_action(int64_t ns_view_handle, uint64_t node_id, uint16_t action);

__attribute__((weak)) extern void
nucleus_tao_a11y_set_text(int64_t ns_view_handle, uint64_t node_id,
                          const char *utf8, int32_t len);

// ────────────────────────────────────────────────────────────────────────────
// NucleusA11yElement — backing object for one Compose semantic node.
// ────────────────────────────────────────────────────────────────────────────

@class NucleusA11yProjection;

@interface NucleusA11yElement : NSAccessibilityElement
@property(nonatomic, weak) NSView *taoView;
@property(nonatomic, weak) NucleusA11yProjection *projection;
@property(nonatomic, assign) uint64_t nodeId;
@property(nonatomic, assign) uint64_t parentId;
@property(nonatomic, assign) uint16_t role;
@property(nonatomic, assign) uint16_t flags;
@property(nonatomic, assign) uint16_t actions;
@property(nonatomic, assign) NSRect frameInView;       // top-left origin, points
@property(nonatomic, assign) float minValue;
@property(nonatomic, assign) float maxValue;
@property(nonatomic, assign) float numericValue;
@property(nonatomic, copy) NSString *label;
@property(nonatomic, copy) NSString *valueString;
@property(nonatomic, assign) NSUInteger selectionStart;  // UTF-16 code units
@property(nonatomic, assign) NSUInteger selectionEnd;
@property(nonatomic, strong) NSMutableArray<NucleusA11yElement *> *childElements;
@end

@interface NucleusA11yProjection : NSObject
@property(nonatomic, weak) NSView *taoView;
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, NucleusA11yElement *> *byId;
@property(nonatomic, strong) NSMutableArray<NucleusA11yElement *> *roots;
@property(nonatomic, assign) uint64_t focusedNodeId;
// Notification observers for backing-properties / screen change. Stored so
// we can remove them on detach.
@property(nonatomic, strong) NSMutableArray<id> *observers;
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
    // Flip Y inside the view (TaoView is `isFlipped == YES` to match Compose).
    // If for some reason we're un-flipped, the conversion via convertRect:toView:
    // already handles it; the manual flip below is a no-op safety net for the
    // flipped case (which is what we actually use).
    NSRect bounds = view.bounds;
    NSRect flipped = view.isFlipped
        ? NSMakeRect(rectInView.origin.x,
                     bounds.size.height - rectInView.origin.y - rectInView.size.height,
                     rectInView.size.width,
                     rectInView.size.height)
        : rectInView;
    NSRect inWindow = [view convertRect:flipped toView:nil];
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

- (BOOL)isAccessibilityFocused {
    return (self.flags & NucleusA11yFlagFocused) != 0;
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
    return self.childElements ?: @[];
}

- (NSArray *)accessibilityChildrenInNavigationOrder {
    return self.childElements ?: @[];
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

// 10.9 contract for NSObject-backed elements: we must answer YES so AppKit
// posts NSAccessibilityUIElementDestroyedNotification for us on dealloc.
- (BOOL)accessibilityNotifiesWhenDestroyed { return YES; }

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

- (NSRange)accessibilityRangeForLine:(NSInteger)line {
    if (![self isTextElement] || line != 0) return NSMakeRange(NSNotFound, 0);
    return NSMakeRange(0, self.valueString.length);
}

- (NSInteger)accessibilityLineForIndex:(NSInteger)index {
    if (![self isTextElement]) return -1;
    if (index < 0 || (NSUInteger)index > self.valueString.length) return -1;
    return 0;
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

- (NSString *)accessibilitySelectedText {
    return [self accessibilityStringForRange:[self accessibilitySelectedTextRange]];
}

- (NSInteger)accessibilityInsertionPointLineNumber {
    return [self isTextElement] ? 0 : -1;
}

- (NSRange)accessibilityVisibleCharacterRange {
    if (![self isTextElement]) return NSMakeRange(NSNotFound, 0);
    return NSMakeRange(0, self.valueString.length);
}

// VoiceOver edits text via `accessibilitySetValue:`. We forward the new
// string to the JVM side so it can invoke `SemanticsActions.SetText`.
- (BOOL)accessibilityPerformAction:(NSAccessibilityActionName)action {
    return NO; // unused — actions covered by perform* selectors
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
    uint16_t version = 0, reserved = 0;
    READ_OR_FAIL(&version, 2);
    READ_OR_FAIL(&reserved, 2);
    if (version != kSnapshotVersion) return NO;
    uint32_t nodeCount = 0;
    READ_OR_FAIL(&nodeCount, 4);

    NSMutableDictionary<NSNumber *, NucleusA11yElement *> *previous = proj.byId;
    NSMutableDictionary<NSNumber *, NucleusA11yElement *> *next =
        [NSMutableDictionary dictionaryWithCapacity:nodeCount];
    NSMutableArray<NucleusA11yElement *> *roots = [NSMutableArray new];

    // Queued notifications — flushed after the whole snapshot is consistent.
    NSMutableArray<NucleusA11yElement *> *valueChanged = [NSMutableArray new];
    NSMutableArray<NucleusA11yElement *> *titleChanged = [NSMutableArray new];
    NSMutableArray<NucleusA11yElement *> *createdNodes = [NSMutableArray new];
    BOOL anyFrameChanged = NO;

    // First pass: parse, reuse-or-create, diff against previous values.
    for (uint32_t i = 0; i < nodeCount; i++) {
        uint64_t nodeId = 0, parentId = 0;
        uint16_t role = 0, flags = 0, actions = 0, reserved2 = 0;
        float frame[4] = {0};
        float range[3] = {0};
        uint16_t labelLen = 0, valueLen = 0;
        READ_OR_FAIL(&nodeId, 8);
        READ_OR_FAIL(&parentId, 8);
        READ_OR_FAIL(&role, 2);
        READ_OR_FAIL(&flags, 2);
        READ_OR_FAIL(&actions, 2);
        READ_OR_FAIL(&reserved2, 2);
        READ_OR_FAIL(frame, sizeof(frame));
        READ_OR_FAIL(range, sizeof(range));
        uint32_t selStart = 0, selEnd = 0;
        READ_OR_FAIL(&selStart, 4);
        READ_OR_FAIL(&selEnd, 4);
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
        }
        el.taoView = proj.taoView;
        el.projection = proj;
        el.parentId = parentId;
        el.role = role;
        el.flags = flags;
        el.actions = actions;
        el.frameInView = NSMakeRect(frame[0], frame[1], frame[2], frame[3]);
        el.minValue = range[0];
        el.maxValue = range[1];
        el.numericValue = range[2];
        el.label = label;
        el.valueString = valueStr;
        el.selectionStart = selStart;
        el.selectionEnd = selEnd;
        el.childElements = [NSMutableArray new];
        next[key] = el;
        if (wasNew) [createdNodes addObject:el];
        if (flags & NucleusA11yFlagFocused) {
            proj.focusedNodeId = nodeId;
        }
    }

    // Second pass: link parent → children using the buffer's traversal order
    // (Kotlin emits parents before children via DFS, which lets us link in
    // one re-scan). Re-parsing is cheap; the alternative would be a parallel
    // ordering array allocated in pass 1.
    {
        size_t off2 = 12; // skip header
        for (uint32_t i = 0; i < nodeCount; i++) {
            uint64_t nodeId = 0, parentId = 0;
            memcpy(&nodeId, bytes + off2, 8); off2 += 8;
            memcpy(&parentId, bytes + off2, 8); off2 += 8;
            off2 += 36; // role + flags + actions + reserved2 + frame + range
            off2 += 8;  // selectionStart + selectionEnd
            uint16_t labelLen = 0;
            memcpy(&labelLen, bytes + off2, 2); off2 += 2;
            off2 += labelLen;
            uint16_t valueLen = 0;
            memcpy(&valueLen, bytes + off2, 2); off2 += 2;
            off2 += valueLen;

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
    return YES;
}

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
    NucleusA11yProjection *proj = projection_for_view((NSView *)self);
    if (!proj) return @[];
    return NSAccessibilityUnignoredChildren([proj rootChildrenForView]);
}

static id tao_view_accessibility_focused_ui_element(id self, SEL _cmd) {
    (void)_cmd;
    NucleusA11yProjection *proj = projection_for_view((NSView *)self);
    NucleusA11yElement *focused = [proj focusedElement];
    return focused ?: self;
}

static id tao_view_accessibility_hit_test(id self, SEL _cmd, NSPoint pointInScreen) {
    (void)_cmd;
    NSView *view = (NSView *)self;
    NSWindow *window = view.window;
    if (!window) return self;
    NSPoint pointInWindow = [window convertPointFromScreen:pointInScreen];
    NSPoint pointInView = [view convertPoint:pointInWindow fromView:nil];
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

void nucleus_tao_a11y_attach(int64_t ns_view_handle) {
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_handle;
    if (!view) return;
    nucleus_tao_swizzle_taoview_a11y_once();
    NucleusA11yProjection *proj = ensure_projection_for_view(view);
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
