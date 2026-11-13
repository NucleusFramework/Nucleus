// StoreKit Testing half of the bridge: a `.storekit` configuration served in this process,
// without Xcode, and the store-side events it can play.

import Foundation
import StoreKit

struct DevConfigureArgs: Decodable {
    let configuration: String
    let framework: String
}

/// A session property: `value` is a boolean, a number (`timeRate`) or a string (`storefront`).
struct DevSetArgs: Decodable {
    let key: String
    let bool: Bool?
    let int: Int?
    let string: String?
}

struct DevActArgs: Decodable {
    let action: String
    let productId: String
}

/// StoreKitTest is loaded only here, never linked. `-[SKTestSession bundleID]` returns a value
/// already set; left unset it soft-links XCTest from a path that only exists under Xcode's test
/// runner and aborts. So the session is allocated, given this app's bundle id, then initialized.
enum DevSession {
    static var session: NSObject?

    static func require() throws -> NSObject {
        guard let session else {
            throw WireError(code: "invalidArgument", message: "No StoreKit configuration is active")
        }
        return session
    }

    static func configure(_ args: DevConfigureArgs) throws {
        guard session == nil else { return }
        // Outside an app there is nothing to configure: every store call reports notBundled.
        guard isAppBundle, let bundleId = Bundle.main.bundleIdentifier else { return }
        guard dlopen(args.framework, RTLD_NOW) != nil, NSClassFromString("SKTestSession") != nil else {
            let reason = dlerror().map { String(cString: $0) } ?? "SKTestSession not found"
            throw WireError(code: "platform", message: "Cannot load StoreKitTest from \(args.framework): \(reason)")
        }
        var error: NSError?
        guard let made = nms_test_session_create(bundleId, URL(fileURLWithPath: args.configuration), &error) else {
            throw WireError(
                code: "invalidArgument",
                message: "Cannot read StoreKit configuration \(args.configuration): \(error?.localizedDescription ?? "unknown error")"
            )
        }
        session = made
    }

    /// What `forget` did: `skipped` names why nothing was attempted.
    struct ForgetDto: Encodable {
        let removed: Bool
        let skipped: String?
    }

    /// Drops the configuration a previous session left in storekitagent: it outlives the process,
    /// and every later launch of the bundle id, `.storekit` or not, would keep the Xcode
    /// environment instead of the Sandbox or the App Store. Xcode does the same when it launches an
    /// app without one. storekitagent only lets a debuggable process (get-task-allow) do it.
    static func forget() throws -> ForgetDto {
        guard session == nil else { return ForgetDto(removed: false, skipped: "a session is active") }
        guard isAppBundle, let bundleId = Bundle.main.bundleIdentifier else {
            return ForgetDto(removed: false, skipped: "not an app bundle")
        }
        guard nms_is_debuggable() else { return ForgetDto(removed: false, skipped: "not debuggable") }
        var error: NSError?
        guard nms_test_configuration_remove(bundleId, &error) else {
            throw WireError(
                code: "platform",
                message: "Cannot remove the StoreKit Testing configuration: \(error?.localizedDescription ?? "unknown error")"
            )
        }
        return ForgetDto(removed: true, skipped: nil)
    }

    // MARK: Properties

    /// The session's settings. They outlive the process: StoreKit Testing keeps them per bundle id.
    struct SettingsDto: Encodable {
        let dialogs: Bool
        let askToBuy: Bool
        let billingRetryOnRenewal: Bool
        let billingGracePeriod: Bool
        let interruptedPurchases: Bool
        let timeRate: Int
        let storefront: String
    }

    static func settings() throws -> SettingsDto {
        let session = try require()
        func flag(_ key: String) -> Bool { session.value(forKey: key) as? Bool ?? false }
        return SettingsDto(
            dialogs: !flag("disableDialogs"),
            askToBuy: flag("askToBuyEnabled"),
            billingRetryOnRenewal: flag("billingRetryOnRenewalEnabled"),
            billingGracePeriod: flag("billingGracePeriodEnabled"),
            interruptedPurchases: flag("interruptedPurchasesEnabled"),
            timeRate: session.value(forKey: "timeRate") as? Int ?? 0,
            storefront: session.value(forKey: "storefront") as? String ?? ""
        )
    }

    static func set(_ args: DevSetArgs) throws {
        let session = try require()
        switch args.key {
        case "dialogs": session.setValue(!(try flag(args)), forKey: "disableDialogs")
        case "askToBuy": session.setValue(try flag(args), forKey: "askToBuyEnabled")
        case "billingRetryOnRenewal": session.setValue(try flag(args), forKey: "billingRetryOnRenewalEnabled")
        case "billingGracePeriod": session.setValue(try flag(args), forKey: "billingGracePeriodEnabled")
        case "interruptedPurchases": session.setValue(try flag(args), forKey: "interruptedPurchasesEnabled")
        case "timeRate":
            guard let rate = args.int else { throw badValue(args) }
            session.setValue(rate, forKey: "timeRate")
        case "storefront":
            guard let storefront = args.string, storefront.count == 3 else { throw badValue(args) }
            session.setValue(storefront.uppercased(), forKey: "storefront")
        default:
            throw WireError(code: "invalidArgument", message: "Unknown StoreKit Testing setting '\(args.key)'")
        }
    }

    private static func flag(_ args: DevSetArgs) throws -> Bool {
        guard let value = args.bool else { throw badValue(args) }
        return value
    }

    private static func badValue(_ args: DevSetArgs) -> WireError {
        WireError(code: "invalidArgument", message: "Bad value for StoreKit Testing setting '\(args.key)'")
    }

    // MARK: Actions

    /// Store-side events (refund, renewal, expiry, Ask to Buy, purchases made elsewhere), through
    /// `NucleusStoreKitTest.m`. No decline of Ask to Buy: the session marks the transaction failed,
    /// but StoreKit 2 keeps reporting it as a verified, purchased transaction (JWS identical to an
    /// approved one, and the next purchase returns it without a new charge; Xcode 27.1).
    static func act(_ args: DevActArgs) throws {
        let session = try require()
        var error: NSError?
        guard nms_test_session_act(session, args.action, args.productId, &error) else {
            let ours = error?.domain == "NucleusMacStore"
            throw WireError(
                code: ours ? "invalidArgument" : "platform",
                message: "\(args.action) of '\(args.productId)' failed: \(error?.localizedDescription ?? "unknown error")",
                platformCode: ours ? nil : error?.code
            )
        }
    }
}
