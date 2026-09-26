// Helper of screen-capture-macos-e2e.sh.
//   helper windowid <pid> <title>   CGWindowID of the on-screen window of <pid> (0: any) named <title>
//   helper park                     cursor to the centre of the main display
//   helper hung <file>              opens a window, writes its CGWindowID to <file>, never pumps again
import AppKit

let args = CommandLine.arguments
switch args.count > 1 ? args[1] : "" {
case "windowid":
    let pid = Int32(args[2])!
    let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
    for w in list where (pid == 0 || (w[kCGWindowOwnerPID as String] as? Int32) == pid) && (w[kCGWindowName as String] as? String) == args[3] {
        print(w[kCGWindowNumber as String] as! Int); exit(0)
    }
    exit(1)
case "park":
    let b = CGDisplayBounds(CGMainDisplayID())
    // A real motion event: a warp alone leaves a cursor hidden by setHiddenUntilMouseMoves hidden.
    CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: CGPoint(x: b.midX, y: b.midY), mouseButton: .left)?
        .post(tap: .cghidEventTap)
case "hung":
    let app = NSApplication.shared
    app.setActivationPolicy(.regular)
    let w = NSWindow(contentRect: NSRect(x: 40, y: 40, width: 300, height: 200), styleMask: [.titled], backing: .buffered, defer: false)
    w.title = "ScreenCaptureE2eHung"
    w.backgroundColor = NSColor(srgbRed: 20 / 255, green: 160 / 255, blue: 60 / 255, alpha: 1)
    w.orderFrontRegardless()
    let end = Date().addingTimeInterval(0.5)
    while Date() < end { RunLoop.current.run(mode: .default, before: end) }
    try! "\(w.windowNumber)".write(toFile: args[2], atomically: true, encoding: .ascii)
    while true {} // never pumps again: a hung app
default:
    exit(2)
}
