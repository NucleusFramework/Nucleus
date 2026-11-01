// SKTestSession (StoreKitTest, loaded with dlopen by MacStore's DevSession) driven from
// Objective-C: ARC gets alloc/init and NSError ** out-parameters right, which Swift calls through
// raw method implementations do not (an error StoreKit returned was over-released and crashed the
// process when the autorelease pool drained).

#import <Foundation/Foundation.h>
#include "nucleus_macstore.h"

@protocol NMSTestSession <NSObject>
- (nullable instancetype)initWithContentsOfURL:(NSURL *)fileURL error:(NSError **)error;
- (NSArray *)allTransactions;
- (BOOL)refundTransactionWithIdentifier:(NSUInteger)identifier error:(NSError **)error;
- (BOOL)approveAskToBuyTransactionWithIdentifier:(NSUInteger)identifier error:(NSError **)error;
- (BOOL)resolveIssueForTransactionWithIdentifier:(NSUInteger)identifier error:(NSError **)error;
- (BOOL)disableAutoRenewForTransactionWithIdentifier:(NSUInteger)identifier error:(NSError **)error;
- (BOOL)enableAutoRenewForTransactionWithIdentifier:(NSUInteger)identifier error:(NSError **)error;
- (BOOL)expireSubscriptionWithProductIdentifier:(NSString *)productIdentifier error:(NSError **)error;
- (BOOL)forceRenewalOfSubscriptionWithProductIdentifier:(NSString *)productIdentifier error:(NSError **)error;
- (BOOL)buyProductWithIdentifier:(NSString *)productIdentifier error:(NSError **)error;
@end

static NSString *const NMSErrorDomain = @"NucleusMacStore";

NSObject *nms_test_session_create(NSString *bundleId, NSURL *configuration, NSError **error) {
    Class cls = NSClassFromString(@"SKTestSession");
    if (cls == nil) return nil;
    id<NMSTestSession> raw = [cls alloc];
    // -bundleID returns a value already set; unset, it soft-links XCTest from a path that only
    // exists under Xcode's test runner and aborts. Set it before init.
    [(NSObject *)raw setValue:bundleId forKey:@"bundleID"];
    // Its NSError is not trusted either (see refused): a file it cannot read gets our own message.
    NSObject *session = (NSObject *)[raw initWithContentsOfURL:configuration error:NULL];
    if (session == nil && error != NULL) {
        BOOL exists = [[NSFileManager defaultManager] fileExistsAtPath:configuration.path];
        NSString *message = exists ? @"StoreKit Testing could not read it (is it a .storekit file?)" : @"the file does not exist";
        *error = [NSError errorWithDomain:@"NucleusMacStore" code:4 userInfo:@{NSLocalizedDescriptionKey : message}];
    }
    return session;
}

// StoreKitTest's NSError out-parameters cannot be trusted: on a refusal (buying a subscription
// already active) the error it writes is over-released, and the process crashed when the
// autorelease pool drained (seen in a GraalVM Lab, sporadic on the JVM). Its error is never asked
// for; a refusal is reported with our own.
static BOOL refused(BOOL done, NSString *action, NSString *productId, NSError **error) {
    if (!done && error != NULL) {
        NSString *message = [NSString stringWithFormat:@"StoreKit Testing refused to %@ '%@'", action, productId];
        *error = [NSError errorWithDomain:@"NucleusMacStore.StoreKitTesting" code:3 userInfo:@{NSLocalizedDescriptionKey : message}];
    }
    return done;
}

static NSError *nothingToAct(NSString *action, NSString *productId) {
    NSString *message = [NSString stringWithFormat:@"No transaction of '%@' to %@", productId, action];
    return [NSError errorWithDomain:NMSErrorDomain code:1 userInfo:@{NSLocalizedDescriptionKey : message}];
}

// The product's latest transaction in the session that [matching] accepts.
static NSUInteger latestTransaction(id<NMSTestSession> session, NSString *productId, BOOL (^matching)(NSObject *)) {
    NSUInteger found = NSNotFound;
    for (NSObject *transaction in [session allTransactions]) {
        if ([[transaction valueForKey:@"productIdentifier"] isEqual:productId] && matching(transaction)) {
            found = [[transaction valueForKey:@"identifier"] unsignedIntegerValue];
        }
    }
    return found;
}

BOOL nms_test_session_act(NSObject *object, NSString *action, NSString *productId, NSError **error) {
    id<NMSTestSession> session = (id<NMSTestSession>)object;
    BOOL (^any)(NSObject *) = ^BOOL(NSObject *transaction) { return YES; };
    BOOL (^askToBuy)(NSObject *) = ^BOOL(NSObject *transaction) {
        return [[transaction valueForKey:@"pendingAskToBuyConfirmation"] boolValue];
    };
    BOOL (^issue)(NSObject *) = ^BOOL(NSObject *transaction) {
        return [[transaction valueForKey:@"hasPurchaseIssue"] boolValue];
    };

    if ([action isEqualToString:@"expire"]) return refused([session expireSubscriptionWithProductIdentifier:productId error:NULL], action, productId, error);
    if ([action isEqualToString:@"forceRenewal"]) {
        return refused([session forceRenewalOfSubscriptionWithProductIdentifier:productId error:NULL], action, productId, error);
    }
    // A purchase made outside the app (another device, the App Store app).
    if ([action isEqualToString:@"buy"]) return refused([session buyProductWithIdentifier:productId error:NULL], action, productId, error);

    BOOL (^matching)(NSObject *) = [action isEqualToString:@"approveAskToBuy"] ? askToBuy
        : [action isEqualToString:@"resolveIssue"] ? issue
        : any;
    NSUInteger identifier = latestTransaction(session, productId, matching);
    if (identifier == NSNotFound) {
        if (error != NULL) *error = nothingToAct(action, productId);
        return NO;
    }
    if ([action isEqualToString:@"refund"]) return refused([session refundTransactionWithIdentifier:identifier error:NULL], action, productId, error);
    if ([action isEqualToString:@"approveAskToBuy"]) {
        return refused([session approveAskToBuyTransactionWithIdentifier:identifier error:NULL], action, productId, error);
    }
    if ([action isEqualToString:@"resolveIssue"]) return refused([session resolveIssueForTransactionWithIdentifier:identifier error:NULL], action, productId, error);
    if ([action isEqualToString:@"disableAutoRenew"]) {
        return refused([session disableAutoRenewForTransactionWithIdentifier:identifier error:NULL], action, productId, error);
    }
    if ([action isEqualToString:@"enableAutoRenew"]) {
        return refused([session enableAutoRenewForTransactionWithIdentifier:identifier error:NULL], action, productId, error);
    }
    if (error != NULL) {
        NSString *message = [NSString stringWithFormat:@"Unknown StoreKit Testing action '%@'", action];
        *error = [NSError errorWithDomain:NMSErrorDomain code:2 userInfo:@{NSLocalizedDescriptionKey : message}];
    }
    return NO;
}
