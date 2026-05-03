// axtool — minimal AX exerciser for end-to-end testing.
//   axtool <pid> dump [maxDepth]                  Print tree
//   axtool <pid> find <role> <descSubstring>      Print first match info + actions + value
//   axtool <pid> press <role> <descSubstring>     AXPress
//   axtool <pid> increment <role> <descSubstring> AXIncrement
//   axtool <pid> decrement <role> <descSubstring> AXDecrement
//   axtool <pid> setvalue <role> <descSubstring> <newValue>
//   axtool <pid> nav <role> <descSubstring>       Print NavigableStaticText params
import Cocoa
import ApplicationServices

func attr(_ el: AXUIElement, _ name: String) -> CFTypeRef? {
    var v: CFTypeRef?
    return AXUIElementCopyAttributeValue(el, name as CFString, &v) == .success ? v : nil
}

func paramAttr(_ el: AXUIElement, _ name: String, _ param: AnyObject) -> CFTypeRef? {
    var v: CFTypeRef?
    return AXUIElementCopyParameterizedAttributeValue(el, name as CFString, param, &v) == .success ? v : nil
}

func walk(_ el: AXUIElement, _ visit: (AXUIElement) -> Bool) {
    if !visit(el) { return }
    if let kids = attr(el, kAXChildrenAttribute as String) as? [AXUIElement] {
        for k in kids { walk(k, visit) }
    }
    // Application elements expose top-level windows via AXWindows, not AXChildren.
    if let wins = attr(el, kAXWindowsAttribute as String) as? [AXUIElement] {
        for w in wins { walk(w, visit) }
    }
}

func appRoot(_ pid: Int32) -> AXUIElement { AXUIElementCreateApplication(pid) }

func find(_ root: AXUIElement, role: String, descSubstr: String) -> AXUIElement? {
    var found: AXUIElement?
    walk(root) { el in
        if found != nil { return false }
        let r = (attr(el, kAXRoleAttribute as String) as? String) ?? ""
        if r != role { return true }
        let d = (attr(el, kAXDescriptionAttribute as String) as? String) ?? ""
        let t = (attr(el, kAXTitleAttribute as String) as? String) ?? ""
        if descSubstr.isEmpty || d.contains(descSubstr) || t.contains(descSubstr) {
            found = el
            return false
        }
        return true
    }
    return found
}

func dump(_ el: AXUIElement, _ depth: Int, _ maxDepth: Int) {
    if depth > maxDepth { return }
    let role = (attr(el, kAXRoleAttribute as String) as? String) ?? "?"
    let desc = (attr(el, kAXDescriptionAttribute as String) as? String) ?? ""
    let title = (attr(el, kAXTitleAttribute as String) as? String) ?? ""
    let value = attr(el, kAXValueAttribute as String).map { String(describing: $0) } ?? ""
    let pad = String(repeating: "  ", count: depth)
    print("\(pad)\(role) desc=[\(desc)] title=[\(title)] val=[\(value)]")
    if let kids = attr(el, kAXChildrenAttribute as String) as? [AXUIElement] {
        for k in kids { dump(k, depth + 1, maxDepth) }
    }
}

func info(_ el: AXUIElement) {
    let role = (attr(el, kAXRoleAttribute as String) as? String) ?? "?"
    let sub = (attr(el, kAXSubroleAttribute as String) as? String) ?? ""
    let desc = (attr(el, kAXDescriptionAttribute as String) as? String) ?? ""
    let title = (attr(el, kAXTitleAttribute as String) as? String) ?? ""
    let value = attr(el, kAXValueAttribute as String).map { String(describing: $0) } ?? ""
    print("role=\(role) subrole=[\(sub)] desc=[\(desc)] title=[\(title)] value=[\(value)]")
    var actions: CFArray?
    if AXUIElementCopyActionNames(el, &actions) == .success, let arr = actions as? [String] {
        print("actions=\(arr.joined(separator: ","))")
    }
    if let mn = attr(el, kAXMinValueAttribute as String) {
        print("min=\(mn) max=\(attr(el, kAXMaxValueAttribute as String) ?? "?" as CFTypeRef)")
    }
    // Decode position + size (each is an AXValue carrying a CGPoint / CGSize).
    if let posVal = attr(el, kAXPositionAttribute as String) {
        if CFGetTypeID(posVal) == AXValueGetTypeID() {
            var p = CGPoint.zero
            AXValueGetValue(posVal as! AXValue, .cgPoint, &p)
            print("position=(\(p.x), \(p.y))")
        }
    }
    if let szVal = attr(el, kAXSizeAttribute as String) {
        if CFGetTypeID(szVal) == AXValueGetTypeID() {
            var s = CGSize.zero
            AXValueGetValue(szVal as! AXValue, .cgSize, &s)
            print("size=(\(s.width), \(s.height))")
        }
    }
}

let argv = CommandLine.arguments
guard argv.count >= 3 else {
    print("usage: axtool <pid> <cmd> [...]")
    exit(2)
}
_ = argv.count
let pid = Int32(argv[1])!
let cmd = argv[2]
let app = appRoot(pid)

switch cmd {
case "dump":
    let depth = argv.count >= 4 ? Int(argv[3])! : 10
    if let wins = attr(app, kAXWindowsAttribute as String) as? [AXUIElement] {
        for w in wins { dump(w, 0, depth) }
    }
case "find":
    if let el = find(app, role: argv[3], descSubstr: argv[4]) {
        info(el)
    } else { print("NOT FOUND"); exit(1) }
case "wininfo":
    if let wins = attr(app, kAXWindowsAttribute as String) as? [AXUIElement] {
        for (i, w) in wins.enumerated() {
            print("Window \(i):")
            info(w)
        }
    }
case "roles":
    var seen: [String: Int] = [:]
    walk(app) { el in
        let r = (attr(el, kAXRoleAttribute as String) as? String) ?? "?"
        seen[r, default: 0] += 1
        return true
    }
    for (k, v) in seen.sorted(by: { $0.key < $1.key }) { print("\(k): \(v)") }
case "press", "increment", "decrement":
    let action: String = ["press":"AXPress", "increment":"AXIncrement", "decrement":"AXDecrement"][cmd]!
    if let el = find(app, role: argv[3], descSubstr: argv[4]) {
        let err = AXUIElementPerformAction(el, action as CFString)
        print("perform \(action) err=\(err.rawValue)")
    } else { print("NOT FOUND"); exit(1) }
case "setvalue":
    if let el = find(app, role: argv[3], descSubstr: argv[4]) {
        let str = argv[5] as CFString
        let err = AXUIElementSetAttributeValue(el, kAXValueAttribute as CFString, str)
        print("setvalue err=\(err.rawValue)")
    } else { print("NOT FOUND"); exit(1) }
case "setsel":
    if let el = find(app, role: argv[3], descSubstr: argv[4]) {
        var r = CFRange(location: Int(argv[5])!, length: Int(argv[6])!)
        let v = AXValueCreate(.cfRange, &r)!
        let err = AXUIElementSetAttributeValue(el, kAXSelectedTextRangeAttribute as CFString, v)
        print("setsel err=\(err.rawValue)")
    } else { print("NOT FOUND"); exit(1) }
case "nav":
    if let el = find(app, role: argv[3], descSubstr: argv[4]) {
        // Read NavigableStaticText params
        let count = attr(el, kAXNumberOfCharactersAttribute as String)
        print("numberOfChars=\(count ?? "nil" as CFTypeRef)")
        if let n = (count as? Int) ?? (count as? NSNumber).map({ $0.intValue }) {
            // line for index 0
            let zero = NSNumber(value: 0)
            let line = paramAttr(el, kAXLineForIndexParameterizedAttribute as String, zero)
            print("lineForIndex(0)=\(line ?? "nil" as CFTypeRef)")
            // range for line 0
            let range = paramAttr(el, kAXRangeForLineParameterizedAttribute as String, zero)
            print("rangeForLine(0)=\(range ?? "nil" as CFTypeRef)")
            // string for full range
            var rangeVal = CFRange(location: 0, length: n)
            let axRange = AXValueCreate(.cfRange, &rangeVal)!
            let str = paramAttr(el, kAXStringForRangeParameterizedAttribute as String, axRange)
            print("stringForRange(0..\(n))=\(str ?? "nil" as CFTypeRef)")
        }
        let sel = attr(el, kAXSelectedTextRangeAttribute as String)
        print("selectedTextRange=\(sel ?? "nil" as CFTypeRef)")
    } else { print("NOT FOUND"); exit(1) }
default:
    print("unknown cmd")
    exit(2)
}
