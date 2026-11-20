// Helper for scripts/macos-resize-lag-e2e.sh (#953): records a window frame by frame with ScreenCaptureKit
// while it is resized, and tells for each frame whether the content on screen was laid out for the window
// size on screen. Reads the colours examples/resize-lag-demo paints: a #303030 rail on the left, a red
// stripe on the right edge, a green stripe on the bottom edge.
//
//   helper find <title>                                  "pid x y w h" (points, top-left origin)
//   helper record <title> <seconds> <out.csv>            one line per captured frame
//   helper drag <title> <dx> <dy> <steps> <ms> <cycles>  drags the bottom-right corner out and back
//   helper dblclick <title>                              double-clicks the title bar (zoom)
//   helper summary <csv>...                              per recording: window sizes seen in motion, mismatched ones
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

struct Found { let pid: pid_t; let rect: CGRect }

func findWindow(_ title: String) -> Found? {
    let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
    for w in list where (w[kCGWindowName as String] as? String) == title {
        guard let b = w[kCGWindowBounds as String] as? [String: CGFloat],
              let pid = w[kCGWindowOwnerPID as String] as? pid_t else { continue }
        return Found(pid: pid, rect: CGRect(x: b["X"]!, y: b["Y"]!, width: b["Width"]!, height: b["Height"]!))
    }
    return nil
}

func post(_ type: CGEventType, _ p: CGPoint, clicks: Int64 = 1) {
    let e = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: p, mouseButton: .left)!
    e.setIntegerValueField(.mouseEventClickState, value: clicks)
    e.post(tap: .cghidEventTap)
}

/// Synthetic mouse events only reach the frontmost app.
func activate(_ pid: pid_t) {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    p.arguments = ["-e", "tell application \"System Events\" to set frontmost of (first process whose unix id is \(pid)) to true"]
    try? p.run()
    p.waitUntilExit()
    usleep(300_000)
}

typealias RGB = (r: Int, g: Int, b: Int)

final class Recorder: NSObject, SCStreamOutput {
    var lines = ["t_ms,winL,winT,winW,winH,rightGap,bottomGap,railOffset"]
    private var t0: CMTime?

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen,
              let atts = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = atts.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sb)
        if t0 == nil { t0 = pts }
        let t = CMTimeGetSeconds(CMTimeSubtract(pts, t0!)) * 1000
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb), bpr = CVPixelBufferGetBytesPerRow(pb)
        let base = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt8.self)
        func px(_ x: Int, _ y: Int) -> RGB {
            let o = y * bpr + x * 4
            return (Int(base[o + 2]), Int(base[o + 1]), Int(base[o]))
        }
        // Only the window is captured, on black: its bounds are the lit pixels.
        func lit(_ c: RGB) -> Bool { c.r + c.g + c.b > 6 }
        func red(_ c: RGB) -> Bool { c.r > 200 && c.g < 70 && c.b < 70 }
        func green(_ c: RGB) -> Bool { c.g > 200 && c.r < 70 && c.b < 70 }
        func rail(_ c: RGB) -> Bool { abs(c.r - 0x30) < 8 && abs(c.g - 0x30) < 8 && abs(c.b - 0x30) < 8 }
        var xs = (0..<w).filter { lit(px($0, h / 2)) }
        guard var winL = xs.first, var winR = xs.last else { return }
        let col = min(winL + 400, (winL + winR) / 2)
        let ys = (0..<h).filter { lit(px(col, $0)) }
        guard let winT = ys.first, let winB = ys.last else { return }
        let row = (winT + winB) / 2 + 20
        xs = (0..<w).filter { lit(px($0, row)) }
        winL = xs.first ?? winL
        winR = xs.last ?? winR
        // 0 when the stripe touches its edge; > 0 a band of window background (content smaller than the
        // window); -1 no stripe at all (content larger than the window, its edge cut off).
        let rightGap = (winL...winR).last { red(px($0, row)) }.map { winR - $0 } ?? -1
        let bottomGap = (winT...winB).last { green(px(col, $0)) }.map { winB - $0 } ?? -1
        let railOffset = ((winL...winR).first { rail(px($0, row)) } ?? winL - 1) - winL
        lines.append(String(format: "%.1f,%d,%d,%d,%d,%d,%d,%d", t, winL, winT, winR - winL + 1, winB - winT + 1,
                            rightGap, bottomGap, railOffset))
    }
}

func record(title: String, seconds: Double, out: String) async throws {
    let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
    guard let win = content.windows.first(where: { $0.title == title }),
          let display = content.displays.first(where: { $0.frame.intersects(win.frame) }) else {
        fputs("window \(title) not found\n", stderr)
        exit(1)
    }
    let filter = SCContentFilter(display: display, including: [win])
    let cfg = SCStreamConfiguration()
    cfg.width = Int(display.frame.width * CGFloat(filter.pointPixelScale))
    cfg.height = Int(display.frame.height * CGFloat(filter.pointPixelScale))
    cfg.minimumFrameInterval = CMTime(value: 1, timescale: 240)
    cfg.pixelFormat = kCVPixelFormatType_32BGRA
    cfg.showsCursor = false
    cfg.queueDepth = 8
    cfg.backgroundColor = .black
    if #available(macOS 14.0, *) { cfg.ignoreShadowsDisplay = true }
    let recorder = Recorder()
    let stream = SCStream(filter: filter, configuration: cfg, delegate: nil)
    try stream.addStreamOutput(recorder, type: .screen, sampleHandlerQueue: DispatchQueue(label: "record"))
    try await stream.startCapture()
    try await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
    try await stream.stopCapture()
    try recorder.lines.joined(separator: "\n").write(toFile: out, atomically: true, encoding: .utf8)
}

/// Frames of a window size the recording had not shown yet, once the window started moving: one per
/// resize step that reached the screen. A step is mismatched when its first frame does not fit it.
func summary(_ path: String) -> (steps: Int, mismatched: Int) {
    let rows = (try? String(contentsOfFile: path, encoding: .utf8))?.split(separator: "\n").dropFirst()
        .map { $0.split(separator: ",").map(String.init) } ?? []
    guard let first = rows.first else { return (0, 0) }
    var seen = Set<String>(), steps = 0, mismatched = 0, previous = first[1...4].joined(separator: "x")
    for r in rows.dropFirst() {
        let geometry = r[1...4].joined(separator: "x")
        defer { previous = geometry }
        guard geometry != previous, seen.insert(geometry).inserted else { continue }
        steps += 1
        let fits = (0...1).contains(Int(r[5])!) && (0...1).contains(Int(r[6])!) && r[7] == "0"
        if !fits { mismatched += 1 }
    }
    return (steps, mismatched)
}

let args = CommandLine.arguments
switch args.count > 1 ? args[1] : "" {
case "find":
    guard let w = findWindow(args[2]) else { exit(1) }
    print(w.pid, Int(w.rect.minX), Int(w.rect.minY), Int(w.rect.width), Int(w.rect.height))
case "drag":
    guard let w = findWindow(args[2]) else { exit(1) }
    let dx = Double(args[3])!, dy = Double(args[4])!, steps = Int(args[5])!, ms = Double(args[6])!, cycles = Int(args[7])!
    activate(w.pid)
    let start = CGPoint(x: w.rect.maxX - 2, y: w.rect.maxY - 2)
    post(.mouseMoved, start)
    usleep(100_000)
    post(.leftMouseDown, start)
    usleep(50_000)
    for _ in 0..<cycles {
        for outward in [true, false] {
            for i in 1...steps {
                let f = outward ? Double(i) / Double(steps) : 1 - Double(i) / Double(steps)
                post(.leftMouseDragged, CGPoint(x: start.x + dx * f, y: start.y + dy * f))
                usleep(UInt32(ms * 1000))
            }
        }
    }
    usleep(50_000)
    post(.leftMouseUp, start)
case "dblclick":
    guard let w = findWindow(args[2]) else { exit(1) }
    activate(w.pid)
    let p = CGPoint(x: w.rect.midX, y: w.rect.minY + 14)
    post(.mouseMoved, p)
    usleep(50_000)
    post(.leftMouseDown, p, clicks: 1)
    post(.leftMouseUp, p, clicks: 1)
    usleep(60_000)
    post(.leftMouseDown, p, clicks: 2)
    post(.leftMouseUp, p, clicks: 2)
case "record":
    let done = DispatchSemaphore(value: 0)
    Task {
        do { try await record(title: args[2], seconds: Double(args[3])!, out: args[4]) } catch {
            fputs("record failed: \(error)\n", stderr)
            exit(2)
        }
        done.signal()
    }
    done.wait()
case "summary":
    var failures = 0
    for path in args.dropFirst(2) {
        let s = summary(path)
        print("\((path as NSString).lastPathComponent): \(s.steps) resize steps on screen, \(s.mismatched) mismatched")
        if s.steps == 0 || s.mismatched > 0 { failures += 1 }
    }
    exit(Int32(min(failures, 100)))
default:
    fputs("usage: helper find|record|drag|dblclick|summary …\n", stderr)
    exit(64)
}
