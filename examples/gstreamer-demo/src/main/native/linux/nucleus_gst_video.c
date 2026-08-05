/**
 * GStreamer → TextureView bridge for the video sample (Linux / EGL).
 *
 * What it does, and why this shape: a hardware decoder hands out YUV, which the
 * compositor cannot sample (see `NucleusYuvFormat`), so the *pipeline* converts —
 * `glcolorconvert` on the GPU, no CPU copy — and what reaches us is an RGBA
 * texture in GStreamer's own GL context. This is the architecture Flutter settled
 * on too: its Linux embedder takes a GL texture and treats it as RGBA, leaving the
 * conversion to the plugin.
 *
 * Three EGL contexts, all in one share group so textures are common to them:
 *
 *   - the **window's**, captured when [nativeOpen] runs (which is why it must be
 *     called from inside a Compose render pass, where it is current);
 *   - one handed to GStreamer, which creates its own sharing context from it —
 *     GStreamer needs to activate what it is given, so it must not be the
 *     window's;
 *   - one for [nativePullFrame], current only on the thread that pulls, so a
 *     producer loop on a background dispatcher never touches the render thread.
 *
 * Each frame is copied into a texture of ours with `glCopyImageSubData`, and it is
 * that texture — aliased as an `EGLImage` — that the composable imports, once. A
 * copy rather than handing over GStreamer's own texture, because the pipeline
 * rotates a pool of them: one stable source means one import, and no
 * recomposition per frame.
 *
 * Sample-only: it is built by its own `build.sh` against the GStreamer headers,
 * not by CI, and the sample says so when the library is missing.
 */

#include <jni.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/gl/gl.h>
#include <gst/gl/egl/gstgldisplay_egl.h>
#include <gst/video/video.h>
#include <stdlib.h>
#include <string.h>

#define LOG(...) g_print("[nucleus_gst_video] " __VA_ARGS__)

/* Resolved through eglGetProcAddress: the sample links no GL library of its own. */
typedef void (*PFN_glGenTextures)(int, unsigned int *);
typedef void (*PFN_glDeleteTextures)(int, const unsigned int *);
typedef void (*PFN_glBindTexture)(unsigned int, unsigned int);
typedef void (*PFN_glTexParameteri)(unsigned int, unsigned int, int);
typedef void (*PFN_glTexImage2D)(unsigned int, int, int, int, int, int,
                                 unsigned int, unsigned int, const void *);
typedef void (*PFN_glCopyImageSubData)(unsigned int, unsigned int, int, int, int, int,
                                       unsigned int, unsigned int, int, int, int, int,
                                       int, int, int);
typedef void (*PFN_glFinish)(void);
typedef unsigned int (*PFN_glGetError)(void);
typedef EGLImageKHR (*PFN_eglCreateImageKHR)(EGLDisplay, EGLContext, EGLenum,
                                             EGLClientBuffer, const EGLint *);
typedef EGLBoolean (*PFN_eglDestroyImageKHR)(EGLDisplay, EGLImageKHR);

static PFN_glGenTextures       gl_gen_textures;
static PFN_glDeleteTextures    gl_delete_textures;
static PFN_glBindTexture       gl_bind_texture;
static PFN_glTexParameteri     gl_tex_parameteri;
static PFN_glTexImage2D        gl_tex_image_2d;
static PFN_glCopyImageSubData  gl_copy_image_sub_data;
static PFN_glFinish            gl_finish;
static PFN_glGetError          gl_get_error;
static PFN_eglCreateImageKHR   egl_create_image;
static PFN_eglDestroyImageKHR  egl_destroy_image;

#define GL_TEXTURE_2D        0x0DE1
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_WRAP_S    0x2802
#define GL_TEXTURE_WRAP_T    0x2803
#define GL_LINEAR            0x2601
#define GL_CLAMP_TO_EDGE     0x812F
#define GL_RGBA8             0x8058
#define GL_RGBA              0x1908
#define GL_UNSIGNED_BYTE     0x1401
#define GL_NO_ERROR          0

#define EGL_GL_TEXTURE_2D_KHR 0x30B1
#define EGL_IMAGE_PRESERVED_KHR 0x30D2

typedef struct {
    EGLDisplay   display;
    EGLContext   appContext;     /* the window's, borrowed        */
    EGLContext   gstContext;     /* what GStreamer shares from    */
    EGLContext   workerContext;  /* current while pulling a frame */
    GstElement  *pipeline;
    GstAppSink  *sink;
    GstGLDisplay *glDisplay;
    GstGLContext *glWrapped;
    GstVideoInfo info;
    unsigned int target;         /* our RGBA copy of the newest frame */
    EGLImageKHR  targetImage;    /* the same storage, as the composable sees it */
    int          widthPx;
    int          heightPx;
    /* Set from the streaming thread, acted on by the puller: a flushing seek from
     * inside the bus handler can deadlock the very thread that has to serve it. */
    volatile gboolean atEnd;
} NucleusGstVideo;

static gboolean resolve_entry_points(void) {
    if (gl_copy_image_sub_data != NULL) return TRUE;
#define RESOLVE(var, name) var = (void *) eglGetProcAddress(name); if (var == NULL) return FALSE
    RESOLVE(gl_gen_textures, "glGenTextures");
    RESOLVE(gl_delete_textures, "glDeleteTextures");
    RESOLVE(gl_bind_texture, "glBindTexture");
    RESOLVE(gl_tex_parameteri, "glTexParameteri");
    RESOLVE(gl_tex_image_2d, "glTexImage2D");
    RESOLVE(gl_finish, "glFinish");
    RESOLVE(gl_get_error, "glGetError");
    RESOLVE(egl_create_image, "eglCreateImageKHR");
    RESOLVE(egl_destroy_image, "eglDestroyImageKHR");
    /* Last, because it doubles as the "resolved" flag. */
    RESOLVE(gl_copy_image_sub_data, "glCopyImageSubData");
#undef RESOLVE
    return TRUE;
}

/**
 * Hands GStreamer the display and the context it may share from. Called on the
 * pipeline's own threads, which is why the context it gets is never the window's.
 */
static GstBusSyncReply on_bus_message(GstBus *bus, GstMessage *message, gpointer user_data) {
    NucleusGstVideo *video = user_data;
    (void) bus;
    /* Errors and warnings reach the console: a sample that fails silently is
     * useless, and the GL context handover is exactly where things go wrong. */
    if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR ||
        GST_MESSAGE_TYPE(message) == GST_MESSAGE_WARNING) {
        GError *error = NULL;
        gchar *debug = NULL;
        if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR) {
            gst_message_parse_error(message, &error, &debug);
        } else {
            gst_message_parse_warning(message, &error, &debug);
        }
        LOG("%s from %s: %s (%s)\n",
            GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR ? "ERROR" : "WARNING",
            GST_OBJECT_NAME(GST_MESSAGE_SRC(message)),
            error != NULL ? error->message : "?", debug != NULL ? debug : "");
        if (error != NULL) g_error_free(error);
        g_free(debug);
        return GST_BUS_PASS;
    }
    if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_EOS) {
        video->atEnd = TRUE;
        return GST_BUS_PASS;
    }
    if (GST_MESSAGE_TYPE(message) != GST_MESSAGE_NEED_CONTEXT) return GST_BUS_PASS;

    const gchar *type = NULL;
    if (!gst_message_parse_context_type(message, &type) || type == NULL) return GST_BUS_PASS;
    GstContext *context = NULL;
    if (g_strcmp0(type, GST_GL_DISPLAY_CONTEXT_TYPE) == 0) {
        context = gst_context_new(GST_GL_DISPLAY_CONTEXT_TYPE, TRUE);
        gst_context_set_gl_display(context, video->glDisplay);
    } else if (g_strcmp0(type, "gst.gl.app_context") == 0) {
        context = gst_context_new("gst.gl.app_context", TRUE);
        gst_structure_set(gst_context_writable_structure(context),
                          "context", GST_TYPE_GL_CONTEXT, video->glWrapped, NULL);
    }
    if (context != NULL) {
        gst_element_set_context(GST_ELEMENT(GST_MESSAGE_SRC(message)), context);
        gst_context_unref(context);
    }
    return GST_BUS_PASS;
}

/** A context sharing with the window's, without disturbing what is current. */
static EGLContext create_shared_context(NucleusGstVideo *video) {
    static const EGLint attrs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 0,
        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT,
        EGL_NONE
    };
    EGLContext context = eglCreateContext(video->display, EGL_NO_CONFIG_KHR,
                                          video->appContext, attrs);
    if (context != EGL_NO_CONTEXT) return context;
    /* No EGL_KHR_no_config_context: reuse the window's own config. */
    EGLint config_id = 0;
    if (!eglQueryContext(video->display, video->appContext, EGL_CONFIG_ID, &config_id)) {
        return EGL_NO_CONTEXT;
    }
    const EGLint config_attrs[] = { EGL_CONFIG_ID, config_id, EGL_NONE };
    EGLConfig config = NULL;
    EGLint count = 0;
    if (!eglChooseConfig(video->display, config_attrs, &config, 1, &count) || count < 1) {
        return EGL_NO_CONTEXT;
    }
    return eglCreateContext(video->display, config, video->appContext, attrs);
}

/** The texture the composable imports, plus the EGLImage that aliases it. */
static gboolean create_target(NucleusGstVideo *video) {
    gl_gen_textures(1, &video->target);
    if (video->target == 0) return FALSE;
    gl_bind_texture(GL_TEXTURE_2D, video->target);
    gl_tex_parameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    gl_tex_parameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    gl_tex_parameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    gl_tex_parameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    gl_tex_image_2d(GL_TEXTURE_2D, 0, GL_RGBA8, video->widthPx, video->heightPx, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    gl_bind_texture(GL_TEXTURE_2D, 0);
    if (gl_get_error() != GL_NO_ERROR) return FALSE;

    /* Skia samples a texture bound to an EGLImage; one it was merely handed the
     * name of, it does not. Same manoeuvre as Flutter's fl_egl_image.cc. */
    const EGLint image_attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    video->targetImage = egl_create_image(
        video->display, video->appContext, EGL_GL_TEXTURE_2D_KHR,
        (EGLClientBuffer) (uintptr_t) video->target, image_attrs);
    return video->targetImage != EGL_NO_IMAGE_KHR;
}

static void video_free(NucleusGstVideo *video) {
    if (video == NULL) return;
    if (video->pipeline != NULL) {
        gst_element_set_state(video->pipeline, GST_STATE_NULL);
        gst_object_unref(video->pipeline);
    }
    /* Our strong ref to the appsink (the pipeline held the other). Unref after the
     * pipeline so the bin it sat in is already gone, and this is the last ref. */
    if (video->sink != NULL) gst_object_unref(GST_OBJECT(video->sink));
    if (video->glWrapped != NULL) gst_object_unref(video->glWrapped);
    if (video->glDisplay != NULL) gst_object_unref(video->glDisplay);
    if (video->targetImage != EGL_NO_IMAGE_KHR && egl_destroy_image != NULL) {
        egl_destroy_image(video->display, video->targetImage);
    }
    /* The texture belongs to the share group, so any of its contexts can free it;
     * the caller destroys the import first, which is what releases Skia's copy. */
    if (video->target != 0 && gl_delete_textures != NULL &&
        eglMakeCurrent(video->display, EGL_NO_SURFACE, EGL_NO_SURFACE, video->workerContext)) {
        gl_delete_textures(1, &video->target);
        eglMakeCurrent(video->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (video->workerContext != EGL_NO_CONTEXT) eglDestroyContext(video->display, video->workerContext);
    if (video->gstContext != EGL_NO_CONTEXT) eglDestroyContext(video->display, video->gstContext);
    free(video);
}

/**
 * Opens [uri] and prerolls it. **Must be called with the window's EGL context
 * current** — from a Compose `remember {}`, which runs inside the render pass —
 * because that is the context everything here shares from. Returns 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativeOpen(
        JNIEnv *env, jclass clazz, jstring uri) {
    (void) clazz;
    if (!resolve_entry_points()) {
        LOG("missing GL/EGL entry points\n");
        return 0;
    }
    if (!gst_is_initialized()) gst_init(NULL, NULL);

    NucleusGstVideo *video = calloc(1, sizeof(NucleusGstVideo));
    if (video == NULL) return 0;
    video->targetImage = EGL_NO_IMAGE_KHR;
    video->display = eglGetCurrentDisplay();
    video->appContext = eglGetCurrentContext();
    if (video->display == EGL_NO_DISPLAY || video->appContext == EGL_NO_CONTEXT) {
        LOG("no current EGL context — open() must run inside a render pass\n");
        free(video);
        return 0;
    }
    video->gstContext = create_shared_context(video);
    video->workerContext = create_shared_context(video);
    if (video->gstContext == EGL_NO_CONTEXT || video->workerContext == EGL_NO_CONTEXT) {
        LOG("could not create contexts sharing with the window's: 0x%x\n", eglGetError());
        video_free(video);
        return 0;
    }

    video->glDisplay = GST_GL_DISPLAY(gst_gl_display_egl_new_with_egl_display(video->display));
    /* Desktop GL only: left to itself the EGL backend reaches for GLES first, and a
     * GLES context cannot share with the window's desktop-GL one — EGL answers
     * EGL_BAD_CONTEXT and the pipeline never leaves NULL. */
    gst_gl_display_filter_gl_api(video->glDisplay, GST_GL_API_OPENGL3 | GST_GL_API_OPENGL);
    video->glWrapped = gst_gl_context_new_wrapped(
        video->glDisplay, (guintptr) video->gstContext,
        GST_GL_PLATFORM_EGL, GST_GL_API_OPENGL3 | GST_GL_API_OPENGL);
    if (video->glWrapped == NULL) {
        LOG("could not wrap the shared context for GStreamer\n");
        video_free(video);
        return 0;
    }
    /* A wrapped context carries no API or version until someone looks, and
     * GStreamer needs both: without them it creates its sharing context for the
     * wrong client API and EGL refuses it (EGL_BAD_CONTEXT). Looking means making
     * it current for a moment — on this very thread, inside a render pass — so the
     * window's binding is saved and put back, exactly as the toolkit does around
     * its own bring-up. */
    {
        EGLDisplay saved_display = eglGetCurrentDisplay();
        EGLContext saved_context = eglGetCurrentContext();
        EGLSurface saved_draw = eglGetCurrentSurface(EGL_DRAW);
        EGLSurface saved_read = eglGetCurrentSurface(EGL_READ);
        GError *info_error = NULL;
        if (!gst_gl_context_activate(video->glWrapped, TRUE) ||
            !gst_gl_context_fill_info(video->glWrapped, &info_error)) {
            LOG("could not read the shared context's GL info: %s\n",
                info_error != NULL ? info_error->message : "activation refused");
            if (info_error != NULL) g_error_free(info_error);
            gst_gl_context_activate(video->glWrapped, FALSE);
            eglMakeCurrent(saved_display, saved_draw, saved_read, saved_context);
            video_free(video);
            return 0;
        }
        gst_gl_context_activate(video->glWrapped, FALSE);
        if (saved_context != EGL_NO_CONTEXT) {
            eglMakeCurrent(saved_display, saved_draw, saved_read, saved_context);
        }
    }

    const char *uri_chars = (*env)->GetStringUTFChars(env, uri, NULL);

    /* The video-sink bin: glupload → glcolorconvert → appsink, with a ghost pad
     * named "sink" on the front. playbin's playsink looks for a static pad of
     * exactly that name on the video-sink element (`gst_element_get_static_pad`),
     * and a bare bin from gst_parse_launch exposes none of its own — without the
     * ghost pad playsink refuses to link, decodebin then cannot negotiate caps
     * and reports "no decoder available", and preroll never happens. */
    GstElement *videoSink = gst_bin_new("nucleus-video-sink");
    GstElement *glupload = gst_element_factory_make("glupload", NULL);
    GstElement *glcolorconvert = gst_element_factory_make("glcolorconvert", NULL);
    GstElement *appsink = gst_element_factory_make("appsink", "sink");
    if (videoSink == NULL || glupload == NULL || glcolorconvert == NULL || appsink == NULL) {
        LOG("could not build the video-sink bin (glupload/glcolorconvert/appsink)\n");
        if (videoSink != NULL) gst_object_unref(videoSink);
        if (glupload != NULL) gst_object_unref(glupload);
        if (glcolorconvert != NULL) gst_object_unref(glcolorconvert);
        if (appsink != NULL) gst_object_unref(appsink);
        video_free(video);
        return 0;
    }
    GstCaps *sinkCaps = gst_caps_from_string(
        "video/x-raw(memory:GLMemory),format=RGBA");
    g_object_set(appsink, "max-buffers", 1, "drop", TRUE, "sync", TRUE,
                 "caps", sinkCaps, NULL);
    gst_caps_unref(sinkCaps);
    gst_bin_add_many(GST_BIN(videoSink), glupload, glcolorconvert, appsink, NULL);
    gst_element_link_many(glupload, glcolorconvert, appsink, NULL);
    GstPad *sinkPad = gst_element_get_static_pad(glupload, "sink");
    gst_element_add_pad(videoSink, gst_ghost_pad_new("sink", sinkPad));
    gst_object_unref(sinkPad);
    /* Keep our own ref to the appsink across the bin's lifetime; the bin holds the
     * other, released when playbin lets the video-sink go. video_free drops it. */
    video->sink = GST_APP_SINK(g_object_ref(appsink));

    /* `playbin` is the whole playback engine: it demuxes and decodes, wires an
     * `autoaudiosink` for the sound itself, and — the part that matters here —
     * takes its clock from the audio sink, so the appsink's `sync=true` paces the
     * video against the audio. That is the same division of labour as the WASAPI
     * and AVSampleBuffer paths in the Windows and macOS samples, except GStreamer
     * owns the audio path outright: no PCM plumbing on this side at all. `mute`
     * rides on playbin's own property. */
    video->pipeline = gst_element_factory_make("playbin", "playbin");
    if (video->pipeline == NULL) {
        LOG("could not create playbin — is the playback plugin installed?\n");
        gst_object_unref(videoSink);
        video_free(video);
        return 0;
    }
    g_object_set(video->pipeline, "uri", uri_chars, NULL);
    g_object_set(video->pipeline, "video-sink", videoSink, NULL);
    /* playbin holds the only strong ref to the bin now; ours goes. The appsink
     * ref, kept on video->sink, is released in video_free. */
    gst_object_unref(videoSink);
    (*env)->ReleaseStringUTFChars(env, uri, uri_chars);
    GstBus *bus = gst_element_get_bus(video->pipeline);
    gst_bus_set_sync_handler(bus, on_bus_message, video, NULL);
    gst_object_unref(bus);

    /* Preroll: PAUSED settles the caps, so the frame size is known before the
     * first pull — and the target texture can be sized once and for all. */
    gst_element_set_state(video->pipeline, GST_STATE_PAUSED);
    if (gst_element_get_state(video->pipeline, NULL, NULL, 10 * GST_SECOND) ==
        GST_STATE_CHANGE_FAILURE) {
        LOG("pipeline could not reach PAUSED\n");
        video_free(video);
        return 0;
    }
    GstSample *preroll = gst_app_sink_try_pull_preroll(video->sink, 5 * GST_SECOND);
    if (preroll == NULL) {
        LOG("no preroll sample — is the file decodable with these plugins?\n");
        video_free(video);
        return 0;
    }
    GstCaps *caps = gst_sample_get_caps(preroll);
    if (caps == NULL || !gst_video_info_from_caps(&video->info, caps)) {
        LOG("preroll sample carries no video caps\n");
        gst_sample_unref(preroll);
        video_free(video);
        return 0;
    }
    gst_sample_unref(preroll);
    video->widthPx = GST_VIDEO_INFO_WIDTH(&video->info);
    video->heightPx = GST_VIDEO_INFO_HEIGHT(&video->info);
    if (!create_target(video)) {
        LOG("could not create the target texture / EGLImage\n");
        video_free(video);
        return 0;
    }
    gst_element_set_state(video->pipeline, GST_STATE_PLAYING);
    LOG("playing %dx%d\n", video->widthPx, video->heightPx);
    return (jlong) (uintptr_t) video;
}

#define VIDEO_OF(ptr) ((NucleusGstVideo *) (uintptr_t) (ptr))

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativeWidth(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    return handle == 0 ? 0 : VIDEO_OF(handle)->widthPx;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativeHeight(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    return handle == 0 ? 0 : VIDEO_OF(handle)->heightPx;
}

/** The `EGLImageKHR` the composable imports — stable for the whole playback. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativeEglImage(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    return handle == 0 ? 0 : (jlong) (uintptr_t) VIDEO_OF(handle)->targetImage;
}

/**
 * Copies the newest decoded frame into the target texture, if one arrived. Any
 * thread, one at a time: it makes its own context current for the copy, so the
 * render thread is never touched.
 *
 * Returns 1 when a frame was copied, 0 when none was waiting, -1 on error.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativePullFrame(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return -1;
    NucleusGstVideo *video = VIDEO_OF(handle);
    GstSample *sample = gst_app_sink_try_pull_sample(video->sink, 0);
    if (sample == NULL) {
        /* Loop, so the sample keeps something to show. */
        if (video->atEnd) {
            video->atEnd = FALSE;
            gst_element_seek_simple(video->pipeline, GST_FORMAT_TIME,
                                    GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT, 0);
        }
        return 0;
    }

    jint result = -1;
    if (!eglMakeCurrent(video->display, EGL_NO_SURFACE, EGL_NO_SURFACE, video->workerContext)) {
        LOG("could not bind the worker context: 0x%x\n", eglGetError());
        gst_sample_unref(sample);
        return -1;
    }
    GstBuffer *buffer = gst_sample_get_buffer(sample);
    GstVideoFrame frame;
    if (buffer != NULL && gst_video_frame_map(&frame, &video->info, buffer,
                                              GST_MAP_READ | GST_MAP_GL)) {
        /* Wait for the pipeline's own GL writes before reading them. */
        GstGLSyncMeta *sync = gst_buffer_get_gl_sync_meta(buffer);
        if (sync != NULL) {
            GstGLContext *context = NULL;
            if (gst_gl_context_get_current() == NULL) context = video->glWrapped;
            gst_gl_sync_meta_wait(sync, context != NULL ? context : video->glWrapped);
        }
        const unsigned int source = *(unsigned int *) frame.data[0];
        gl_copy_image_sub_data(source, GL_TEXTURE_2D, 0, 0, 0, 0,
                               video->target, GL_TEXTURE_2D, 0, 0, 0, 0,
                               video->widthPx, video->heightPx, 1);
        /* The consumer samples this texture from another thread's context, so the
         * copy has to be finished before the frame is signalled — the contract
         * `markFrameAvailable` documents. */
        gl_finish();
        result = gl_get_error() == GL_NO_ERROR ? 1 : -1;
        gst_video_frame_unmap(&frame);
    }
    eglMakeCurrent(video->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    gst_sample_unref(sample);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle != 0) video_free(VIDEO_OF(handle));
}

/**
 * True when the file has an audio stream. `playbin` exposes `n-audio` once it has
 * discovered the streams, which the preroll in [nativeOpen] forces — so by the
 * time the Kotlin side asks, the count is settled.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativeHasAudio(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return JNI_FALSE;
    NucleusGstVideo *video = VIDEO_OF(handle);
    gint nAudio = 0;
    if (video->pipeline != NULL) {
        g_object_get(video->pipeline, "n-audio", &nAudio, NULL);
    }
    return nAudio > 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * Mutes the audio path. playbin's clock keeps running — only the render volume
 * goes to zero — so video pacing is unaffected, and a file without audio is a
 * no-op. The Kotlin side serialises this against [nativeClose].
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_samplegst_NativeGstVideoBridge_nativeSetMuted(
        JNIEnv *env, jclass clazz, jlong handle, jboolean muted) {
    (void) env; (void) clazz;
    if (handle == 0) return;
    NucleusGstVideo *video = VIDEO_OF(handle);
    if (video->pipeline != NULL) {
        g_object_set(video->pipeline, "mute", (gboolean) (muted ? TRUE : FALSE), NULL);
    }
}
