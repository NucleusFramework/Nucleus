// Out-of-band half of the StoreKit 2 bridge: transaction updates, the app's own transaction,
// its App Store page.

import AppKit
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
    return AppTransactionDto(
        bundleId: app.bundleID,
        appId: app.appID.map { String($0) },
        appVersion: app.appVersion,
        originalAppVersion: app.originalAppVersion,
        originalPurchaseTime: millis(app.originalPurchaseDate),
        preorderTime: millis(app.preorderDate),
        revocationTime: millis(revocationDate(app)),
        environment: environmentName(app.environment.rawValue),
        jws: verification.jwsRepresentation
    )
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
