#import <Cocoa/Cocoa.h>
#import <UserNotifications/UserNotifications.h>
#include <jni.h>

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = NULL;
static BOOL g_hasKotlinDelegate = NO;

#define BRIDGE_CLASS "dev/nucleusframework/nucleus/notification/macos/NativeMacNotificationBridge"

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static JNIEnv *getEnv(BOOL *didAttach) {
    *didAttach = NO;
    if (g_jvm == NULL) return NULL;

    JNIEnv *env = NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) {
            return NULL;
        }
        *didAttach = YES;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static void releaseEnv(BOOL didAttach) {
    if (didAttach && g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void clearException(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

static jstring toJString(JNIEnv *env, NSString *str) {
    if (str == nil) return NULL;
    const char *utf = [str UTF8String];
    return (*env)->NewStringUTF(env, utf ? utf : "");
}

// Like toJString but never returns NULL — maps nil to empty string.
// Use for Kotlin non-nullable String parameters.
static jstring toJStringNonNull(JNIEnv *env, NSString *str) {
    if (str == nil || str.length == 0) return (*env)->NewStringUTF(env, "");
    const char *utf = [str UTF8String];
    return (*env)->NewStringUTF(env, utf ? utf : "");
}

static NSString *toNSString(JNIEnv *env, jstring jstr) {
    if (jstr == NULL) return nil;
    const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (utf == NULL) return nil;
    NSString *str = [NSString stringWithUTF8String:utf];
    (*env)->ReleaseStringUTFChars(env, jstr, utf);
    return str;
}

// ============================================================================
// Delegate
// ============================================================================

API_AVAILABLE(macos(10.14))
@interface NucleusNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation NucleusNotificationDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    @autoreleasepool {
        // Default: show banner + sound + list when no Kotlin delegate is set
        UNNotificationPresentationOptions defaultOptions = UNNotificationPresentationOptionSound;
        if (@available(macOS 11.0, *)) {
            defaultOptions |= UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList;
        } else {
            defaultOptions |= UNNotificationPresentationOptionAlert;
        }

        if (!g_hasKotlinDelegate) {
            completionHandler(defaultOptions);
            return;
        }

        BOOL didAttach = NO;
        JNIEnv *env = getEnv(&didAttach);
        if (env == NULL) {
            completionHandler(defaultOptions);
            return;
        }

        UNNotificationRequest *request = notification.request;
        UNNotificationContent *content = request.content;
        jlong dateMs = (jlong)([notification.date timeIntervalSince1970] * 1000.0);

        jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
        if (cls == NULL) {
            clearException(env);
            releaseEnv(didAttach);
            completionHandler(defaultOptions);
            return;
        }

        jmethodID method = (*env)->GetStaticMethodID(env, cls, "onWillPresentNotification",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;)I");
        if (method == NULL) {
            clearException(env);
            releaseEnv(didAttach);
            completionHandler(defaultOptions);
            return;
        }

        jstring jIdentifier = toJStringNonNull(env, request.identifier);
        jstring jTitle = toJStringNonNull(env, content.title);
        jstring jSubtitle = toJStringNonNull(env, content.subtitle);
        jstring jBody = toJStringNonNull(env, content.body);
        jstring jCategoryId = toJStringNonNull(env, content.categoryIdentifier);
        jstring jThreadId = toJStringNonNull(env, content.threadIdentifier);

        jint result = (*env)->CallStaticIntMethod(env, cls, method,
            jIdentifier, jTitle, jSubtitle, jBody, dateMs, jCategoryId, jThreadId);

        BOOL hadException = (*env)->ExceptionCheck(env);
        if (hadException) {
            (*env)->ExceptionDescribe(env); // prints to stderr for debugging
            (*env)->ExceptionClear(env);
        }
        releaseEnv(didAttach);

        // If Kotlin callback failed (exception → result=0), fall back to defaults
        UNNotificationPresentationOptions options = hadException ? defaultOptions
            : (UNNotificationPresentationOptions)result;
        if (options == UNNotificationPresentationOptionNone) {
            options = defaultOptions;
        }
        completionHandler(options);
    }
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
didReceiveNotificationResponse:(UNNotificationResponse *)response
         withCompletionHandler:(void (^)(void))completionHandler {
    @autoreleasepool {
        if (!g_hasKotlinDelegate) {
            completionHandler();
            return;
        }

        BOOL didAttach = NO;
        JNIEnv *env = getEnv(&didAttach);
        if (env == NULL) {
            completionHandler();
            return;
        }

        UNNotification *notification = response.notification;
        UNNotificationRequest *request = notification.request;
        UNNotificationContent *content = request.content;
        jlong dateMs = (jlong)([notification.date timeIntervalSince1970] * 1000.0);

        NSString *userText = nil;
        if ([response isKindOfClass:[UNTextInputNotificationResponse class]]) {
            userText = ((UNTextInputNotificationResponse *)response).userText;
        }

        jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
        if (cls == NULL) {
            clearException(env);
            releaseEnv(didAttach);
            completionHandler();
            return;
        }

        jmethodID method = (*env)->GetStaticMethodID(env, cls, "onDidReceiveResponse",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (method == NULL) {
            clearException(env);
            releaseEnv(didAttach);
            completionHandler();
            return;
        }

        jstring jActionId = toJStringNonNull(env, response.actionIdentifier);
        jstring jIdentifier = toJStringNonNull(env, request.identifier);
        jstring jTitle = toJStringNonNull(env, content.title);
        jstring jSubtitle = toJStringNonNull(env, content.subtitle);
        jstring jBody = toJStringNonNull(env, content.body);
        jstring jCategoryId = toJStringNonNull(env, content.categoryIdentifier);
        jstring jThreadId = toJStringNonNull(env, content.threadIdentifier);
        jstring jUserText = toJString(env, userText); // nullable — stays toJString

        (*env)->CallStaticVoidMethod(env, cls, method,
            jActionId, jIdentifier, jTitle, jSubtitle, jBody, dateMs, jCategoryId, jThreadId, jUserText);

        clearException(env);
        releaseEnv(didAttach);
        completionHandler();
    }
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
   openSettingsForNotification:(UNNotification *)notification {
    @autoreleasepool {
        if (!g_hasKotlinDelegate) return;

        BOOL didAttach = NO;
        JNIEnv *env = getEnv(&didAttach);
        if (env == NULL) return;

        jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
        if (cls == NULL) {
            clearException(env);
            releaseEnv(didAttach);
            return;
        }

        jmethodID method = (*env)->GetStaticMethodID(env, cls, "onOpenSettings",
            "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;)V");
        if (method == NULL) {
            clearException(env);
            releaseEnv(didAttach);
            return;
        }

        if (notification != nil) {
            UNNotificationRequest *request = notification.request;
            UNNotificationContent *content = request.content;
            jlong dateMs = (jlong)([notification.date timeIntervalSince1970] * 1000.0);

            jstring jId = toJString(env, request.identifier);
            jstring jTitle = toJString(env, content.title);
            jstring jSubtitle = toJString(env, content.subtitle);
            jstring jBody = toJString(env, content.body);
            jstring jCategoryId = toJString(env, content.categoryIdentifier);
            jstring jThreadId = toJString(env, content.threadIdentifier);

            (*env)->CallStaticVoidMethod(env, cls, method,
                JNI_TRUE, jId, jTitle, jSubtitle, jBody, dateMs, jCategoryId, jThreadId);
        } else {
            (*env)->CallStaticVoidMethod(env, cls, method,
                JNI_FALSE, NULL, NULL, NULL, NULL, (jlong)0, NULL, NULL);
        }

        clearException(env);
        releaseEnv(didAttach);
    }
}

@end

static NucleusNotificationDelegate *g_delegate = nil;

// Installs the native delegate synchronously so it is ready before any notification fires.
static void ensureDelegateInstalled(void) {
    if (g_delegate != nil) return;
    if (@available(macOS 10.14, *)) {
        g_delegate = [[NucleusNotificationDelegate alloc] init];
        // Must assign on main thread (Apple requirement), but synchronously
        // so the delegate is active before the first notification fires.
        if ([NSThread isMainThread]) {
            [UNUserNotificationCenter currentNotificationCenter].delegate = g_delegate;
        } else {
            dispatch_sync(dispatch_get_main_queue(), ^{
                [UNUserNotificationCenter currentNotificationCenter].delegate = g_delegate;
            });
        }
    }
}

// ============================================================================
// Sound helper
// ============================================================================

static UNNotificationSound * _Nullable makeSoundFromType(int soundType, NSString *soundName, float volume)
    API_AVAILABLE(macos(10.14)) {
    switch (soundType) {
        case 0: return nil; // no sound
        case 1: return [UNNotificationSound defaultSound];
        case 2: return [UNNotificationSound soundNamed:soundName];
        case 3: return [UNNotificationSound defaultCriticalSound];
        case 4:
            if (@available(macOS 12.0, *)) {
                return [UNNotificationSound criticalSoundNamed:soundName withAudioVolume:volume];
            }
            return [UNNotificationSound soundNamed:soundName];
        case 5:
            if (@available(macOS 12.0, *)) {
                return [UNNotificationSound defaultCriticalSoundWithAudioVolume:volume];
            }
            return [UNNotificationSound defaultCriticalSound];
        default: return nil;
    }
}

// ============================================================================
// JNI: Authorization
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeRequestAuthorization(
    JNIEnv *env, jclass clazz, jint optionsMask, jlong callbackId) {
    if (@available(macOS 10.14, *)) {
        ensureDelegateInstalled();
        // Must run on main thread for the permission dialog to appear
        dispatch_async(dispatch_get_main_queue(), ^{
            UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
            [center requestAuthorizationWithOptions:(UNAuthorizationOptions)optionsMask
                                 completionHandler:^(BOOL granted, NSError *error) {
                @autoreleasepool {
                    BOOL didAttach = NO;
                    JNIEnv *cbEnv = getEnv(&didAttach);
                    if (cbEnv == NULL) return;

                    jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                    if (cls != NULL) {
                        jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
                            "onAuthorizationResult", "(JZLjava/lang/String;)V");
                        if (method != NULL) {
                            jstring jError = error ? toJString(cbEnv, [error localizedDescription]) : NULL;
                            (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId,
                                granted ? JNI_TRUE : JNI_FALSE, jError);
                        }
                    }
                    clearException(cbEnv);
                    releaseEnv(didAttach);
                }
            }];
        });
    }
}

// ============================================================================
// JNI: Settings
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeGetNotificationSettings(
    JNIEnv *env, jclass clazz, jlong callbackId) {
    if (@available(macOS 10.14, *)) {
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
            @autoreleasepool {
                BOOL didAttach = NO;
                JNIEnv *cbEnv = getEnv(&didAttach);
                if (cbEnv == NULL) return;

                jint authStatus = (jint)settings.authorizationStatus;
                jint soundSetting = (jint)settings.soundSetting;
                jint badgeSetting = (jint)settings.badgeSetting;
                jint alertSetting = (jint)settings.alertSetting;
                jint ncSetting = (jint)settings.notificationCenterSetting;
                jint lockSetting = (jint)settings.lockScreenSetting;
                jint alertStyle = (jint)settings.alertStyle;

                jint showPreviews = 0;
                if (@available(macOS 11.0, *)) {
                    showPreviews = (jint)settings.showPreviewsSetting;
                }

                jint criticalSetting = (jint)settings.criticalAlertSetting;
                jboolean providesSettings = settings.providesAppNotificationSettings ? JNI_TRUE : JNI_FALSE;

                jint timeSensitive = 0;
                jint directMessages = 0;
                if (@available(macOS 12.0, *)) {
                    timeSensitive = (jint)settings.timeSensitiveSetting;
                    directMessages = (jint)settings.directMessagesSetting;
                }

                jint scheduled = 0;
                if (@available(macOS 15.0, *)) {
                    scheduled = (jint)settings.scheduledDeliverySetting;
                }

                jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (cls != NULL) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
                        "onNotificationSettings", "(JIIIIIIIIIZIII)V");
                    if (method != NULL) {
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId,
                            authStatus, soundSetting, badgeSetting, alertSetting,
                            ncSetting, lockSetting, alertStyle, showPreviews,
                            criticalSetting, providesSettings,
                            timeSensitive, directMessages, scheduled);
                    }
                }
                clearException(cbEnv);
                releaseEnv(didAttach);
            }
        }];
    }
}

// ============================================================================
// JNI: Add notification request
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeAddNotificationRequest(
    JNIEnv *env, jclass clazz,
    jstring jIdentifier,
    jstring jTitle, jstring jSubtitle, jstring jBody,
    jint badge,
    jint soundType, jstring jSoundName, jfloat soundVolume,
    jstring jThreadId, jstring jCategoryId, jstring jTargetContentId,
    jint interruptionLevel, jdouble relevanceScore,
    jobjectArray jUserInfoKeys, jobjectArray jUserInfoValues,
    jobjectArray jAttachmentIds, jobjectArray jAttachmentUrls,
    jint triggerType, jboolean triggerRepeats, jdouble triggerTimeInterval,
    jint calYear, jint calMonth, jint calDay,
    jint calHour, jint calMinute, jint calSecond, jint calWeekday,
    jlong callbackId) {

    if (@available(macOS 10.14, *)) {
        ensureDelegateInstalled();
        @autoreleasepool {
            // Build content
            UNMutableNotificationContent *content = [[UNMutableNotificationContent alloc] init];
            content.title = toNSString(env, jTitle) ?: @"";
            content.subtitle = toNSString(env, jSubtitle) ?: @"";
            content.body = toNSString(env, jBody) ?: @"";

            if (badge >= 0) {
                content.badge = @(badge);
            }

            NSString *soundName = toNSString(env, jSoundName);
            content.sound = makeSoundFromType(soundType, soundName, soundVolume);

            content.threadIdentifier = toNSString(env, jThreadId) ?: @"";
            content.categoryIdentifier = toNSString(env, jCategoryId) ?: @"";

            if (@available(macOS 11.0, *)) {
                NSString *targetId = toNSString(env, jTargetContentId);
                if (targetId.length > 0) {
                    content.targetContentIdentifier = targetId;
                }
            }

            if (@available(macOS 12.0, *)) {
                content.interruptionLevel = (UNNotificationInterruptionLevel)interruptionLevel;
                content.relevanceScore = relevanceScore;
            }

            // UserInfo
            jsize infoCount = jUserInfoKeys ? (*env)->GetArrayLength(env, jUserInfoKeys) : 0;
            if (infoCount > 0) {
                NSMutableDictionary *userInfo = [NSMutableDictionary dictionaryWithCapacity:infoCount];
                for (jsize i = 0; i < infoCount; i++) {
                    jstring jKey = (*env)->GetObjectArrayElement(env, jUserInfoKeys, i);
                    jstring jVal = (*env)->GetObjectArrayElement(env, jUserInfoValues, i);
                    NSString *key = toNSString(env, jKey);
                    NSString *val = toNSString(env, jVal);
                    if (key != nil && val != nil) {
                        userInfo[key] = val;
                    }
                }
                content.userInfo = userInfo;
            }

            // Attachments
            jsize attachCount = jAttachmentIds ? (*env)->GetArrayLength(env, jAttachmentIds) : 0;
            if (attachCount > 0) {
                NSMutableArray<UNNotificationAttachment *> *attachments =
                    [NSMutableArray arrayWithCapacity:attachCount];
                for (jsize i = 0; i < attachCount; i++) {
                    jstring jAId = (*env)->GetObjectArrayElement(env, jAttachmentIds, i);
                    jstring jAUrl = (*env)->GetObjectArrayElement(env, jAttachmentUrls, i);
                    NSString *aId = toNSString(env, jAId);
                    NSString *aUrl = toNSString(env, jAUrl);
                    if (aId != nil && aUrl != nil) {
                        NSURL *url = [NSURL fileURLWithPath:aUrl];
                        NSError *error = nil;
                        UNNotificationAttachment *att =
                            [UNNotificationAttachment attachmentWithIdentifier:aId URL:url options:nil error:&error];
                        if (att != nil) {
                            [attachments addObject:att];
                        }
                    }
                }
                content.attachments = attachments;
            }

            // Build trigger
            UNNotificationTrigger *trigger = nil;
            if (triggerType == 1) {
                // Time interval
                trigger = [UNTimeIntervalNotificationTrigger
                    triggerWithTimeInterval:triggerTimeInterval
                                   repeats:triggerRepeats];
            } else if (triggerType == 2) {
                // Calendar
                NSDateComponents *dc = [[NSDateComponents alloc] init];
                if (calYear >= 0) dc.year = calYear;
                if (calMonth >= 0) dc.month = calMonth;
                if (calDay >= 0) dc.day = calDay;
                if (calHour >= 0) dc.hour = calHour;
                if (calMinute >= 0) dc.minute = calMinute;
                if (calSecond >= 0) dc.second = calSecond;
                if (calWeekday >= 0) dc.weekday = calWeekday;
                trigger = [UNCalendarNotificationTrigger
                    triggerWithDateMatchingComponents:dc
                                             repeats:triggerRepeats];
            }

            // Build request
            NSString *identifier = toNSString(env, jIdentifier) ?: [[NSUUID UUID] UUIDString];
            UNNotificationRequest *request =
                [UNNotificationRequest requestWithIdentifier:identifier content:content trigger:trigger];

            // Add
            UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
            [center addNotificationRequest:request withCompletionHandler:^(NSError *error) {
                @autoreleasepool {
                    BOOL didAttach = NO;
                    JNIEnv *cbEnv = getEnv(&didAttach);
                    if (cbEnv == NULL) return;

                    jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                    if (cls != NULL) {
                        jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
                            "onRequestAdded", "(JLjava/lang/String;)V");
                        if (method != NULL) {
                            jstring jError = error ? toJString(cbEnv, [error localizedDescription]) : NULL;
                            (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId, jError);
                        }
                    }
                    clearException(cbEnv);
                    releaseEnv(didAttach);
                }
            }];
        }
    }
}

// ============================================================================
// JNI: Remove / get pending notifications
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeRemovePendingNotifications(
    JNIEnv *env, jclass clazz, jobjectArray jIdentifiers) {
    if (@available(macOS 10.14, *)) {
        @autoreleasepool {
            jsize count = (*env)->GetArrayLength(env, jIdentifiers);
            NSMutableArray<NSString *> *ids = [NSMutableArray arrayWithCapacity:count];
            for (jsize i = 0; i < count; i++) {
                jstring jId = (*env)->GetObjectArrayElement(env, jIdentifiers, i);
                NSString *nsId = toNSString(env, jId);
                if (nsId) [ids addObject:nsId];
            }
            [[UNUserNotificationCenter currentNotificationCenter]
                removePendingNotificationRequestsWithIdentifiers:ids];
        }
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeRemoveAllPendingNotifications(
    JNIEnv *env, jclass clazz) {
    if (@available(macOS 10.14, *)) {
        [[UNUserNotificationCenter currentNotificationCenter] removeAllPendingNotificationRequests];
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeGetPendingNotifications(
    JNIEnv *env, jclass clazz, jlong callbackId) {
    if (@available(macOS 10.14, *)) {
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        [center getPendingNotificationRequestsWithCompletionHandler:^(NSArray<UNNotificationRequest *> *requests) {
            @autoreleasepool {
                BOOL didAttach = NO;
                JNIEnv *cbEnv = getEnv(&didAttach);
                if (cbEnv == NULL) return;

                jsize count = (jsize)requests.count;

                jclass stringClass = (*cbEnv)->FindClass(cbEnv, "java/lang/String");
                jobjectArray jIds = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jTitles = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jSubtitles = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jBodies = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jCatIds = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jThreadIds = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jintArray jTriggerTypes = (*cbEnv)->NewIntArray(cbEnv, count);
                jbooleanArray jTriggerRepeats = (*cbEnv)->NewBooleanArray(cbEnv, count);
                jdoubleArray jTriggerIntervals = (*cbEnv)->NewDoubleArray(cbEnv, count);

                for (jsize i = 0; i < count; i++) {
                    UNNotificationRequest *req = requests[i];
                    UNNotificationContent *content = req.content;

                    (*cbEnv)->SetObjectArrayElement(cbEnv, jIds, i, toJStringNonNull(cbEnv, req.identifier));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jTitles, i, toJStringNonNull(cbEnv, content.title));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jSubtitles, i, toJStringNonNull(cbEnv, content.subtitle));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jBodies, i, toJStringNonNull(cbEnv, content.body));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jCatIds, i, toJStringNonNull(cbEnv, content.categoryIdentifier));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jThreadIds, i, toJStringNonNull(cbEnv, content.threadIdentifier));

                    jint trigType = 0;
                    jboolean trigRepeats = JNI_FALSE;
                    jdouble trigInterval = 0.0;
                    UNNotificationTrigger *trigger = req.trigger;
                    if ([trigger isKindOfClass:[UNTimeIntervalNotificationTrigger class]]) {
                        UNTimeIntervalNotificationTrigger *timeTrig = (UNTimeIntervalNotificationTrigger *)trigger;
                        trigType = 1;
                        trigRepeats = timeTrig.repeats ? JNI_TRUE : JNI_FALSE;
                        trigInterval = timeTrig.timeInterval;
                    } else if ([trigger isKindOfClass:[UNCalendarNotificationTrigger class]]) {
                        trigType = 2;
                        trigRepeats = trigger.repeats ? JNI_TRUE : JNI_FALSE;
                    }
                    (*cbEnv)->SetIntArrayRegion(cbEnv, jTriggerTypes, i, 1, &trigType);
                    (*cbEnv)->SetBooleanArrayRegion(cbEnv, jTriggerRepeats, i, 1, &trigRepeats);
                    (*cbEnv)->SetDoubleArrayRegion(cbEnv, jTriggerIntervals, i, 1, &trigInterval);
                }

                jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (cls != NULL) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
                        "onPendingNotifications",
                        "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I[Z[D)V");
                    if (method != NULL) {
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId,
                            jIds, jTitles, jSubtitles, jBodies, jCatIds, jThreadIds,
                            jTriggerTypes, jTriggerRepeats, jTriggerIntervals);
                    }
                }
                clearException(cbEnv);
                releaseEnv(didAttach);
            }
        }];
    }
}

// ============================================================================
// JNI: Remove / get delivered notifications
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeRemoveDeliveredNotifications(
    JNIEnv *env, jclass clazz, jobjectArray jIdentifiers) {
    if (@available(macOS 10.14, *)) {
        @autoreleasepool {
            jsize count = (*env)->GetArrayLength(env, jIdentifiers);
            NSMutableArray<NSString *> *ids = [NSMutableArray arrayWithCapacity:count];
            for (jsize i = 0; i < count; i++) {
                jstring jId = (*env)->GetObjectArrayElement(env, jIdentifiers, i);
                NSString *nsId = toNSString(env, jId);
                if (nsId) [ids addObject:nsId];
            }
            [[UNUserNotificationCenter currentNotificationCenter]
                removeDeliveredNotificationsWithIdentifiers:ids];
        }
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeRemoveAllDeliveredNotifications(
    JNIEnv *env, jclass clazz) {
    if (@available(macOS 10.14, *)) {
        [[UNUserNotificationCenter currentNotificationCenter] removeAllDeliveredNotifications];
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeGetDeliveredNotifications(
    JNIEnv *env, jclass clazz, jlong callbackId) {
    if (@available(macOS 10.14, *)) {
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        [center getDeliveredNotificationsWithCompletionHandler:^(NSArray<UNNotification *> *notifications) {
            @autoreleasepool {
                BOOL didAttach = NO;
                JNIEnv *cbEnv = getEnv(&didAttach);
                if (cbEnv == NULL) return;

                jsize count = (jsize)notifications.count;

                jclass stringClass = (*cbEnv)->FindClass(cbEnv, "java/lang/String");
                jobjectArray jIds = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jTitles = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jSubtitles = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jBodies = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jlongArray jDates = (*cbEnv)->NewLongArray(cbEnv, count);
                jobjectArray jCatIds = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);
                jobjectArray jThreadIds = (*cbEnv)->NewObjectArray(cbEnv, count, stringClass, NULL);

                for (jsize i = 0; i < count; i++) {
                    UNNotification *notif = notifications[i];
                    UNNotificationRequest *req = notif.request;
                    UNNotificationContent *content = req.content;

                    (*cbEnv)->SetObjectArrayElement(cbEnv, jIds, i, toJStringNonNull(cbEnv, req.identifier));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jTitles, i, toJStringNonNull(cbEnv, content.title));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jSubtitles, i, toJStringNonNull(cbEnv, content.subtitle));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jBodies, i, toJStringNonNull(cbEnv, content.body));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jCatIds, i, toJStringNonNull(cbEnv, content.categoryIdentifier));
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jThreadIds, i, toJStringNonNull(cbEnv, content.threadIdentifier));

                    jlong dateMs = (jlong)([notif.date timeIntervalSince1970] * 1000.0);
                    (*cbEnv)->SetLongArrayRegion(cbEnv, jDates, i, 1, &dateMs);
                }

                jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (cls != NULL) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
                        "onDeliveredNotifications",
                        "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[J[Ljava/lang/String;[Ljava/lang/String;)V");
                    if (method != NULL) {
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId,
                            jIds, jTitles, jSubtitles, jBodies, jDates, jCatIds, jThreadIds);
                    }
                }
                clearException(cbEnv);
                releaseEnv(didAttach);
            }
        }];
    }
}

// ============================================================================
// JNI: Categories
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeSetNotificationCategories(
    JNIEnv *env, jclass clazz,
    jobjectArray jCatIds, jintArray jCatOptions,
    jintArray jActionCatIndices,
    jobjectArray jActionIds, jobjectArray jActionTitles,
    jintArray jActionOptions, jbooleanArray jActionIsTextInput,
    jobjectArray jActionInputBtnTitles, jobjectArray jActionInputPlaceholders) {

    if (@available(macOS 10.14, *)) {
        @autoreleasepool {
            jsize catCount = (*env)->GetArrayLength(env, jCatIds);
            jint *catOptions = (*env)->GetIntArrayElements(env, jCatOptions, NULL);

            jsize actionCount = jActionIds ? (*env)->GetArrayLength(env, jActionIds) : 0;
            jint *actionCatIndices = actionCount > 0 ?
                (*env)->GetIntArrayElements(env, jActionCatIndices, NULL) : NULL;
            jint *actionOptions = actionCount > 0 ?
                (*env)->GetIntArrayElements(env, jActionOptions, NULL) : NULL;
            jboolean *actionIsTextInput = actionCount > 0 ?
                (*env)->GetBooleanArrayElements(env, jActionIsTextInput, NULL) : NULL;

            // Group actions by category index
            NSMutableDictionary<NSNumber *, NSMutableArray<UNNotificationAction *> *> *actionsByCategory =
                [NSMutableDictionary dictionary];

            for (jsize i = 0; i < actionCount; i++) {
                NSNumber *catIdx = @(actionCatIndices[i]);
                if (actionsByCategory[catIdx] == nil) {
                    actionsByCategory[catIdx] = [NSMutableArray array];
                }

                jstring jActId = (*env)->GetObjectArrayElement(env, jActionIds, i);
                jstring jActTitle = (*env)->GetObjectArrayElement(env, jActionTitles, i);
                NSString *actId = toNSString(env, jActId);
                NSString *actTitle = toNSString(env, jActTitle);
                UNNotificationActionOptions actOpts = (UNNotificationActionOptions)actionOptions[i];

                UNNotificationAction *action;
                if (actionIsTextInput[i]) {
                    jstring jBtnTitle = (*env)->GetObjectArrayElement(env, jActionInputBtnTitles, i);
                    jstring jPlaceholder = (*env)->GetObjectArrayElement(env, jActionInputPlaceholders, i);
                    NSString *btnTitle = toNSString(env, jBtnTitle) ?: @"";
                    NSString *placeholder = toNSString(env, jPlaceholder) ?: @"";
                    action = [UNTextInputNotificationAction
                        actionWithIdentifier:actId
                                       title:actTitle
                                     options:actOpts
                        textInputButtonTitle:btnTitle
                        textInputPlaceholder:placeholder];
                } else {
                    action = [UNNotificationAction
                        actionWithIdentifier:actId
                                       title:actTitle
                                     options:actOpts];
                }
                [actionsByCategory[catIdx] addObject:action];
            }

            // Build categories
            NSMutableSet<UNNotificationCategory *> *categories =
                [NSMutableSet setWithCapacity:catCount];

            for (jsize i = 0; i < catCount; i++) {
                jstring jCatId = (*env)->GetObjectArrayElement(env, jCatIds, i);
                NSString *catId = toNSString(env, jCatId);
                NSArray<UNNotificationAction *> *actions =
                    actionsByCategory[@(i)] ?: @[];
                UNNotificationCategoryOptions opts = (UNNotificationCategoryOptions)catOptions[i];

                UNNotificationCategory *category = [UNNotificationCategory
                    categoryWithIdentifier:catId
                                   actions:actions
                         intentIdentifiers:@[]
                                   options:opts];
                [categories addObject:category];
            }

            [[UNUserNotificationCenter currentNotificationCenter]
                setNotificationCategories:categories];

            // Release arrays
            (*env)->ReleaseIntArrayElements(env, jCatOptions, catOptions, JNI_ABORT);
            if (actionCatIndices) (*env)->ReleaseIntArrayElements(env, jActionCatIndices, actionCatIndices, JNI_ABORT);
            if (actionOptions) (*env)->ReleaseIntArrayElements(env, jActionOptions, actionOptions, JNI_ABORT);
            if (actionIsTextInput) (*env)->ReleaseBooleanArrayElements(env, jActionIsTextInput, actionIsTextInput, JNI_ABORT);
        }
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeGetNotificationCategories(
    JNIEnv *env, jclass clazz, jlong callbackId) {
    if (@available(macOS 10.14, *)) {
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        [center getNotificationCategoriesWithCompletionHandler:^(NSSet<UNNotificationCategory *> *categories) {
            @autoreleasepool {
                BOOL didAttach = NO;
                JNIEnv *cbEnv = getEnv(&didAttach);
                if (cbEnv == NULL) return;

                NSArray<UNNotificationCategory *> *catArray = [categories allObjects];
                jsize catCount = (jsize)catArray.count;

                // Count total actions
                jsize totalActions = 0;
                for (UNNotificationCategory *cat in catArray) {
                    totalActions += (jsize)cat.actions.count;
                }

                jclass stringClass = (*cbEnv)->FindClass(cbEnv, "java/lang/String");

                // Category arrays
                jobjectArray jCatIds = (*cbEnv)->NewObjectArray(cbEnv, catCount, stringClass, NULL);
                jintArray jCatOptions = (*cbEnv)->NewIntArray(cbEnv, catCount);

                // Action arrays
                jintArray jActionCatIndices = (*cbEnv)->NewIntArray(cbEnv, totalActions);
                jobjectArray jActionIds = (*cbEnv)->NewObjectArray(cbEnv, totalActions, stringClass, NULL);
                jobjectArray jActionTitles = (*cbEnv)->NewObjectArray(cbEnv, totalActions, stringClass, NULL);
                jintArray jActionOptions = (*cbEnv)->NewIntArray(cbEnv, totalActions);
                jbooleanArray jActionIsTextInput = (*cbEnv)->NewBooleanArray(cbEnv, totalActions);
                jobjectArray jActionBtnTitles = (*cbEnv)->NewObjectArray(cbEnv, totalActions, stringClass, NULL);
                jobjectArray jActionPlaceholders = (*cbEnv)->NewObjectArray(cbEnv, totalActions, stringClass, NULL);

                jsize actionIdx = 0;
                for (jsize ci = 0; ci < catCount; ci++) {
                    UNNotificationCategory *cat = catArray[ci];
                    (*cbEnv)->SetObjectArrayElement(cbEnv, jCatIds, ci, toJStringNonNull(cbEnv, cat.identifier));
                    jint catOpt = (jint)cat.options;
                    (*cbEnv)->SetIntArrayRegion(cbEnv, jCatOptions, ci, 1, &catOpt);

                    for (UNNotificationAction *action in cat.actions) {
                        jint catIndex = ci;
                        (*cbEnv)->SetIntArrayRegion(cbEnv, jActionCatIndices, actionIdx, 1, &catIndex);
                        (*cbEnv)->SetObjectArrayElement(cbEnv, jActionIds, actionIdx, toJStringNonNull(cbEnv, action.identifier));
                        (*cbEnv)->SetObjectArrayElement(cbEnv, jActionTitles, actionIdx, toJStringNonNull(cbEnv, action.title));
                        jint actOpt = (jint)action.options;
                        (*cbEnv)->SetIntArrayRegion(cbEnv, jActionOptions, actionIdx, 1, &actOpt);

                        BOOL isTextInput = [action isKindOfClass:[UNTextInputNotificationAction class]];
                        jboolean jIsTextInput = isTextInput ? JNI_TRUE : JNI_FALSE;
                        (*cbEnv)->SetBooleanArrayRegion(cbEnv, jActionIsTextInput, actionIdx, 1, &jIsTextInput);

                        if (isTextInput) {
                            UNTextInputNotificationAction *textAction = (UNTextInputNotificationAction *)action;
                            (*cbEnv)->SetObjectArrayElement(cbEnv, jActionBtnTitles, actionIdx,
                                toJStringNonNull(cbEnv, textAction.textInputButtonTitle));
                            (*cbEnv)->SetObjectArrayElement(cbEnv, jActionPlaceholders, actionIdx,
                                toJStringNonNull(cbEnv, textAction.textInputPlaceholder));
                        } else {
                            jstring empty = toJStringNonNull(cbEnv, @"");
                            (*cbEnv)->SetObjectArrayElement(cbEnv, jActionBtnTitles, actionIdx, empty);
                            (*cbEnv)->SetObjectArrayElement(cbEnv, jActionPlaceholders, actionIdx, empty);
                        }
                        actionIdx++;
                    }
                }

                jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (cls != NULL) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
                        "onNotificationCategories",
                        "(J[Ljava/lang/String;[I[I[Ljava/lang/String;[Ljava/lang/String;[I[Z[Ljava/lang/String;[Ljava/lang/String;)V");
                    if (method != NULL) {
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId,
                            jCatIds, jCatOptions,
                            jActionCatIndices, jActionIds, jActionTitles,
                            jActionOptions, jActionIsTextInput,
                            jActionBtnTitles, jActionPlaceholders);
                    }
                }
                clearException(cbEnv);
                releaseEnv(didAttach);
            }
        }];
    }
}

// ============================================================================
// JNI: Badge
// ============================================================================

// Helper: invoke onBadgeResult callback
static void fireBadgeResult(jlong callbackId, NSError * _Nullable error) {
    BOOL didAttach = NO;
    JNIEnv *cbEnv = getEnv(&didAttach);
    if (cbEnv == NULL) return;

    jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
    if (cls != NULL) {
        jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
            "onBadgeResult", "(JLjava/lang/String;)V");
        if (method != NULL) {
            jstring jError = error ? toJString(cbEnv, [error localizedDescription]) : NULL;
            (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId, jError);
        }
    }
    clearException(cbEnv);
    releaseEnv(didAttach);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeSetBadgeCount(
    JNIEnv *env, jclass clazz, jint count, jlong callbackId) {
    if (@available(macOS 13.0, *)) {
        // macOS 13+: use the proper UNUserNotificationCenter API
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        [center setBadgeCount:count withCompletionHandler:^(NSError *error) {
            @autoreleasepool {
                fireBadgeResult(callbackId, error);
            }
        }];
    } else if (@available(macOS 10.14, *)) {
        // Fallback: NSDockTile badge label
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                NSDockTile *dockTile = [NSApp dockTile];
                if (count > 0) {
                    dockTile.badgeLabel = [NSString stringWithFormat:@"%d", count];
                } else {
                    dockTile.badgeLabel = nil;
                }
                fireBadgeResult(callbackId, nil);
            }
        });
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeGetBadgeCount(
    JNIEnv *env, jclass clazz, jlong callbackId) {
    if (@available(macOS 13.0, *)) {
        // macOS 13+: no direct getter; read from NSDockTile as best effort
        // (Apple does not expose a getBadgeCount API)
    }
    if (@available(macOS 10.14, *)) {
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                NSDockTile *dockTile = [NSApp dockTile];
                NSString *label = dockTile.badgeLabel;
                int badgeVal = label ? [label intValue] : 0;

                BOOL didAttach = NO;
                JNIEnv *cbEnv = getEnv(&didAttach);
                if (cbEnv == NULL) return;

                jclass cls = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (cls != NULL) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, cls,
                        "onBadgeCount", "(JI)V");
                    if (method != NULL) {
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, cls, method, callbackId, (jint)badgeVal);
                    }
                }
                clearException(cbEnv);
                releaseEnv(didAttach);
            }
        });
    }
}

// ============================================================================
// JNI: Delegate management
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_macos_NativeMacNotificationBridge_nativeSetDelegate(
    JNIEnv *env, jclass clazz, jboolean enabled) {
    if (@available(macOS 10.14, *)) {
        g_hasKotlinDelegate = enabled;
        ensureDelegateInstalled();
    }
}
