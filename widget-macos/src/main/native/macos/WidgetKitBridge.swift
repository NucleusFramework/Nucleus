// WidgetKitBridge.swift
//
// WidgetKit's app-side API (WidgetCenter, ControlCenter) is Swift-only: these @_cdecl functions
// give the JNI layer (nucleus_widget_macos.m) a C surface. Asynchronous calls block the caller on
// a semaphore, so the Kotlin side runs them off its own threads. Results that may fail come back
// as a malloc'ed string, "OK\n" + payload or "ERR\n" + message, freed by the caller.

import Foundation
import WidgetKit

private func result(_ ok: Bool, _ payload: String) -> UnsafeMutablePointer<CChar>? {
    strdup((ok ? "OK\n" : "ERR\n") + payload)
}

private func waitFor<T>(_ timeoutMillis: Int32, _ body: @escaping (@escaping (Result<T, Error>) -> Void) -> Void) -> Result<T, Error>? {
    let semaphore = DispatchSemaphore(value: 0)
    var outcome: Result<T, Error>?
    let lock = NSLock()
    body { value in
        lock.lock()
        outcome = value
        lock.unlock()
        semaphore.signal()
    }
    guard semaphore.wait(timeout: .now() + .milliseconds(Int(timeoutMillis))) == .success else { return nil }
    lock.lock()
    defer { lock.unlock() }
    return outcome
}

private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

@_cdecl("nucleus_wk_reload_timelines")
public func reloadTimelines(_ kind: UnsafePointer<CChar>) {
    WidgetCenter.shared.reloadTimelines(ofKind: String(cString: kind))
}

@_cdecl("nucleus_wk_reload_all_timelines")
public func reloadAllTimelines() {
    WidgetCenter.shared.reloadAllTimelines()
}

/// Returns false below macOS 13, where WidgetKit has no configuration recommendations.
@_cdecl("nucleus_wk_invalidate_configuration_recommendations")
public func invalidateConfigurationRecommendations() -> Bool {
    guard #available(macOS 13.0, *) else { return false }
    WidgetCenter.shared.invalidateConfigurationRecommendations()
    return true
}

/// Returns false below macOS 15.
@_cdecl("nucleus_wk_invalidate_relevance")
public func invalidateRelevance(_ kind: UnsafePointer<CChar>) -> Bool {
    guard #available(macOS 15.0, *) else { return false }
    WidgetCenter.shared.invalidateRelevance(ofKind: String(cString: kind))
    return true
}

/// One "kind\tfamily" line per pinned widget.
@_cdecl("nucleus_wk_current_configurations")
public func currentConfigurations(_ timeoutMillis: Int32) -> UnsafeMutablePointer<CChar>? {
    let outcome: Result<[WidgetInfo], Error>? = waitFor(timeoutMillis) { done in
        WidgetCenter.shared.getCurrentConfigurations { done($0) }
    }
    switch outcome {
    case .success(let infos):
        return result(true, infos.map { "\($0.kind)\t\(String(describing: $0.family))" }.joined(separator: "\n"))
    case .failure(let error):
        return result(false, String(describing: error))
    case nil:
        return result(false, "WidgetCenter did not answer within \(timeoutMillis) ms")
    }
}

/// The widgets' push token as hex, empty when there is none (no WidgetPushHandler, or below macOS 26).
@_cdecl("nucleus_wk_current_push_info")
public func currentPushInfo(_ timeoutMillis: Int32) -> UnsafeMutablePointer<CChar>? {
    guard #available(macOS 26.0, *) else { return result(true, "") }
    let outcome: Result<WidgetPushInfo?, Error>? = waitFor(timeoutMillis) { done in
        Task { done(.success(await WidgetCenter.shared.currentPushInfo)) }
    }
    switch outcome {
    case .success(let info): return result(true, info.map { hex($0.token) } ?? "")
    case .failure(let error): return result(false, String(describing: error))
    case nil: return result(false, "WidgetCenter did not answer within \(timeoutMillis) ms")
    }
}

/// WidgetCenter.UserInfoKey: 0 kind, 1 family, 2 activityID.
@_cdecl("nucleus_wk_user_info_key")
public func userInfoKey(_ which: Int32) -> UnsafeMutablePointer<CChar>? {
    switch which {
    case 0: return strdup(WidgetCenter.UserInfoKey.kind)
    case 1: return strdup(WidgetCenter.UserInfoKey.family)
    default: return strdup(WidgetCenter.UserInfoKey.activityID)
    }
}

@_cdecl("nucleus_wk_controls_available")
public func controlsAvailable() -> Bool {
    if #available(macOS 26.0, *) { return true }
    return false
}

@_cdecl("nucleus_wk_reload_controls")
public func reloadControls(_ kind: UnsafePointer<CChar>) {
    guard #available(macOS 26.0, *) else { return }
    ControlCenter.shared.reloadControls(ofKind: String(cString: kind))
}

@_cdecl("nucleus_wk_reload_all_controls")
public func reloadAllControls() {
    guard #available(macOS 26.0, *) else { return }
    ControlCenter.shared.reloadAllControls()
}

/// One "kind\tpushTokenHex" line per control the user added (empty token when it has none).
@_cdecl("nucleus_wk_current_controls")
public func currentControls(_ timeoutMillis: Int32) -> UnsafeMutablePointer<CChar>? {
    guard #available(macOS 26.0, *) else { return result(true, "") }
    let outcome: Result<[ControlInfo], Error>? = waitFor(timeoutMillis) { done in
        Task {
            do { done(.success(try await ControlCenter.shared.currentControls())) } catch { done(.failure(error)) }
        }
    }
    switch outcome {
    case .success(let controls):
        return result(true, controls.map { "\($0.kind)\t\($0.pushInfo.map { hex($0.token) } ?? "")" }.joined(separator: "\n"))
    case .failure(let error):
        return result(false, String(describing: error))
    case nil:
        return result(false, "ControlCenter did not answer within \(timeoutMillis) ms")
    }
}
