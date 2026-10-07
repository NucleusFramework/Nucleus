// CI probe: verifies the Tao macOS NSAccessibility projection end-to-end.
//
// Locates the Lab a11y-surface fixture process (opens on its A11y tab), dumps
// its AX tree through the ApplicationServices client API — the same path
// VoiceOver uses — and asserts the expected elements are exposed. The first
// AX attribute read is also what flips the app's a11y pipeline active
// (query-timestamp heuristic), so this exercises activation too.
//
// AX clients need the Accessibility permission (TCC). GitHub runners cannot
// grant it, so when AXIsProcessTrusted() is false the probe prints SKIPPED
// and exits 0 — the hard e2e gates are the Windows/Linux jobs; run this
// locally (once the terminal is trusted) for the macOS leg.
//
// Usage: swift scripts/ci/verify-ax.swift <pid>
// Derived from scripts/axdump.swift / scripts/axtool.swift.
import ApplicationServices
import Cocoa

func attr(_ el: AXUIElement, _ name: String) -> CFTypeRef? {
    var value: CFTypeRef?
    let err = AXUIElementCopyAttributeValue(el, name as CFString, &value)
    return err == .success ? value : nil
}

func collectDescriptions(_ el: AXUIElement, depth: Int, into found: inout Set<String>) {
    if depth > 12 { return }
    if let desc = attr(el, kAXDescriptionAttribute) as? String, !desc.isEmpty {
        found.insert(desc)
    }
    if let title = attr(el, kAXTitleAttribute) as? String, !title.isEmpty {
        found.insert(title)
    }
    if let children = attr(el, kAXChildrenAttribute) as? [AXUIElement] {
        for child in children {
            collectDescriptions(child, depth: depth + 1, into: &found)
        }
    }
}

guard CommandLine.arguments.count >= 2, let pid = Int32(CommandLine.arguments[1]) else {
    FileHandle.standardError.write("usage: verify-ax.swift <pid>\n".data(using: .utf8)!)
    exit(2)
}

print("── AX a11y verification (pid \(pid)) ──")
guard AXIsProcessTrusted() else {
    print("SKIPPED: this process is not an AX-trusted client (TCC Accessibility permission missing).")
    print("Run locally from a trusted terminal for the macOS leg; Windows/Linux CI jobs are the hard gates.")
    exit(0)
}

let app = AXUIElementCreateApplication(pid)
let expected = ["Increment", "Tri-state checkbox", "Notifications switch", "click counter 0"]
var failures = 0
let deadline = Date().addingTimeInterval(120)
var found = Set<String>()
repeat {
    found.removeAll()
    if let windows = attr(app, kAXWindowsAttribute) as? [AXUIElement] {
        for window in windows {
            collectDescriptions(window, depth: 0, into: &found)
        }
    }
    if expected.allSatisfy({ found.contains($0) }) { break }
    Thread.sleep(forTimeInterval: 2)
} while Date() < deadline

for name in expected {
    let ok = found.contains(name)
    print("  [\(ok ? "PASS" : "FAIL")] element '\(name)' exposed")
    if !ok { failures += 1 }
}

// ── Advanced semantics ──────────────────────────────────────────────────
func findElement(desc: String, prefix: Bool = false) -> AXUIElement? {
    var queue: [AXUIElement] = []
    if let windows = attr(app, kAXWindowsAttribute) as? [AXUIElement] { queue = windows }
    var depth = 0
    while !queue.isEmpty && depth < 4000 {
        depth += 1
        let el = queue.removeFirst()
        let d = (attr(el, kAXDescriptionAttribute) as? String) ?? ""
        let title = (attr(el, kAXTitleAttribute) as? String) ?? ""
        if prefix ? (d.hasPrefix(desc) || title.hasPrefix(desc)) : (d == desc || title == desc) { return el }
        if let children = attr(el, kAXChildrenAttribute) as? [AXUIElement] { queue.append(contentsOf: children) }
    }
    return nil
}

// Disabled button: role must survive AND AXEnabled must be false.
var disabledOk = false
if let cannot = findElement(desc: "Cannot press"),
   let enabled = attr(cannot, kAXEnabledAttribute) as? Bool,
   let role = attr(cannot, kAXRoleAttribute) as? String {
    disabledOk = !enabled && role == (kAXButtonRole as String)
    if !disabledOk { print("  'Cannot press': role=\(role) enabled=\(enabled)") }
}
print("  [\(disabledOk ? "PASS" : "FAIL")] 'Cannot press' exposed as a disabled button")
if !disabledOk { failures += 1 }

// Custom actions: labels must ride the AX custom-actions channel.
var customOk = false
if let notif = findElement(desc: "Notification (clicks:", prefix: true) {
    var names: CFArray?
    if AXUIElementCopyActionNames(notif, &names) == .success,
       let actions = names as? [String] {
        customOk = actions.contains("Mark as read") || actions.contains(where: { $0.contains("Mark") })
        if !customOk { print("  actions seen: \(actions)") }
    }
}
print("  [\(customOk ? "PASS" : "FAIL")] custom action 'Mark as read' exposed")
if !customOk { failures += 1 }

// Live region: pressing 'Update status' must surface the new status text.
var liveOk = false
if let upd = findElement(desc: "Update status") {
    AXUIElementPerformAction(upd, kAXPressAction as CFString)
    let liveDeadline = Date().addingTimeInterval(15)
    while Date() < liveDeadline && !liveOk {
        liveOk = findElement(desc: "Status updated at", prefix: true) != nil
        if !liveOk { Thread.sleep(forTimeInterval: 1) }
    }
}
print("  [\(liveOk ? "PASS" : "FAIL")] live region text updated after 'Update status'")
if !liveOk { failures += 1 }

print("── \(failures) failure(s) ── (saw \(found.count) named elements)")
exit(failures > 0 ? 1 : 0)
