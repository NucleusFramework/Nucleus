#ifndef NUCLEUS_MACSTORE_H
#define NUCLEUS_MACSTORE_H

#include <stdint.h>

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

#endif
