// Out-of-band half of the StoreKit 2 bridge: transaction updates, the app's own transaction,
// its App Store page.

import AppKit
import CryptoKit
import Foundation
import StoreKit

// MARK: - Transaction updates

private var updatesTask: Task<Void, Never>?

/// Starts listening for the life of the process; idempotent.
///
/// Unfinished transactions (a consumable not yet consumed, a purchase the app died before
/// delivering) are replayed first, from `Transaction.unfinished`: StoreKit only replays them to
/// `Transaction.updates` at process launch, not to a listener started later (measured). Each
/// live transaction is delivered once whichever path brings it first; a revocation always is.
@_cdecl("nms_start_updates")
public func nmsStartUpdates() -> Bool {
    guard isAppBundle else { return false }
    return locked {
        if updatesTask == nil {
            updatesTask = Task.detached {
                // Subscribed before the replay, so nothing that arrives meanwhile is missed.
                let live = Task.detached {
                    for await verification in Transaction.updates {
                        await deliver(verification)
                    }
                }
                for await verification in Transaction.unfinished {
                    await deliver(verification)
                }
                await live.value
            }
        }
        return true
    }
}

private actor Delivered {
    private var ids = Set<UInt64>()

    /// `true` the first time [id] is seen.
    func insert(_ id: UInt64) -> Bool { ids.insert(id).inserted }
}

private let delivered = Delivered()

private func deliver(_ verification: VerificationResult<Transaction>) async {
    // Nothing is granted on a signature StoreKit rejects; it stays unfinished and comes back.
    guard case .verified(let transaction) = verification else { return }
    if transaction.revocationDate == nil, await !delivered.insert(transaction.id) { return }
    // Same rule as purchase(): a live consumable waits for consume().
    if transaction.productType != .consumable || transaction.revocationDate != nil {
        await transaction.finish()
        _ = await pendingConsumables.remove(transaction.id)
    } else {
        await pendingConsumables.add(transaction, jws: verification.jwsRepresentation)
    }
    emitTransaction(transaction, jws: verification.jwsRepresentation)
}

private struct TransactionEvent: Encodable {
    let type = "transaction"
    let purchase: PurchaseDto
}

/// Reports a transaction no request is waiting for. Also used by purchase() when its caller
/// cancelled while the sheet was up and the customer bought anyway.
func emitTransaction(_ transaction: Transaction, jws: String) {
    nms_event(encode(TransactionEvent(purchase: PurchaseDto(transaction, jws: jws))))
}

// MARK: - App transaction

struct AppTransactionDto: Encodable {
    let bundleId: String
    let appId: String?
    let appVersion: String
    let originalAppVersion: String
    let originalPurchaseTime: Int64?
    let preorderTime: Int64?
    let revocationTime: Int64?
    let environment: String
    let jws: String
}

func appTransaction() async throws -> AppTransactionDto {
    try requireAppBundle()
    guard #available(macOS 13.0, *) else {
        throw WireError(code: "unsupported", message: "AppTransaction needs macOS 13")
    }
    let verification = try await AppTransaction.shared
    guard case .verified(let app) = verification else {
        throw WireError(code: "verificationFailed", message: "StoreKit could not verify the app transaction")
    }
    return appTransactionDto(app, verification.jwsRepresentation)
}

@available(macOS 13.0, *)
private func appTransactionDto(_ app: AppTransaction, _ jws: String) -> AppTransactionDto {
    AppTransactionDto(
        bundleId: app.bundleID,
        appId: app.appID.map { String($0) },
        appVersion: app.appVersion,
        originalAppVersion: app.originalAppVersion,
        originalPurchaseTime: millis(app.originalPurchaseDate),
        preorderTime: millis(app.preorderDate),
        revocationTime: millis(revocationDate(app)),
        environment: environmentName(app.environment.rawValue),
        jws: jws
    )
}

// MARK: - App license

/// `status`: valid, revoked, invalid (a proof that fails a check), unavailable (no proof now).
struct LicenseDto: Encodable {
    let status: String
    let reason: String?
    let app: AppTransactionDto?
}

/// Whether this copy of the app was got from the App Store by this customer, for this Mac: the
/// Mac App Store's counterpart of a license, since it neither encrypts apps nor has one. A copied
/// bundle runs; only this check tells it apart. Every check Apple documents for an
/// `AppTransaction`, on the device:
/// - the signature (StoreKit's `VerificationResult`);
/// - the bundle id, so a proof made for another app does not count;
/// - the device: SHA-384 of the proof's nonce and this Mac's `deviceVerificationID`, both as
///   lowercase UUID strings, must equal `deviceVerification`, so a proof copied from another
///   Mac (a container copied along with the app) does not count;
/// - the revocation (a refund).
/// No proof at all (offline on the first launch, no App Store account) is `unavailable`, not
/// `invalid`: the app decides how long to trust it.
func license() async -> LicenseDto {
    func unavailable(_ reason: String) -> LicenseDto { LicenseDto(status: "unavailable", reason: reason, app: nil) }
    guard isAppBundle, let bundleId = Bundle.main.bundleIdentifier else { return unavailable("not an app bundle") }
    guard #available(macOS 13.0, *) else { return unavailable("AppTransaction needs macOS 13") }
    let verification: VerificationResult<AppTransaction>
    do {
        verification = try await AppTransaction.shared
    } catch {
        return unavailable("no app transaction: \(error.localizedDescription)")
    }
    let app: AppTransaction
    switch verification {
    case .unverified(let unsigned, let error):
        let dto = appTransactionDto(unsigned, verification.jwsRepresentation)
        return LicenseDto(status: "invalid", reason: "signature: \(error.localizedDescription)", app: dto)
    case .verified(let signed):
        app = signed
    }
    let dto = appTransactionDto(app, verification.jwsRepresentation)
    guard app.bundleID == bundleId else {
        return LicenseDto(status: "invalid", reason: "made for \(app.bundleID), not \(bundleId)", app: dto)
    }
    guard let device = AppStore.deviceVerificationID else {
        return LicenseDto(status: "unavailable", reason: "no device verification id", app: dto)
    }
    let text = verification.deviceVerificationNonce.uuidString.lowercased() + device.uuidString.lowercased()
    guard Data(SHA384.hash(data: Data(text.utf8))) == verification.deviceVerification else {
        return LicenseDto(status: "invalid", reason: "made for another Mac", app: dto)
    }
    if revocationDate(app) != nil {
        return LicenseDto(status: "revoked", reason: nil, app: dto)
    }
    return LicenseDto(status: "valid", reason: nil, app: dto)
}

/// `AppTransaction.revocationDate` is back-deployed to macOS 13 but only declared by the macOS 27
/// SDK (Swift 6.4): an older Xcode builds without it and reports no revocation.
@available(macOS 13.0, *)
private func revocationDate(_ app: AppTransaction) -> Date? {
    #if compiler(>=6.4)
    return app.revocationDate
    #else
    return nil
    #endif
}

// MARK: - Store page

struct StorePageArgs: Decodable { let appId: String? }

struct OpenedDto: Encodable { let opened: Bool }

/// Opens the app's page in the App Store app. The id is the numeric Apple ID of App Store
/// Connect; without one given, the app transaction's (absent in StoreKit Testing).
func openStorePage(_ appId: String?) async throws -> OpenedDto {
    var id = appId
    if id == nil, #available(macOS 13.0, *), isAppBundle,
       let verification = try? await AppTransaction.shared, case .verified(let app) = verification {
        id = app.appID.map { String($0) }
    }
    guard let id, !id.isEmpty, id.allSatisfy(\.isNumber),
          let url = URL(string: "macappstore://apps.apple.com/app/id\(id)") else {
        throw WireError(code: "invalidArgument", message: "No App Store id: pass the app's Apple ID from App Store Connect")
    }
    return OpenedDto(opened: NSWorkspace.shared.open(url))
}

func environmentName(_ raw: String) -> String {
    switch raw {
    case "Production": return "production"
    case "Sandbox": return "sandbox"
    case "Xcode": return "xcode"
    default: return "unknown"
    }
}
