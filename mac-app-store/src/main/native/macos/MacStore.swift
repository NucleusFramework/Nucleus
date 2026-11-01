// StoreKit 2 bridge of nucleus_macstore. The JSON shapes mirror
// dev.nucleusframework.store.mac.internal.Wire* — keep both sides in sync.

import Foundation
import StoreKit

// MARK: - Requests

private let lock = NSLock()
private var inFlight: [Int64: Task<Void, Never>] = [:]

// Synchronous, so the lock is never held across a suspension point.
func locked<T>(_ body: () -> T) -> T {
    lock.lock()
    defer { lock.unlock() }
    return body()
}

@_cdecl("nms_request")
public func nmsRequest(_ id: Int64, _ method: UnsafePointer<CChar>, _ args: UnsafePointer<CChar>) {
    let method = String(cString: method)
    let args = Data(String(cString: args).utf8)
    // Registered under the lock, so a request that finishes at once still finds itself.
    locked {
        inFlight[id] = Task {
            let envelope = await envelope(for: method, args: args)
            _ = locked { inFlight.removeValue(forKey: id) }
            nms_respond(id, envelope)
        }
    }
}

@_cdecl("nms_cancel")
public func nmsCancel(_ id: Int64) {
    // The task still answers, with a `cancelled` error.
    locked { inFlight[id] }?.cancel()
}

@_cdecl("nms_in_flight")
public func nmsInFlight() -> Int32 {
    locked { Int32(inFlight.count) }
}

private func envelope(for method: String, args: Data) async -> String {
    do {
        let payload = try await handle(method, args: args)
        try Task.checkCancellation()
        return encode(OkEnvelope(ok: payload))
    } catch is CancellationError {
        return encode(ErrorEnvelope(error: WireError(code: "cancelled", message: "The request was cancelled")))
    } catch let error as WireError {
        return encode(ErrorEnvelope(error: error))
    } catch {
        return encode(ErrorEnvelope(error: wireError(error)))
    }
}

private func handle(_ method: String, args: Data) async throws -> AnyEncodable {
    switch method {
    case "availability":
        return AnyEncodable(AvailabilityDto(state: availability()))
    case "products":
        let request = try decode(ProductsArgs.self, args)
        try requireAppBundle()
        return AnyEncodable(try await products(request.ids))
    case "purchase":
        return AnyEncodable(try await purchase(try decode(PurchaseArgs.self, args)))
    case "purchases":
        return AnyEncodable(try await ownedPurchases())
    case "entitlement":
        return AnyEncodable(try await entitlement(try decode(EntitlementArgs.self, args).productId))
    case "consume":
        try await consume(try decode(ConsumeArgs.self, args).transactionId)
        return AnyEncodable(Empty())
    case "appTransaction":
        return AnyEncodable(try await appTransaction())
    case "openStorePage":
        return AnyEncodable(try await openStorePage(try decode(StorePageArgs.self, args).appId))
    case "dev.configure":
        let request = try decode(DevConfigureArgs.self, args)
        try DevSession.configure(request)
        return AnyEncodable(Empty())
    case "dev.clearTransactions":
        try DevSession.require().perform(NSSelectorFromString("clearTransactions"))
        return AnyEncodable(Empty())
    case "dev.refund", "dev.expire", "dev.forceRenewal", "dev.buy":
        try DevSession.act(method, productId: try decode(EntitlementArgs.self, args).productId)
        return AnyEncodable(Empty())
    case "dev.setDialogs":
        let request = try decode(DevDialogsArgs.self, args)
        try DevSession.require().setValue(!request.enabled, forKey: "disableDialogs")
        return AnyEncodable(Empty())
    default:
        throw WireError(code: "invalidArgument", message: "Unknown method '\(method)'")
    }
}

// MARK: - Store

/// StoreKit identifies the app by its main bundle. A bare `java` launch has one too, the JDK's
/// (`.jdk`, package type `BNDL`), which is no app the store knows: only an `APPL` counts.
var isAppBundle: Bool {
    Bundle.main.bundleIdentifier != nil && Bundle.main.object(forInfoDictionaryKey: "CFBundlePackageType") as? String == "APPL"
}

func requireAppBundle() throws {
    guard isAppBundle else {
        throw WireError(code: "notBundled", message: "StoreKit needs an app bundle; this process runs from \(Bundle.main.bundlePath)")
    }
}

private func availability() -> String {
    guard isAppBundle else { return "notBundled" }
    return AppStore.canMakePayments ? "available" : "paymentsRestricted"
}

private func products(_ ids: [String]) async throws -> [ProductDto] {
    var result: [ProductDto] = []
    for product in try await Product.products(for: ids) {
        result.append(await ProductDto(product))
    }
    return result
}

// MARK: - Development session

/// A `.storekit` configuration served by StoreKit Testing, in this process, without Xcode.
///
/// StoreKitTest is loaded only here, never linked. `-[SKTestSession bundleID]` returns a value
/// already set; left unset it soft-links XCTest from a path that only exists under Xcode's test
/// runner and aborts. So the session is allocated, given this app's bundle id, then initialized.
private enum DevSession {
    static var session: NSObject?

    static func require() throws -> NSObject {
        guard let session else {
            throw WireError(code: "invalidArgument", message: "No StoreKit configuration is active")
        }
        return session
    }

    /// Store-side events of StoreKit Testing, as the App Store would send them.
    static func act(_ method: String, productId: String) throws {
        let session = try require()
        typealias ById = @convention(c) (AnyObject, Selector, UInt, UnsafeMutablePointer<NSError?>) -> Bool
        typealias ByProduct = @convention(c) (AnyObject, Selector, NSString, UnsafeMutablePointer<NSError?>) -> Bool
        var error: NSError?
        let done: Bool
        switch method {
        case "dev.refund":
            // The product's latest transaction in the session.
            let transactions = session.perform(NSSelectorFromString("allTransactions"))?.takeUnretainedValue() as? [NSObject] ?? []
            guard let latest = transactions.last(where: { $0.value(forKey: "productIdentifier") as? String == productId }),
                  let id = latest.value(forKey: "identifier") as? UInt else {
                throw WireError(code: "invalidArgument", message: "No transaction of '\(productId)' to refund")
            }
            let selector = NSSelectorFromString("refundTransactionWithIdentifier:error:")
            done = unsafeBitCast(session.method(for: selector), to: ById.self)(session, selector, id, &error)
        default:
            let name = [
                "dev.expire": "expireSubscriptionWithProductIdentifier:error:",
                "dev.forceRenewal": "forceRenewalOfSubscriptionWithProductIdentifier:error:",
                // A purchase made outside the app (another device, the App Store app).
                "dev.buy": "buyProductWithIdentifier:error:",
            ][method]!
            let selector = NSSelectorFromString(name)
            done = unsafeBitCast(session.method(for: selector), to: ByProduct.self)(session, selector, productId as NSString, &error)
        }
        guard done else {
            throw WireError(code: "platform", message: "\(method) failed: \(error?.localizedDescription ?? "unknown error")", platformCode: error?.code)
        }
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
}

// MARK: - Wire format

struct WireError: Error, Encodable {
    var code: String
    var message: String
    var platformCode: Int? = nil
}

private struct OkEnvelope: Encodable { let ok: AnyEncodable }
private struct ErrorEnvelope: Encodable { let error: WireError }
private struct Empty: Encodable {}
private struct AvailabilityDto: Encodable { let state: String }
private struct ProductsArgs: Decodable { let ids: [String] }
private struct DevDialogsArgs: Decodable { let enabled: Bool }
struct DevConfigureArgs: Decodable {
    let configuration: String
    let framework: String
}

struct AnyEncodable: Encodable {
    private let value: Encodable
    init(_ value: Encodable) { self.value = value }
    func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
}

private struct PeriodDto: Encodable {
    let value: Int
    let unit: String

    init(_ period: Product.SubscriptionPeriod) {
        value = period.value
        switch period.unit {
        case .day: unit = "day"
        case .week: unit = "week"
        case .month: unit = "month"
        case .year: unit = "year"
        @unknown default: unit = "unknown"
        }
    }
}

private struct OfferDto: Encodable {
    let displayPrice: String
    let price: String
    let period: PeriodDto
    let periodCount: Int
    let paymentMode: String

    init(_ offer: Product.SubscriptionOffer) {
        displayPrice = offer.displayPrice
        price = "\(offer.price)"
        period = PeriodDto(offer.period)
        periodCount = offer.periodCount
        switch offer.paymentMode {
        case .freeTrial: paymentMode = "freeTrial"
        case .payAsYouGo: paymentMode = "payAsYouGo"
        case .payUpFront: paymentMode = "payUpFront"
        default: paymentMode = "unknown"
        }
    }
}

private struct SubscriptionDto: Encodable {
    let period: PeriodDto
    let groupId: String
    let introductoryOffer: OfferDto?
}

private struct ProductDto: Encodable {
    let id: String
    let title: String
    let description: String
    let type: String
    let displayPrice: String
    let price: String
    let currencyCode: String?
    let subscription: SubscriptionDto?

    init(_ product: Product) async {
        id = product.id
        title = product.displayName
        description = product.description
        type = typeName(product.type)
        displayPrice = product.displayPrice
        // Decimal's description is locale-independent ("4.99").
        price = "\(product.price)"
        currencyCode = product.priceFormatStyle.currencyCode
        if let info = product.subscription {
            // Only an offer this customer can still redeem, as the Microsoft Store reports it.
            let eligible = await info.isEligibleForIntroOffer
            subscription = SubscriptionDto(
                period: PeriodDto(info.subscriptionPeriod),
                groupId: info.subscriptionGroupID,
                introductoryOffer: eligible ? info.introductoryOffer.map(OfferDto.init) : nil
            )
        } else {
            subscription = nil
        }
    }
}

private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
    do {
        return try JSONDecoder().decode(type, from: data)
    } catch {
        throw WireError(code: "invalidArgument", message: "Malformed arguments: \(error)")
    }
}

func encode<T: Encodable>(_ value: T) -> String {
    guard let data = try? JSONEncoder().encode(value), let json = String(data: data, encoding: .utf8) else {
        return #"{"error":{"code":"internal","message":"Failed to encode the response"}}"#
    }
    return json
}

private func wireError(_ error: Error) -> WireError {
    let nsError = error as NSError
    if let purchaseError = error as? Product.PurchaseError {
        switch purchaseError {
        case .productUnavailable:
            return WireError(code: "productNotFound", message: "The product is not available")
        case .purchaseNotAllowed:
            return WireError(code: "purchaseNotAllowed", message: "This customer cannot make purchases")
        case .invalidQuantity:
            return WireError(code: "invalidArgument", message: "Invalid quantity")
        default:
            return WireError(code: "platform", message: String(describing: purchaseError))
        }
    }
    if let storeKitError = error as? StoreKitError {
        switch storeKitError {
        case .networkError(let underlying):
            return WireError(code: "network", message: underlying.localizedDescription, platformCode: (underlying as NSError).code)
        case .notAvailableInStorefront:
            return WireError(code: "notAvailableInStorefront", message: "The product is not sold in this storefront")
        case .userCancelled:
            return WireError(code: "cancelled", message: "The user cancelled")
        default:
            return WireError(code: "platform", message: String(describing: storeKitError))
        }
    }
    return WireError(code: "platform", message: nsError.localizedDescription, platformCode: nsError.code)
}
