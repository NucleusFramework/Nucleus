import Cocoa
import ApplicationServices

func attr(_ el: AXUIElement, _ name: String) -> CFTypeRef? {
    var v: CFTypeRef?
    let err = AXUIElementCopyAttributeValue(el, name as CFString, &v)
    return err == .success ? v : nil
}

func dump(_ el: AXUIElement, _ depth: Int, _ maxDepth: Int) {
    if depth > maxDepth { return }
    let role = (attr(el, kAXRoleAttribute as String) as? String) ?? "?"
    let desc = (attr(el, kAXDescriptionAttribute as String) as? String) ?? ""
    let title = (attr(el, kAXTitleAttribute as String) as? String) ?? ""
    let value = (attr(el, kAXValueAttribute as String) as? String) ?? ""
    let pad = String(repeating: "  ", count: depth)
    print("\(pad)\(role) desc=[\(desc)] title=[\(title)] val=[\(value)]")

    if let kids = attr(el, kAXChildrenAttribute as String) as? [AXUIElement] {
        for k in kids { dump(k, depth + 1, maxDepth) }
    }
}

let pid = atoi(CommandLine.arguments[1])
let maxDepth = CommandLine.arguments.count >= 3 ? Int(CommandLine.arguments[2])! : 8
let app = AXUIElementCreateApplication(pid_t(pid))

if let windows = attr(app, kAXWindowsAttribute as String) as? [AXUIElement] {
    for (i, w) in windows.enumerated() {
        print("=== Window \(i) ===")
        dump(w, 0, maxDepth)
    }
}
