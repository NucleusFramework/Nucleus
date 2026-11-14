// NucleusWidgetCenter.swift
//
// WidgetKit's WidgetCenter and ControlCenter for widget-ios's Kotlin (WidgetCenter.kt), which finds
// this class through the Objective-C runtime: WidgetKit is Swift-only. Add it to the app target
// (and to the widget extension if its Kotlin reloads widgets too). One string command in, "OK\n"
// + payload or "ERR\n" + message out.

import Foundation
import WidgetKit

@objc(NucleusWidgetCenter)
final class NucleusWidgetCenter: NSObject {
    @objc static func call(_ request: String) -> String {
        let parts = request.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        let command = parts.first ?? ""
        let argument = parts.count > 1 ? parts[1] : ""
        switch command {
        case "available":
            return "OK\n"
        case "reloadTimelines":
            WidgetCenter.shared.reloadTimelines(ofKind: argument)
            return "OK\n"
        case "reloadAllTimelines":
            WidgetCenter.shared.reloadAllTimelines()
            return "OK\n"
        case "currentConfigurations":
            return wait { done in
                WidgetCenter.shared.getCurrentConfigurations { result in
                    switch result {
                    case .success(let infos):
                        done("OK\n" + infos.map { "\($0.kind)\t\(String(describing: $0.family))" }.joined(separator: "\n"))
                    case .failure(let error):
                        done("ERR\n\(error)")
                    }
                }
            }
        case "invalidateConfigurationRecommendations":
            WidgetCenter.shared.invalidateConfigurationRecommendations()
            return "OK\n"
        case "invalidateRelevance":
            guard #available(iOS 18.0, *) else { return "ERR\niOS 18 required" }
            WidgetCenter.shared.invalidateRelevance(ofKind: argument)
            return "OK\n"
        case "controlsAvailable":
            if #available(iOS 18.0, *) { return "OK\n" }
            return "ERR\niOS 18 required"
        case "reloadControls":
            if #available(iOS 18.0, *) { ControlCenter.shared.reloadControls(ofKind: argument) }
            return "OK\n"
        case "reloadAllControls":
            if #available(iOS 18.0, *) { ControlCenter.shared.reloadAllControls() }
            return "OK\n"
        case "currentControls":
            guard #available(iOS 18.0, *) else { return "OK\n" }
            return wait { done in
                Task {
                    do {
                        done("OK\n" + (try await ControlCenter.shared.currentControls()).map(\.kind).joined(separator: "\n"))
                    } catch {
                        done("ERR\n\(error)")
                    }
                }
            }
        default:
            return "ERR\nunknown command \(command)"
        }
    }

    /// Blocks the Kotlin caller (off the main thread) until WidgetKit answers, 10 s at most.
    private static func wait(_ body: (@escaping (String) -> Void) -> Void) -> String {
        let semaphore = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var answer = "ERR\nWidgetKit did not answer"
        body { value in
            lock.lock()
            answer = value
            lock.unlock()
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 10)
        lock.lock()
        defer { lock.unlock() }
        return answer
    }
}
