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
        guard dlopen(args.framework, RTLD_NOW) != nil, let cls = NSClassFromString("SKTestSession") as? NSObject.Type else {
            let reason = dlerror().map { String(cString: $0) } ?? "SKTestSession not found"
            throw WireError(code: "platform", message: "Cannot load StoreKitTest from \(args.framework): \(reason)")
        }
        guard let raw = cls.perform(NSSelectorFromString("alloc"))?.takeUnretainedValue() as? NSObject else {
            throw WireError(code: "internal", message: "Cannot allocate SKTestSession")
        }
        raw.setValue(bundleId, forKey: "bundleID")
        typealias Initializer = @convention(c) (AnyObject, Selector, NSURL, UnsafeMutablePointer<NSError?>) -> AnyObject?
        let selector = NSSelectorFromString("initWithContentsOfURL:error:")
        let initialize = unsafeBitCast(class_getMethodImplementation(cls, selector), to: Initializer.self)
        var error: NSError?
        guard let made = initialize(raw, selector, URL(fileURLWithPath: args.configuration) as NSURL, &error) as? NSObject else {
            throw WireError(
                code: "invalidArgument",
                message: "Cannot read StoreKit configuration \(args.configuration): \(error?.localizedDescription ?? "unknown error")",
                platformCode: error?.code
            )
        }
        session = made
    }

    // MARK: Properties

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

    private typealias ById = @convention(c) (AnyObject, Selector, UInt, UnsafeMutablePointer<NSError?>) -> Bool
    private typealias ByProduct = @convention(c) (AnyObject, Selector, NSString, UnsafeMutablePointer<NSError?>) -> Bool

    /// Actions on the product's latest matching transaction of the session.
    ///
    /// No `declineAskToBuyTransactionWithIdentifier:`: the session marks the transaction failed,
    /// but StoreKit 2 keeps reporting it as a verified, purchased transaction (JWS identical to an
    /// approved one, and the next purchase returns it without a new charge; Xcode 27.1).
    private static let transactionActions: [String: (selector: String, matches: (NSObject) -> Bool)] = [
        "refund": ("refundTransactionWithIdentifier:error:", { _ in true }),
        "approveAskToBuy": ("approveAskToBuyTransactionWithIdentifier:error:", pendingAskToBuy),
        "resolveIssue": ("resolveIssueForTransactionWithIdentifier:error:", { $0.value(forKey: "hasPurchaseIssue") as? Bool == true }),
        "disableAutoRenew": ("disableAutoRenewForTransactionWithIdentifier:error:", { _ in true }),
        "enableAutoRenew": ("enableAutoRenewForTransactionWithIdentifier:error:", { _ in true }),
    ]

    /// Actions on the product itself.
    private static let productActions: [String: String] = [
        "expire": "expireSubscriptionWithProductIdentifier:error:",
        "forceRenewal": "forceRenewalOfSubscriptionWithProductIdentifier:error:",
        // A purchase made outside the app (another device, the App Store app).
        "buy": "buyProductWithIdentifier:error:",
    ]

    private static func pendingAskToBuy(_ transaction: NSObject) -> Bool {
        transaction.value(forKey: "pendingAskToBuyConfirmation") as? Bool == true
    }

    static func act(_ args: DevActArgs) throws {
        let session = try require()
        var error: NSError?
        let done: Bool
        if let action = transactionActions[args.action] {
            let transactions = session.perform(NSSelectorFromString("allTransactions"))?.takeUnretainedValue() as? [NSObject] ?? []
            guard let latest = transactions.last(where: { $0.value(forKey: "productIdentifier") as? String == args.productId && action.matches($0) }),
                  let id = latest.value(forKey: "identifier") as? UInt else {
                throw WireError(code: "invalidArgument", message: "No transaction of '\(args.productId)' to \(args.action)")
            }
            let selector = NSSelectorFromString(action.selector)
            done = unsafeBitCast(session.method(for: selector), to: ById.self)(session, selector, id, &error)
        } else if let name = productActions[args.action] {
            let selector = NSSelectorFromString(name)
            done = unsafeBitCast(session.method(for: selector), to: ByProduct.self)(session, selector, args.productId as NSString, &error)
        } else {
            throw WireError(code: "invalidArgument", message: "Unknown StoreKit Testing action '\(args.action)'")
        }
        guard done else {
            throw WireError(
                code: "platform",
                message: "\(args.action) of '\(args.productId)' failed: \(error?.localizedDescription ?? "unknown error")",
                platformCode: error?.code
            )
        }
    }
}
