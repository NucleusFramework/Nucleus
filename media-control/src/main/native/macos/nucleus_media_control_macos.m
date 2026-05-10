/**
 * JNI bridge for OS media controls on macOS via:
 *   MPNowPlayingInfoCenter    — metadata & playback state in Control Center
 *   MPRemoteCommandCenter     — remote commands (play/pause/next/prev/…)
 *
 * Apple references:
 *   https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter
 *   https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter
 *
 * Notes:
 *   - All MPRemoteCommandCenter setup/teardown is dispatched to the main queue.
 *   - Artwork is loaded asynchronously on a global queue, then merged into
 *     nowPlayingInfo only if a fresher metadata update hasn't arrived in the
 *     meantime (guarded by a monotonically increasing counter).
 *   - Emits the same JSON payload shape as the Linux bridge so the Kotlin
 *     parser can be shared.
 */

#import <Cocoa/Cocoa.h>
#import <MediaPlayer/MediaPlayer.h>
#include <jni.h>
#include <stdatomic.h>

// ============================================================================
// Constants
// ============================================================================

#define STATUS_STOPPED 0
#define STATUS_PAUSED  1
#define STATUS_PLAYING 2

#define BRIDGE_CLASS "dev/nucleusframework/nucleus/media/control/macos/NativeMacOsBridge"

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_on_event_method = NULL;

// Retained targets so we can detach later via removeTarget:
static id g_target_play      = nil;
static id g_target_pause     = nil;
static id g_target_toggle    = nil;
static id g_target_stop      = nil;
static id g_target_next      = nil;
static id g_target_previous  = nil;
static id g_target_position  = nil;

// Monotonic counter to protect against stale artwork overwriting fresh metadata.
static _Atomic uint64_t g_metadata_counter = 0;

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
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

static int ensureCallbackIds(JNIEnv *env) {
    if (g_bridge_class != NULL) return 1;
    jclass cls = (*env)->FindClass(env, BRIDGE_CLASS);
    if (!cls) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    g_bridge_class = (jclass)(*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);
    g_on_event_method = (*env)->GetStaticMethodID(env, g_bridge_class,
        "onMediaControlEvent", "(Ljava/lang/String;)V");
    if (!g_on_event_method) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
        return 0;
    }
    return 1;
}

static void dispatchJson(NSString *json) {
    BOOL didAttach = NO;
    JNIEnv *env = getEnv(&didAttach);
    if (!env || !ensureCallbackIds(env)) {
        releaseEnv(didAttach);
        return;
    }
    const char *utf = [json UTF8String];
    jstring js = (*env)->NewStringUTF(env, utf ? utf : "{}");
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method, js);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, js);
    releaseEnv(didAttach);
}

static void dispatchSimple(NSString *type) {
    dispatchJson([NSString stringWithFormat:@"{\"type\":\"%@\"}", type]);
}

static void dispatchPosition(int64_t positionUs) {
    dispatchJson([NSString stringWithFormat:@"{\"type\":\"set_position\",\"positionUs\":%lld}",
                  (long long)positionUs]);
}

// ============================================================================
// MPNowPlayingInfoCenter helpers
// ============================================================================

static void setNowPlayingInfoOnMain(NSDictionary *info) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
    });
}

static void mergeNowPlayingInfoOnMain(NSDictionary *extra) {
    dispatch_async(dispatch_get_main_queue(), ^{
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        NSMutableDictionary *merged = [NSMutableDictionary dictionary];
        if (center.nowPlayingInfo) [merged addEntriesFromDictionary:center.nowPlayingInfo];
        [merged addEntriesFromDictionary:extra];
        center.nowPlayingInfo = merged;
    });
}

static void loadArtworkAsync(NSString *url, uint64_t counter) {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @autoreleasepool {
            NSURL *nsurl = [NSURL URLWithString:url];
            if (!nsurl) return;
            NSImage *image = [[NSImage alloc] initWithContentsOfURL:nsurl];
            if (!image) return;
            CGSize size = image.size;
            if (size.width <= 0 || size.height <= 0) return;

            MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc]
                initWithBoundsSize:size
                    requestHandler:^NSImage * _Nonnull(CGSize requestedSize) {
                        (void)requestedSize;
                        return image;
                    }];

            // Discard if a fresher metadata set has already been applied.
            if (atomic_load(&g_metadata_counter) != counter) return;

            mergeNowPlayingInfoOnMain(@{ MPMediaItemPropertyArtwork: artwork });
        }
    });
}

// ============================================================================
// JNI — metadata / playback state
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_macos_NativeMacOsBridge_nativeConfigure(
    JNIEnv *env, jclass clazz, jstring jBusName, jstring jDisplayName)
{
    (void)env; (void)clazz; (void)jBusName; (void)jDisplayName;
    // No-op on macOS — identity is derived from the host app bundle.
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_macos_NativeMacOsBridge_nativeSetMetadata(
    JNIEnv *env, jclass clazz,
    jstring jTitle, jstring jArtist, jstring jAlbum, jstring jCover, jlong durationMs)
{
    (void)clazz;
    @autoreleasepool {
        NSMutableDictionary *info = [NSMutableDictionary dictionary];

        if (jTitle) {
            const char *utf = (*env)->GetStringUTFChars(env, jTitle, NULL);
            if (utf) {
                info[MPMediaItemPropertyTitle] = [NSString stringWithUTF8String:utf];
                (*env)->ReleaseStringUTFChars(env, jTitle, utf);
            }
        }
        if (jArtist) {
            const char *utf = (*env)->GetStringUTFChars(env, jArtist, NULL);
            if (utf) {
                info[MPMediaItemPropertyArtist] = [NSString stringWithUTF8String:utf];
                (*env)->ReleaseStringUTFChars(env, jArtist, utf);
            }
        }
        if (jAlbum) {
            const char *utf = (*env)->GetStringUTFChars(env, jAlbum, NULL);
            if (utf) {
                info[MPMediaItemPropertyAlbumTitle] = [NSString stringWithUTF8String:utf];
                (*env)->ReleaseStringUTFChars(env, jAlbum, utf);
            }
        }
        if (durationMs > 0) {
            info[MPMediaItemPropertyPlaybackDuration] = @((double)durationMs / 1000.0);
        }

        NSString *coverStr = nil;
        if (jCover) {
            const char *utf = (*env)->GetStringUTFChars(env, jCover, NULL);
            if (utf) {
                coverStr = [NSString stringWithUTF8String:utf];
                (*env)->ReleaseStringUTFChars(env, jCover, utf);
            }
        }

        uint64_t counter = atomic_fetch_add(&g_metadata_counter, 1) + 1;

        // Replace the full dict so stale artwork from the previous track is cleared.
        setNowPlayingInfoOnMain([info copy]);

        if (coverStr.length > 0) {
            loadArtworkAsync(coverStr, counter);
        }
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_macos_NativeMacOsBridge_nativeSetPlaybackState(
    JNIEnv *env, jclass clazz, jint status, jlong positionMs)
{
    (void)env; (void)clazz;
    MPNowPlayingPlaybackState state;
    switch (status) {
        case STATUS_PLAYING: state = MPNowPlayingPlaybackStatePlaying; break;
        case STATUS_PAUSED:  state = MPNowPlayingPlaybackStatePaused;  break;
        default:             state = MPNowPlayingPlaybackStateStopped; break;
    }

    long long position = (long long)positionMs;
    dispatch_async(dispatch_get_main_queue(), ^{
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        center.playbackState = state;
        if (position >= 0) {
            NSMutableDictionary *merged = [NSMutableDictionary dictionary];
            if (center.nowPlayingInfo) [merged addEntriesFromDictionary:center.nowPlayingInfo];
            merged[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @((double)position / 1000.0);
            center.nowPlayingInfo = merged;
        }
    });
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_macos_NativeMacOsBridge_nativeSetVolume(
    JNIEnv *env, jclass clazz, jdouble volume)
{
    (void)env; (void)clazz; (void)volume;
    // No-op — macOS Now Playing has no per-app volume channel; system volume is separate.
}

// ============================================================================
// JNI — command handlers (attach / detach)
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_media_control_macos_NativeMacOsBridge_nativeStartListening(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    if (!ensureCallbackIds(env)) return JNI_FALSE;

    dispatch_async(dispatch_get_main_queue(), ^{
        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];

        if (!g_target_play) {
            center.playCommand.enabled = YES;
            g_target_play = [center.playCommand addTargetWithHandler:
                ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent * _Nonnull event) {
                    (void)event;
                    dispatchSimple(@"play");
                    return MPRemoteCommandHandlerStatusSuccess;
                }];
        }
        if (!g_target_pause) {
            center.pauseCommand.enabled = YES;
            g_target_pause = [center.pauseCommand addTargetWithHandler:
                ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent * _Nonnull event) {
                    (void)event;
                    dispatchSimple(@"pause");
                    return MPRemoteCommandHandlerStatusSuccess;
                }];
        }
        if (!g_target_toggle) {
            center.togglePlayPauseCommand.enabled = YES;
            g_target_toggle = [center.togglePlayPauseCommand addTargetWithHandler:
                ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent * _Nonnull event) {
                    (void)event;
                    dispatchSimple(@"toggle");
                    return MPRemoteCommandHandlerStatusSuccess;
                }];
        }
        if (!g_target_stop) {
            center.stopCommand.enabled = YES;
            g_target_stop = [center.stopCommand addTargetWithHandler:
                ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent * _Nonnull event) {
                    (void)event;
                    dispatchSimple(@"stop");
                    return MPRemoteCommandHandlerStatusSuccess;
                }];
        }
        if (!g_target_next) {
            center.nextTrackCommand.enabled = YES;
            g_target_next = [center.nextTrackCommand addTargetWithHandler:
                ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent * _Nonnull event) {
                    (void)event;
                    dispatchSimple(@"next");
                    return MPRemoteCommandHandlerStatusSuccess;
                }];
        }
        if (!g_target_previous) {
            center.previousTrackCommand.enabled = YES;
            g_target_previous = [center.previousTrackCommand addTargetWithHandler:
                ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent * _Nonnull event) {
                    (void)event;
                    dispatchSimple(@"previous");
                    return MPRemoteCommandHandlerStatusSuccess;
                }];
        }
        if (!g_target_position) {
            center.changePlaybackPositionCommand.enabled = YES;
            g_target_position = [center.changePlaybackPositionCommand addTargetWithHandler:
                ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent * _Nonnull event) {
                    MPChangePlaybackPositionCommandEvent *pe =
                        (MPChangePlaybackPositionCommandEvent *)event;
                    int64_t positionUs = (int64_t)(pe.positionTime * 1000000.0);
                    dispatchPosition(positionUs);
                    return MPRemoteCommandHandlerStatusSuccess;
                }];
        }
    });
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_macos_NativeMacOsBridge_nativeStopListening(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];

        if (g_target_play)     { [center.playCommand              removeTarget:g_target_play];     g_target_play     = nil; }
        if (g_target_pause)    { [center.pauseCommand             removeTarget:g_target_pause];    g_target_pause    = nil; }
        if (g_target_toggle)   { [center.togglePlayPauseCommand   removeTarget:g_target_toggle];   g_target_toggle   = nil; }
        if (g_target_stop)     { [center.stopCommand              removeTarget:g_target_stop];     g_target_stop     = nil; }
        if (g_target_next)     { [center.nextTrackCommand         removeTarget:g_target_next];     g_target_next     = nil; }
        if (g_target_previous) { [center.previousTrackCommand     removeTarget:g_target_previous]; g_target_previous = nil; }
        if (g_target_position) { [center.changePlaybackPositionCommand removeTarget:g_target_position]; g_target_position = nil; }

        center.playCommand.enabled                    = NO;
        center.pauseCommand.enabled                   = NO;
        center.togglePlayPauseCommand.enabled         = NO;
        center.stopCommand.enabled                    = NO;
        center.nextTrackCommand.enabled               = NO;
        center.previousTrackCommand.enabled           = NO;
        center.changePlaybackPositionCommand.enabled  = NO;

        MPNowPlayingInfoCenter *info = [MPNowPlayingInfoCenter defaultCenter];
        info.nowPlayingInfo = nil;
        info.playbackState = MPNowPlayingPlaybackStateStopped;
    });
}
