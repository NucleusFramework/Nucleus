#import <Foundation/Foundation.h>
#include <jni.h>

// SMJobCopyDictionary is deprecated but has no replacement for dynamic user agents.
// SMAppService (macOS 13+) only supports plists bundled inside the app bundle,
// not dynamically-created plists in ~/Library/LaunchAgents/.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
#import <ServiceManagement/ServiceManagement.h>
#pragma clang diagnostic pop

// ============================================================================
// JNI class prefix
// ============================================================================

#define JNI_CLASS(name) \
    Java_dev_nucleusframework_nucleus_scheduler_internal_MacOSLaunchdSchedulerJni_##name

// ============================================================================
// JNI helpers
// ============================================================================

static jstring toJString(JNIEnv *env, NSString *str) {
    if (str == nil) return NULL;
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

static NSArray<NSString *> *toNSArray(JNIEnv *env, jobjectArray jarray) {
    if (jarray == NULL) return nil;
    jsize len = (*env)->GetArrayLength(env, jarray);
    NSMutableArray *arr = [NSMutableArray arrayWithCapacity:(NSUInteger)len];
    for (jsize i = 0; i < len; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, jarray, i);
        NSString *str = toNSString(env, jstr);
        if (str) [arr addObject:str];
        if (jstr) (*env)->DeleteLocalRef(env, jstr);
    }
    return arr;
}

// ============================================================================
// Plist constants
// ============================================================================

static const int CAL_NOT_SET = -1;

// ============================================================================
// nativeWritePlist
// ============================================================================

JNIEXPORT jstring JNICALL
JNI_CLASS(nativeWritePlist)(JNIEnv *env, jclass clazz,
                            jstring jPlistPath,
                            jstring jLabel,
                            jobjectArray jProgramArgs,
                            jint intervalSeconds,
                            jint calendarDay,
                            jint calendarHour,
                            jint calendarMinute,
                            jboolean runAtLoad,
                            jintArray jCalendarDays) {
    (void)clazz;
    @autoreleasepool {
        NSString *plistPath = toNSString(env, jPlistPath);
        NSString *label = toNSString(env, jLabel);
        NSArray *programArgs = toNSArray(env, jProgramArgs);

        if (!plistPath || !label || !programArgs) {
            return toJString(env, @"Invalid null argument");
        }

        NSMutableDictionary *plist = [NSMutableDictionary dictionary];
        plist[@"Label"] = label;
        plist[@"ProgramArguments"] = programArgs;

        // Schedule configuration
        if (intervalSeconds > 0) {
            // Periodic schedule
            plist[@"StartInterval"] = @(intervalSeconds);
            if (runAtLoad) {
                plist[@"RunAtLoad"] = @YES;
            }
        } else if (jCalendarDays != NULL) {
            // Day-range calendar schedule (e.g., Mon..Fri)
            jsize daysLen = (*env)->GetArrayLength(env, jCalendarDays);
            jint *days = (*env)->GetIntArrayElements(env, jCalendarDays, NULL);
            if (days && daysLen > 0) {
                NSMutableArray *intervals = [NSMutableArray arrayWithCapacity:(NSUInteger)daysLen];
                for (jsize i = 0; i < daysLen; i++) {
                    NSMutableDictionary *entry = [NSMutableDictionary dictionary];
                    entry[@"Weekday"] = @(days[i]);
                    if (calendarHour != CAL_NOT_SET) entry[@"Hour"] = @(calendarHour);
                    if (calendarMinute != CAL_NOT_SET) entry[@"Minute"] = @(calendarMinute);
                    [intervals addObject:entry];
                }
                plist[@"StartCalendarInterval"] = intervals;
            }
            if (days) (*env)->ReleaseIntArrayElements(env, jCalendarDays, days, JNI_ABORT);
        } else if (calendarDay != CAL_NOT_SET || calendarHour != CAL_NOT_SET || calendarMinute != CAL_NOT_SET) {
            // Single calendar interval
            NSMutableDictionary *cal = [NSMutableDictionary dictionary];
            if (calendarDay != CAL_NOT_SET) cal[@"Weekday"] = @(calendarDay);
            if (calendarHour != CAL_NOT_SET) cal[@"Hour"] = @(calendarHour);
            if (calendarMinute != CAL_NOT_SET) cal[@"Minute"] = @(calendarMinute);
            plist[@"StartCalendarInterval"] = cal;
        } else if (runAtLoad) {
            // On-boot only
            plist[@"RunAtLoad"] = @YES;
        }

        // Common settings
        plist[@"KeepAlive"] = @NO;
        plist[@"ProcessType"] = @"Background";

        // Serialize to binary plist
        NSError *error = nil;
        NSData *data = [NSPropertyListSerialization dataWithPropertyList:plist
                                                                 format:NSPropertyListXMLFormat_v1_0
                                                                options:0
                                                                  error:&error];
        if (!data) {
            NSString *msg = [NSString stringWithFormat:@"Plist serialization failed: %@",
                             error.localizedDescription ?: @"unknown"];
            return toJString(env, msg);
        }

        // Ensure parent directory exists
        NSString *parentDir = [plistPath stringByDeletingLastPathComponent];
        [[NSFileManager defaultManager] createDirectoryAtPath:parentDir
                                  withIntermediateDirectories:YES
                                                  attributes:nil
                                                       error:nil];

        // Write atomically
        if (![data writeToFile:plistPath options:NSDataWritingAtomic error:&error]) {
            NSString *msg = [NSString stringWithFormat:@"Failed to write plist: %@",
                             error.localizedDescription ?: @"unknown"];
            return toJString(env, msg);
        }

        return NULL; // success
    }
}

// ============================================================================
// nativeLaunchctlLoad / nativeLaunchctlUnload
// ============================================================================

static jstring runLaunchctl(JNIEnv *env, NSString *verb, NSString *plistPath) {
    @autoreleasepool {
        if (!plistPath) return toJString(env, @"plistPath must not be null");

        NSMutableArray *args = [NSMutableArray array];
        [args addObject:verb];
        if ([verb isEqualToString:@"load"]) {
            [args addObject:@"-w"];
        }
        [args addObject:plistPath];

        NSTask *task = [[NSTask alloc] init];
        task.executableURL = [NSURL fileURLWithPath:@"/bin/launchctl"];
        task.arguments = args;

        NSPipe *errPipe = [NSPipe pipe];
        task.standardError = errPipe;
        task.standardOutput = [NSPipe pipe]; // drain stdout

        NSError *error = nil;
        if (![task launchAndReturnError:&error]) {
            NSString *msg = [NSString stringWithFormat:@"launchctl %@ launch failed: %@",
                             verb, error.localizedDescription ?: @"unknown"];
            return toJString(env, msg);
        }

        [task waitUntilExit];

        if (task.terminationStatus != 0) {
            NSData *errData = [errPipe.fileHandleForReading readDataToEndOfFile];
            NSString *errStr = [[NSString alloc] initWithData:errData encoding:NSUTF8StringEncoding];
            NSString *msg = [NSString stringWithFormat:@"launchctl %@ failed (exit %d): %@",
                             verb, task.terminationStatus,
                             errStr.length > 0 ? errStr : @"no output"];
            return toJString(env, msg);
        }

        return NULL; // success
    }
}

JNIEXPORT jstring JNICALL
JNI_CLASS(nativeLaunchctlLoad)(JNIEnv *env, jclass clazz, jstring jPlistPath) {
    (void)clazz;
    return runLaunchctl(env, @"load", toNSString(env, jPlistPath));
}

JNIEXPORT jstring JNICALL
JNI_CLASS(nativeLaunchctlUnload)(JNIEnv *env, jclass clazz, jstring jPlistPath) {
    (void)clazz;
    return runLaunchctl(env, @"unload", toNSString(env, jPlistPath));
}

// ============================================================================
// nativeIsJobLoaded (SMJobCopyDictionary — no subprocess)
// ============================================================================

JNIEXPORT jboolean JNICALL
JNI_CLASS(nativeIsJobLoaded)(JNIEnv *env, jclass clazz, jstring jJobLabel) {
    (void)clazz;
    @autoreleasepool {
        NSString *label = toNSString(env, jJobLabel);
        if (!label) return JNI_FALSE;

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        CFDictionaryRef dict = SMJobCopyDictionary(kSMDomainUserLaunchd,
                                                   (__bridge CFStringRef)label);
#pragma clang diagnostic pop

        if (dict != NULL) {
            CFRelease(dict);
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }
}

// ============================================================================
// nativeDeleteFile
// ============================================================================

JNIEXPORT jboolean JNICALL
JNI_CLASS(nativeDeleteFile)(JNIEnv *env, jclass clazz, jstring jPath) {
    (void)clazz;
    @autoreleasepool {
        NSString *path = toNSString(env, jPath);
        if (!path) return JNI_FALSE;
        return [[NSFileManager defaultManager] removeItemAtPath:path error:nil] ? JNI_TRUE : JNI_FALSE;
    }
}

// ============================================================================
// nativeComputeNextFireTime
// ============================================================================

static jlong computeNextForComponents(NSCalendar *cal, NSDate *now,
                                       int weekday, int hour, int minute) {
    NSDateComponents *comps = [[NSDateComponents alloc] init];
    // launchd weekday: 0=Sunday..6=Saturday
    // NSCalendar weekday: 1=Sunday..7=Saturday
    if (weekday >= 0) comps.weekday = weekday + 1;
    if (hour >= 0) comps.hour = hour;
    if (minute >= 0) comps.minute = minute;

    NSDate *next = [cal nextDateAfterDate:now
                      matchingComponents:comps
                                 options:NSCalendarMatchNextTime];
    if (next) {
        return (jlong)([next timeIntervalSince1970] * 1000.0);
    }
    return 0;
}

JNIEXPORT jlong JNICALL
JNI_CLASS(nativeComputeNextFireTime)(JNIEnv *env, jclass clazz,
                                     jint intervalSeconds,
                                     jint calendarDay,
                                     jint calendarHour,
                                     jint calendarMinute,
                                     jintArray jCalendarDays) {
    (void)clazz;
    @autoreleasepool {
        NSDate *now = [NSDate date];

        // Periodic: next = now + interval
        if (intervalSeconds > 0) {
            return (jlong)(([now timeIntervalSince1970] + intervalSeconds) * 1000.0);
        }

        NSCalendar *cal = [NSCalendar currentCalendar];

        // Multi-day range: compute for each day, return earliest
        if (jCalendarDays != NULL) {
            jsize daysLen = (*env)->GetArrayLength(env, jCalendarDays);
            jint *days = (*env)->GetIntArrayElements(env, jCalendarDays, NULL);
            if (days && daysLen > 0) {
                jlong earliest = 0;
                for (jsize i = 0; i < daysLen; i++) {
                    jlong candidate = computeNextForComponents(cal, now,
                                                                days[i],
                                                                calendarHour,
                                                                calendarMinute);
                    if (candidate > 0 && (earliest == 0 || candidate < earliest)) {
                        earliest = candidate;
                    }
                }
                (*env)->ReleaseIntArrayElements(env, jCalendarDays, days, JNI_ABORT);
                return earliest;
            }
            if (days) (*env)->ReleaseIntArrayElements(env, jCalendarDays, days, JNI_ABORT);
            return 0;
        }

        // Single calendar interval
        if (calendarDay != CAL_NOT_SET || calendarHour != CAL_NOT_SET || calendarMinute != CAL_NOT_SET) {
            return computeNextForComponents(cal, now, calendarDay, calendarHour, calendarMinute);
        }

        return 0; // on-boot only, no predictable next fire time
    }
}

// ============================================================================
// nativeScheduleRetry
// ============================================================================

JNIEXPORT jstring JNICALL
JNI_CLASS(nativeScheduleRetry)(JNIEnv *env, jclass clazz,
                                jstring jPlistPath,
                                jstring jLabel,
                                jobjectArray jProgramArgs,
                                jlong delaySeconds) {
    (void)clazz;
    @autoreleasepool {
        NSString *plistPath = toNSString(env, jPlistPath);
        NSString *label = toNSString(env, jLabel);
        NSArray *programArgs = toNSArray(env, jProgramArgs);

        if (!plistPath || !label || !programArgs) {
            return toJString(env, @"Invalid null argument");
        }

        // Build a RunAtLoad-only retry plist
        NSDictionary *plist = @{
            @"Label": label,
            @"ProgramArguments": programArgs,
            @"RunAtLoad": @YES,
            @"KeepAlive": @NO,
            @"ProcessType": @"Background",
        };

        NSError *error = nil;
        NSData *data = [NSPropertyListSerialization dataWithPropertyList:plist
                                                                 format:NSPropertyListXMLFormat_v1_0
                                                                options:0
                                                                  error:&error];
        if (!data) {
            NSString *msg = [NSString stringWithFormat:@"Retry plist serialization failed: %@",
                             error.localizedDescription ?: @"unknown"];
            return toJString(env, msg);
        }

        // Ensure parent directory exists
        NSString *parentDir = [plistPath stringByDeletingLastPathComponent];
        [[NSFileManager defaultManager] createDirectoryAtPath:parentDir
                                  withIntermediateDirectories:YES
                                                  attributes:nil
                                                       error:nil];

        // Write atomically
        if (![data writeToFile:plistPath options:NSDataWritingAtomic error:&error]) {
            NSString *msg = [NSString stringWithFormat:@"Failed to write retry plist: %@",
                             error.localizedDescription ?: @"unknown"];
            return toJString(env, msg);
        }

        // Delayed load via dispatch_after — the plist persists on disk
        // so even if the app crashes, reboot/relogin will pick it up (RunAtLoad).
        NSString *pathCopy = [plistPath copy];
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delaySeconds * NSEC_PER_SEC)),
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0),
            ^{
                @autoreleasepool {
                    NSTask *task = [[NSTask alloc] init];
                    task.executableURL = [NSURL fileURLWithPath:@"/bin/launchctl"];
                    task.arguments = @[@"load", pathCopy];
                    task.standardOutput = [NSPipe pipe];
                    task.standardError = [NSPipe pipe];
                    [task launchAndReturnError:nil];
                    [task waitUntilExit];
                }
            }
        );

        return NULL; // success
    }
}
