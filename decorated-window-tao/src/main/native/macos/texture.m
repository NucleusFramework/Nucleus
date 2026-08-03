// texture.m — JNI bridge: external GPU texture import for the TextureView
// composable (macOS / Metal backend). Compiled into libnucleus_tao_metal.dylib
// next to NucleusTaoMetal.m, the Metal twin of Windows' nucleus_tao_texture.c.
//
// Import path:
//   producer IOSurface (or IOSurface-backed id<MTLTexture>) →
//   [hostDevice newTextureWithDescriptor:iosurface:plane:] → an id<MTLTexture>
//   that aliases the producer's pixels on the *host* Metal device → Kotlin
//   wraps it in a Skia BackendRenderTarget + Surface and takes one
//   makeImageSnapshot() per producer frame, which Skia composites into the
//   Compose scene (see TextureViewMac.kt).
//
// Why IOSurface: it is macOS's shareable GPU buffer — the moral equivalent of
// the DXGI shared handle used on Windows. Both processes/devices map the same
// pixels, so no CPU frame copy ever happens on the way in. A raw
// id<MTLTexture> is accepted too (same process): it is sampled in place when it
// already lives on the host device with MTLTextureUsageRenderTarget, and
// otherwise re-wrapped through its own IOSurface backing.
//
// Unlike ANGLE on Windows, Skia's Metal backend gives no public skiko API to
// build a GrBackendTexture from an id<MTLTexture> (only
// BackendRenderTarget.makeMetal exists), so the consumer side wraps the import
// as a render target and pays exactly one GPU-GPU copy per frame — the same
// trade-off as the keyed-mutex staging path on Windows, and the reason no
// per-frame native call is needed here.
//
// Threading: import / destroy must run on the thread that owns the Skia
// DirectContext (the host's Metal render thread) so the texture is created on
// the same device Skia renders with. The test producer owns a private device +
// command queue and is safe from any single producer thread.

#import <Metal/Metal.h>
#import <IOSurface/IOSurface.h>
#import <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// Pixel layouts the import accepts. Skia samples the wrapped texture as
// premultiplied 8-bit RGBA; these are the two byte orders that map onto
// MTLPixelFormatBGRA8Unorm / MTLPixelFormatRGBA8Unorm (Kotlin turns the value
// into the matching SurfaceColorFormat).
#define NUCLEUS_TEX_FORMAT_BGRA8 0
#define NUCLEUS_TEX_FORMAT_RGBA8 1

// Staged failure codes returned by the import entry points (negative so the
// Kotlin side can log the failing stage; 0 means "out of memory").
#define NUCLEUS_TEX_ERR_ARGS        (-1) /* null device / surface, bad size    */
#define NUCLEUS_TEX_ERR_FORMAT      (-2) /* not a BGRA8 / RGBA8 surface        */
#define NUCLEUS_TEX_ERR_SIZE        (-3) /* size does not match the surface    */
#define NUCLEUS_TEX_ERR_TEXTURE     (-4) /* newTextureWithDescriptor failed    */
#define NUCLEUS_TEX_ERR_USAGE       (-5) /* texture not usable as render target*/
#define NUCLEUS_TEX_ERR_FOREIGN     (-6) /* foreign device, no IOSurface       */

#define NUCLEUS_TEST_BAR_PX 16

typedef struct {
    id<MTLDevice>  device;
    // Texture Skia wraps. Aliases the producer's IOSurface, or *is* the
    // producer's texture when it was already usable on the host device.
    id<MTLTexture> texture;
    // Retained only when the import came from an IOSurface (directly or
    // through a producer texture's backing) — keeps the pixels alive for the
    // lifetime of the import even if the producer drops its reference.
    IOSurfaceRef   surface;
    int            widthPx;
    int            heightPx;
    int            pixelFormat; /* NUCLEUS_TEX_FORMAT_* */
} NucleusTaoExternalTexture;

typedef struct {
    id<MTLDevice>       device;
    id<MTLCommandQueue> queue;
    // Render target the consumer imports, aliasing `surface`.
    id<MTLTexture>      texture;
    // Opaque-white scratch texture, blit-copied in rectangles to draw the
    // moving test-pattern bars (a blit needs no shaders, so the producer
    // stays a single self-contained file).
    id<MTLTexture>      white;
    IOSurfaceRef        surface;
    int                 widthPx;
    int                 heightPx;
} NucleusTaoTestProducer;

#define EXTERNAL_OF(ptr) ((NucleusTaoExternalTexture *)(uintptr_t)(ptr))
#define PRODUCER_OF(ptr) ((NucleusTaoTestProducer *)(uintptr_t)(ptr))

/* ================================================================== */
/*  Import                                                             */
/* ================================================================== */

static int nucleusFormatOfIOSurface(IOSurfaceRef surface) {
    switch (IOSurfaceGetPixelFormat(surface)) {
        case 'BGRA': return NUCLEUS_TEX_FORMAT_BGRA8;
        case 'RGBA': return NUCLEUS_TEX_FORMAT_RGBA8;
        default:     return -1;
    }
}

static int nucleusFormatOfMetalPixelFormat(MTLPixelFormat format) {
    switch (format) {
        case MTLPixelFormatBGRA8Unorm:
        case MTLPixelFormatBGRA8Unorm_sRGB:
            return NUCLEUS_TEX_FORMAT_BGRA8;
        case MTLPixelFormatRGBA8Unorm:
        case MTLPixelFormatRGBA8Unorm_sRGB:
            return NUCLEUS_TEX_FORMAT_RGBA8;
        default:
            return -1;
    }
}

static MTLPixelFormat nucleusMetalPixelFormat(int format) {
    return format == NUCLEUS_TEX_FORMAT_RGBA8 ? MTLPixelFormatRGBA8Unorm
                                              : MTLPixelFormatBGRA8Unorm;
}

// Creates a texture on `device` aliasing `plane` of `surface`.
//
// Shared storage only, deliberately: a Managed texture keeps a device-private
// copy that has to be reconciled with the IOSurface explicitly, which nothing
// on the consumer path does — the import would succeed and then sample stale
// pixels forever. Failing here instead degrades TextureView to an empty Box,
// which is at least honest. Shared is available on all Apple silicon and on
// Intel integrated GPUs; a discrete-only Intel Mac is the case that fails.
static id<MTLTexture> nucleusTextureFromIOSurface(
        id<MTLDevice> device, IOSurfaceRef surface, NSUInteger plane,
        int widthPx, int heightPx, MTLPixelFormat pixelFormat) {
    MTLTextureDescriptor *desc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:pixelFormat
                                                          width:(NSUInteger)widthPx
                                                         height:(NSUInteger)heightPx
                                                      mipmapped:NO];
    // Render target: Skia can only wrap an id<MTLTexture> as a
    // BackendRenderTarget, and it samples the frame out of it via
    // makeImageSnapshot (shader read).
    desc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    desc.storageMode = MTLStorageModeShared;
    return [device newTextureWithDescriptor:desc iosurface:surface plane:plane];
}

static jlong nucleusWrapImport(
        id<MTLDevice> device, id<MTLTexture> texture, IOSurfaceRef surfaceOrNull,
        int widthPx, int heightPx, int pixelFormat) {
    NucleusTaoExternalTexture *t = (NucleusTaoExternalTexture *)
        calloc(1, sizeof(NucleusTaoExternalTexture));
    if (t == NULL) return 0;
    t->device      = device;
    t->texture     = texture;
    t->surface     = surfaceOrNull != NULL ? (IOSurfaceRef)CFRetain(surfaceOrNull) : NULL;
    t->widthPx     = widthPx;
    t->heightPx    = heightPx;
    t->pixelFormat = pixelFormat;
    return (jlong)(uintptr_t)t;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeImportIOSurface(
        JNIEnv *env, jclass clazz, jlong devicePtr, jlong ioSurfacePtr,
        jint widthPx, jint heightPx) {
    (void)env; (void)clazz;
    if (devicePtr == 0 || ioSurfacePtr == 0 || widthPx < 1 || heightPx < 1) {
        return NUCLEUS_TEX_ERR_ARGS;
    }
    id<MTLDevice> device = (__bridge id<MTLDevice>)(void *)(uintptr_t)devicePtr;
    IOSurfaceRef surface = (IOSurfaceRef)(uintptr_t)ioSurfacePtr;
    if (device == nil) return NUCLEUS_TEX_ERR_ARGS;

    int format = nucleusFormatOfIOSurface(surface);
    if (format < 0) return NUCLEUS_TEX_ERR_FORMAT;
    /* Metal validates the descriptor against the plane's real dimensions, so
     * the requested size must match exactly — a "large enough" surface (e.g. a
     * decoder's 1920x1088 allocation for 1080p) would fail deeper, in
     * newTextureWithDescriptor, with a far less obvious code. */
    if ((size_t)widthPx  != IOSurfaceGetWidth(surface) ||
        (size_t)heightPx != IOSurfaceGetHeight(surface)) {
        return NUCLEUS_TEX_ERR_SIZE;
    }

    id<MTLTexture> texture = nucleusTextureFromIOSurface(
        device, surface, 0, widthPx, heightPx, nucleusMetalPixelFormat(format));
    if (texture == nil) return NUCLEUS_TEX_ERR_TEXTURE;
    return nucleusWrapImport(device, texture, surface, widthPx, heightPx, format);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeImportMetalTexture(
        JNIEnv *env, jclass clazz, jlong devicePtr, jlong texturePtr,
        jint widthPx, jint heightPx) {
    (void)env; (void)clazz;
    if (devicePtr == 0 || texturePtr == 0 || widthPx < 1 || heightPx < 1) {
        return NUCLEUS_TEX_ERR_ARGS;
    }
    id<MTLDevice>  device = (__bridge id<MTLDevice>)(void *)(uintptr_t)devicePtr;
    id<MTLTexture> source = (__bridge id<MTLTexture>)(void *)(uintptr_t)texturePtr;
    if (device == nil || source == nil) return NUCLEUS_TEX_ERR_ARGS;

    int format = nucleusFormatOfMetalPixelFormat(source.pixelFormat);
    if (format < 0) return NUCLEUS_TEX_ERR_FORMAT;
    if ((NSUInteger)widthPx > source.width || (NSUInteger)heightPx > source.height) {
        return NUCLEUS_TEX_ERR_SIZE;
    }

    BOOL sameDevice = (source.device == device);
    if (sameDevice && (source.usage & MTLTextureUsageRenderTarget) != 0) {
        // Already usable as-is: Skia wraps the producer's own texture, so the
        // producer's writes land straight in the imported render target.
        return nucleusWrapImport(device, source, NULL, widthPx, heightPx, format);
    }
    // Foreign device, or a texture Skia cannot wrap (no render-target usage —
    // e.g. one vended by CVMetalTextureCache). Re-wrap its IOSurface backing on
    // the host device instead; that is the only way to reach the same pixels.
    IOSurfaceRef backing = source.iosurface;
    if (backing == NULL) {
        return sameDevice ? NUCLEUS_TEX_ERR_USAGE : NUCLEUS_TEX_ERR_FOREIGN;
    }
    id<MTLTexture> texture = nucleusTextureFromIOSurface(
        device, backing, source.iosurfacePlane, widthPx, heightPx,
        nucleusMetalPixelFormat(format));
    if (texture == nil) return NUCLEUS_TEX_ERR_TEXTURE;
    return nucleusWrapImport(device, texture, backing, widthPx, heightPx, format);
}

/* id<MTLTexture> Skia wraps via BackendRenderTarget.makeMetal. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTexturePtr(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (handle <= 0) return 0;
    return (jlong)(uintptr_t)(__bridge void *)EXTERNAL_OF(handle)->texture;
}

/* NUCLEUS_TEX_FORMAT_* of the import — picks the Skia SurfaceColorFormat. */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativePixelFormat(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (handle <= 0) return NUCLEUS_TEX_FORMAT_BGRA8;
    return (jint)EXTERNAL_OF(handle)->pixelFormat;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (handle <= 0) return;
    NucleusTaoExternalTexture *t = EXTERNAL_OF(handle);
    // Nil the strong fields before free() — ARC releases on assignment, and a
    // plain free() would leak the texture / device references.
    t->texture = nil;
    t->device  = nil;
    if (t->surface != NULL) {
        CFRelease(t->surface);
        t->surface = NULL;
    }
    free(t);
}

/* ================================================================== */
/*  Metal test producer (demos / smoke tests)                          */
/* ================================================================== */

// Clears the producer texture to `argb` (premultiplied, as Skia samples it)
// and returns the command buffer so the caller can append more work.
static id<MTLCommandBuffer> nucleusProducerClear(NucleusTaoTestProducer *p, jint argb) {
    double a = (double)((argb >> 24) & 0xFF) / 255.0;
    MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture     = p->texture;
    pass.colorAttachments[0].loadAction  = MTLLoadActionClear;
    pass.colorAttachments[0].storeAction = MTLStoreActionStore;
    pass.colorAttachments[0].clearColor  = MTLClearColorMake(
        a * (double)((argb >> 16) & 0xFF) / 255.0,
        a * (double)((argb >>  8) & 0xFF) / 255.0,
        a * (double)( argb        & 0xFF) / 255.0,
        a);
    id<MTLCommandBuffer> cb = [p->queue commandBuffer];
    id<MTLRenderCommandEncoder> encoder = [cb renderCommandEncoderWithDescriptor:pass];
    [encoder endEncoding];
    return cb;
}

// Commits and waits: the producer contract is that a frame is fully written
// before markFrameAvailable() is called, which is what makes the consumer's
// per-frame copy tear-free.
static void nucleusProducerSubmit(id<MTLCommandBuffer> cb) {
    [cb commit];
    [cb waitUntilCompleted];
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTestProducerCreate(
        JNIEnv *env, jclass clazz, jint widthPx, jint heightPx) {
    (void)env; (void)clazz;
    if (widthPx < 1 || heightPx < 1) return 0;

    // Own device + queue, distinct from the host's: the IOSurface is the only
    // object shared with the compositor, exactly like a real producer.
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (device == nil) return 0;
    id<MTLCommandQueue> queue = [device newCommandQueue];
    if (queue == nil) return 0;

    NSDictionary *props = @{
        (__bridge NSString *)kIOSurfaceWidth:            @(widthPx),
        (__bridge NSString *)kIOSurfaceHeight:           @(heightPx),
        (__bridge NSString *)kIOSurfaceBytesPerElement:  @(4),
        (__bridge NSString *)kIOSurfacePixelFormat:      @((int)'BGRA'),
    };
    IOSurfaceRef surface = IOSurfaceCreate((__bridge CFDictionaryRef)props);
    if (surface == NULL) return 0;

    id<MTLTexture> texture = nucleusTextureFromIOSurface(
        device, surface, 0, widthPx, heightPx, MTLPixelFormatBGRA8Unorm);
    if (texture == nil) {
        CFRelease(surface);
        return 0;
    }

    // Opaque-white source for the pattern bars. Shared storage so the pixels
    // can be uploaded once from the CPU; only blits read it afterwards.
    MTLTextureDescriptor *whiteDesc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                          width:(NSUInteger)widthPx
                                                         height:(NSUInteger)heightPx
                                                      mipmapped:NO];
    whiteDesc.usage       = MTLTextureUsageShaderRead;
    whiteDesc.storageMode = MTLStorageModeShared;
    id<MTLTexture> white = [device newTextureWithDescriptor:whiteDesc];
    if (white == nil) {
        CFRelease(surface);
        return 0;
    }
    size_t rowBytes = (size_t)widthPx * 4;
    unsigned char *pixels = (unsigned char *)malloc(rowBytes * (size_t)heightPx);
    if (pixels == NULL) {
        CFRelease(surface);
        return 0;
    }
    memset(pixels, 0xFF, rowBytes * (size_t)heightPx);
    [white replaceRegion:MTLRegionMake2D(0, 0, (NSUInteger)widthPx, (NSUInteger)heightPx)
             mipmapLevel:0
               withBytes:pixels
             bytesPerRow:rowBytes];
    free(pixels);

    NucleusTaoTestProducer *p = (NucleusTaoTestProducer *)
        calloc(1, sizeof(NucleusTaoTestProducer));
    if (p == NULL) {
        CFRelease(surface);
        return 0;
    }
    p->device   = device;
    p->queue    = queue;
    p->texture  = texture;
    p->white    = white;
    p->surface  = surface; /* ownership transferred from IOSurfaceCreate */
    p->widthPx  = (int)widthPx;
    p->heightPx = (int)heightPx;
    return (jlong)(uintptr_t)p;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTestProducerIoSurface(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void)env; (void)clazz;
    if (producer == 0) return 0;
    return (jlong)(uintptr_t)PRODUCER_OF(producer)->surface;
}

/* id<MTLDevice> / id<MTLCommandQueue> of the producer — used by the headless
 * smoke test to build a Skia Metal DirectContext without a window. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTestProducerDevicePtr(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void)env; (void)clazz;
    if (producer == 0) return 0;
    return (jlong)(uintptr_t)(__bridge void *)PRODUCER_OF(producer)->device;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTestProducerQueuePtr(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void)env; (void)clazz;
    if (producer == 0) return 0;
    return (jlong)(uintptr_t)(__bridge void *)PRODUCER_OF(producer)->queue;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTestProducerFill(
        JNIEnv *env, jclass clazz, jlong producer, jint argb) {
    (void)env; (void)clazz;
    if (producer == 0) return;
    NucleusTaoTestProducer *p = PRODUCER_OF(producer);
    nucleusProducerSubmit(nucleusProducerClear(p, argb));
}

/* Animated test pattern: `argbBg` background plus a white vertical bar (x
 * follows `tick`) and a white horizontal bar (y follows `tick`) — enough
 * structure for the contentScale / filterQuality demos and to make tearing
 * observable. Drawn with blit copies out of the white scratch texture, so no
 * render pipeline (and no shader library) is needed. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTestProducerDrawPattern(
        JNIEnv *env, jclass clazz, jlong producer, jint tick, jint argbBg) {
    (void)env; (void)clazz;
    if (producer == 0) return;
    NucleusTaoTestProducer *p = PRODUCER_OF(producer);
    int barW = p->widthPx  < NUCLEUS_TEST_BAR_PX ? p->widthPx  : NUCLEUS_TEST_BAR_PX;
    int barH = p->heightPx < NUCLEUS_TEST_BAR_PX ? p->heightPx : NUCLEUS_TEST_BAR_PX;
    int barX = (tick * 2) % (p->widthPx  - barW + 1);
    int barY =  tick      % (p->heightPx - barH + 1);
    if (barX < 0) barX = 0;
    if (barY < 0) barY = 0;

    // Encoders run in creation order within one command buffer, so the clear
    // always lands before the bars.
    id<MTLCommandBuffer> cb = nucleusProducerClear(p, argbBg);
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:p->white
              sourceSlice:0
              sourceLevel:0
             sourceOrigin:MTLOriginMake(0, 0, 0)
               sourceSize:MTLSizeMake((NSUInteger)barW, (NSUInteger)p->heightPx, 1)
                toTexture:p->texture
         destinationSlice:0
         destinationLevel:0
        destinationOrigin:MTLOriginMake((NSUInteger)barX, 0, 0)];
    [blit copyFromTexture:p->white
              sourceSlice:0
              sourceLevel:0
             sourceOrigin:MTLOriginMake(0, 0, 0)
               sourceSize:MTLSizeMake((NSUInteger)p->widthPx, (NSUInteger)barH, 1)
                toTexture:p->texture
         destinationSlice:0
         destinationLevel:0
        destinationOrigin:MTLOriginMake(0, (NSUInteger)barY, 0)];
    [blit endEncoding];
    nucleusProducerSubmit(cb);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsTextureBridge_nativeTestProducerDestroy(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void)env; (void)clazz;
    if (producer == 0) return;
    NucleusTaoTestProducer *p = PRODUCER_OF(producer);
    p->white   = nil;
    p->texture = nil;
    p->queue   = nil;
    p->device  = nil;
    if (p->surface != NULL) {
        CFRelease(p->surface);
        p->surface = NULL;
    }
    free(p);
}
