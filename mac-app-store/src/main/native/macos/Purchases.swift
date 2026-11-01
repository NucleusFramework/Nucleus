// Purchases half of the StoreKit 2 bridge: buying, what the customer owns, consumables.

import AppKit
import Foundation
import StoreKit

// MARK: - Purchase

struct PurchaseArgs: Decodable {
    let productId: String
    /// `TaoWindow.nativeHandle`: an `NSView *` (or an `NSWindow *`) of the app.
    let window: Int64?
    let quantity: Int?
    let appAccountToken: String?
}

struct PurchaseOutcomeDto: Encodable {
    let status: String
    var purchase: PurchaseDto? = nil
}

func purchase(_ args: PurchaseArgs) async throws -> PurchaseOutcomeDto {
    try requireAppBundle()
    guard let product = try await Product.products(for: [args.productId]).first else {
        throw WireError(code: "productNotFound", message: "The App Store does not sell '\(args.productId)'")
    }
    var options = Set<Product.PurchaseOption>()
    if let quantity = args.quantity, quantity != 1 {
        options.insert(.quantity(quantity))
    }
    if let token = args.appAccountToken {
        guard let uuid = UUID(uuidString: token) else {
            throw WireError(code: "invalidArgument", message: "appAccountToken '\(token)' is not a UUID")
        }
        options.insert(.appAccountToken(uuid))
    }

    // StoreKit ignores task cancellation: a cancelled caller leaves the sheet to the customer.
    // Closing it from here (endSheet on its ViewBridge chain) was tried: StoreKit then never
    // returns, and every later purchase hangs behind it.
    let window = await presentationWindow(args.window)
    let result: Product.PurchaseResult
    do {
        if #available(macOS 15.2, *), let window {
            result = try await product.purchase(confirmIn: window, options: options)
        } else {
            result = try await product.purchase(options: options)
        }
    } catch StoreKitError.userCancelled {
        // Some sheets report the customer's cancel as an error rather than a result.
        return PurchaseOutcomeDto(status: "cancelled")
    }

    switch result {
    case .success(let verification):
        guard case .verified(let transaction) = verification else {
            throw unverified(verification)
        }
        // A consumable stays unfinished until the app reports it delivered (`consume`):
        // StoreKit hands it back at the next launch if the app dies in between.
        if transaction.productType != .consumable {
            await transaction.finish()
        }
        return PurchaseOutcomeDto(status: "success", purchase: PurchaseDto(transaction, jws: verification.jwsRepresentation))
    case .pending:
        return PurchaseOutcomeDto(status: "pending")
    case .userCancelled:
        return PurchaseOutcomeDto(status: "cancelled")
    @unknown default:
        throw WireError(code: "platform", message: "Unknown purchase result \(result)")
    }
}

/// The window the purchase sheet attaches to: the one given, else the app's key, main or first
/// visible window. `nil` without any (StoreKit then picks, or refuses).
@MainActor
private func presentationWindow(_ handle: Int64?) -> NSWindow? {
    if let handle, handle != 0, let pointer = UnsafeRawPointer(bitPattern: Int(handle)) {
        let object = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
        if let window = object as? NSWindow { return window }
        if let view = object as? NSView, let window = view.window { return window }
    }
    guard let app = NSApp else { return nil }
    return app.keyWindow ?? app.mainWindow ?? app.windows.first { $0.isVisible }
}

private func unverified<T>(_ verification: VerificationResult<T>) -> WireError {
    if case .unverified(_, let error) = verification {
        return WireError(code: "verificationFailed", message: "StoreKit could not verify the transaction: \(error)")
    }
    return WireError(code: "verificationFailed", message: "StoreKit could not verify the transaction")
}

// MARK: - Owned

/// Active non-consumables and subscriptions, plus consumables bought but not yet consumed.
/// Unverified transactions are left out: nothing is granted on a signature StoreKit rejects.
func ownedPurchases() async throws -> [PurchaseDto] {
    try requireAppBundle()
    var seen = Set<UInt64>()
    var result: [PurchaseDto] = []
    for await verification in Transaction.currentEntitlements {
        guard case .verified(let transaction) = verification, transaction.productType != .consumable,
              seen.insert(transaction.id).inserted else { continue }
        result.append(PurchaseDto(transaction, jws: verification.jwsRepresentation))
    }
    for await verification in Transaction.unfinished {
        guard case .verified(let transaction) = verification, transaction.productType == .consumable,
              transaction.revocationDate == nil, seen.insert(transaction.id).inserted else { continue }
        result.append(PurchaseDto(transaction, jws: verification.jwsRepresentation))
    }
    return result
}

// MARK: - Entitlement

struct EntitlementArgs: Decodable { let productId: String }

struct EntitlementDto: Encodable {
    let productId: String
    let state: String
    var expirationTime: Int64? = nil
    var willAutoRenew: Bool? = nil
    var purchase: PurchaseDto? = nil
}

func entitlement(_ productId: String) async throws -> EntitlementDto {
    try requireAppBundle()
    let product = try await Product.products(for: [productId]).first
    if product?.type == .consumable {
        return EntitlementDto(productId: productId, state: "notOwned")
    }
    if let subscription = product?.subscription {
        return try await subscriptionEntitlement(productId, subscription)
    }
    if let (transaction, jws) = await currentEntitlement(productId) {
        return EntitlementDto(
            productId: productId,
            state: "active",
            expirationTime: millis(transaction.expirationDate),
            purchase: PurchaseDto(transaction, jws: jws)
        )
    }
    // Not entitled: say why when the last transaction tells.
    if let verification = await Transaction.latest(for: productId), case .verified(let transaction) = verification {
        let purchase = PurchaseDto(transaction, jws: verification.jwsRepresentation)
        if transaction.revocationDate != nil {
            return EntitlementDto(productId: productId, state: "revoked", purchase: purchase)
        }
        if let expiration = transaction.expirationDate, expiration <= Date() {
            return EntitlementDto(productId: productId, state: "expired", expirationTime: millis(expiration), purchase: purchase)
        }
    }
    return EntitlementDto(productId: productId, state: "notOwned")
}

private func currentEntitlement(_ productId: String) async -> (Transaction, String)? {
    if #available(macOS 15.4, *) {
        for await verification in Transaction.currentEntitlements(for: productId) {
            if case .verified(let transaction) = verification { return (transaction, verification.jwsRepresentation) }
        }
        return nil
    }
    guard let verification = await Transaction.currentEntitlement(for: productId),
          case .verified(let transaction) = verification else { return nil }
    return (transaction, verification.jwsRepresentation)
}

/// The group's status, as far as it concerns [productId]: a status carried by another product
/// of the same group (another tier) leaves this one not owned.
private func subscriptionEntitlement(_ productId: String, _ subscription: Product.SubscriptionInfo) async throws -> EntitlementDto {
    for status in try await subscription.status {
        guard case .verified(let transaction) = status.transaction, transaction.productID == productId else { continue }
        let state: String
        switch status.state {
        case .subscribed: state = "active"
        case .inGracePeriod: state = "inGracePeriod"
        case .inBillingRetryPeriod: state = "inBillingRetry"
        case .expired: state = "expired"
        case .revoked: state = "revoked"
        default: state = "notOwned"
        }
        var willAutoRenew: Bool?
        if case .verified(let renewal) = status.renewalInfo {
            willAutoRenew = renewal.willAutoRenew
        }
        return EntitlementDto(
            productId: productId,
            state: state,
            expirationTime: millis(transaction.expirationDate),
            willAutoRenew: willAutoRenew,
            purchase: PurchaseDto(transaction, jws: status.transaction.jwsRepresentation)
        )
    }
    return EntitlementDto(productId: productId, state: "notOwned")
}

// MARK: - Consumables

struct ConsumeArgs: Decodable { let transactionId: String }

/// Finishes the consumable's transaction. Already finished (a retry, another launch): nothing to do.
func consume(_ transactionId: String) async throws {
    try requireAppBundle()
    for await verification in Transaction.unfinished {
        if case .verified(let transaction) = verification, String(transaction.id) == transactionId {
            await transaction.finish()
            return
        }
    }
}

// MARK: - Wire

struct PurchaseDto: Encodable {
    let productId: String
    let productType: String
    let transactionId: String
    let originalTransactionId: String
    let purchaseTime: Int64
    let expirationTime: Int64?
    let revocationTime: Int64?
    let quantity: Int
    let environment: String
    let appAccountToken: String?
    let jws: String

    init(_ transaction: Transaction, jws: String) {
        productId = transaction.productID
        productType = typeName(transaction.productType)
        transactionId = String(transaction.id)
        originalTransactionId = String(transaction.originalID)
        purchaseTime = millis(transaction.purchaseDate) ?? 0
        expirationTime = millis(transaction.expirationDate)
        revocationTime = millis(transaction.revocationDate)
        quantity = transaction.purchasedQuantity
        appAccountToken = transaction.appAccountToken?.uuidString
        self.jws = jws
        let raw: String
        if #available(macOS 13.0, *) {
            raw = transaction.environment.rawValue
        } else {
            raw = transaction.environmentStringRepresentation
        }
        switch raw {
        case "Production": environment = "production"
        case "Sandbox": environment = "sandbox"
        case "Xcode": environment = "xcode"
        default: environment = "unknown"
        }
    }
}

func typeName(_ type: Product.ProductType) -> String {
    switch type {
    case .consumable: return "consumable"
    case .nonConsumable: return "nonConsumable"
    case .autoRenewable: return "autoRenewable"
    case .nonRenewable: return "nonRenewing"
    default: return "unknown"
    }
}

func millis(_ date: Date?) -> Int64? {
    date.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
}
