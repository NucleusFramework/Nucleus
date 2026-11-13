#ifndef NUCLEUS_MACSTORE_H
#define NUCLEUS_MACSTORE_H

#include <stdint.h>
#ifdef __OBJC__
#import <Foundation/Foundation.h>
#endif

#pragma clang assume_nonnull begin

/*
 * The C seam between the Objective-C JNI shim and the Swift StoreKit bridge.
 *
 * Every request is answered exactly once through nms_respond, from any thread,
 * with {"ok": <payload>} or {"error": {"code", "message", "platformCode"}}.
 */

/* Implemented in Swift (MacStore.swift). */
void nms_request(int64_t id, const char *method, const char *args);
void nms_cancel(int64_t id);
int32_t nms_in_flight(void);
/* Starts the Transaction.updates listener once; false outside an app bundle. */
_Bool nms_start_updates(void);

/* Implemented by the JNI shim (NucleusMacStoreJni.m). */
void nms_respond(int64_t id, const char *envelope);
void nms_event(const char *json);

#ifdef __OBJC__
/* StoreKit Testing, in Objective-C under ARC (NucleusStoreKitTest.m): SKTestSession's alloc/init
 * and its NSError ** methods, which raw Swift function-pointer calls get wrong. */
NSObject *_Nullable nms_test_session_create(NSString *bundleId, NSURL *configuration, NSError *_Nullable *_Nullable error);
BOOL nms_test_session_act(NSObject *session, NSString *action, NSString *productId, NSError *_Nullable *_Nullable error);
/* Removes the StoreKit Testing configuration storekitagent keeps for bundleId, as Xcode does when it
 * launches an app without one. Only a debuggable process (get-task-allow) is allowed to. */
BOOL nms_test_configuration_remove(NSString *bundleId, NSError *_Nullable *_Nullable error);
/* Whether this process carries com.apple.security.get-task-allow. */
BOOL nms_is_debuggable(void);

#endif

#pragma clang assume_nonnull end

#endif
