// nucleus_avf_video.m — sample helper for the AVFoundation → TextureView demo:
// decodes a video file with Apple's own decoder and publishes each frame in the
// IOSurface the TextureView import path expects.
//
// Path, all of it on the GPU:
//   AVAssetReader (VideoToolbox hardware decode) → NV12 CVPixelBuffer, backed by
//   an IOSurface → CVMetalTextureCache maps its two planes as MTLTextures → one
//   Metal render pass converts Y'CbCr to BGRA into our own IOSurface-backed
//   texture → the compositor maps that IOSurface on the window's Metal device
//   and Skia samples it.
//
// Why a render pass rather than asking the reader for BGRA: kCVPixelFormatType_32BGRA
// output makes AVFoundation insert a VTPixelTransferSession, which is free to do
// the conversion on the CPU — and the point of the sample is that no frame ever
// touches it. The decoder's native output is bi-planar NV12, Skia cannot sample
// a two-plane buffer, so the conversion is one full-screen quad on our own
// queue: the macOS counterpart of the D3D11 video processor on Windows and of
// glcolorconvert on Linux, and what AVFoundation-based players (and Chromium's
// macOS video path) do too.
//
// Output is a plain 'BGRA' IOSurface — macOS's shareable GPU buffer, the moral
// equivalent of the DXGI shared handle — created once from the first decoded
// frame, so the consumer imports it once for the whole playback.
//
// Synchronization: the consumer copies the frame out on the window's own Skia
// queue (see TextureViewMac.kt), and its contract is that the producer has
// finished writing before it signals. The command buffer is therefore committed
// *and* waited on before a pull returns — the equivalent of the keyed-mutex
// bracket on Windows.
//
// Pacing: an asset reader has no clock, it decodes as fast as it is asked to. A
// sample is read ahead and held until its presentation time is due against
// mach_absolute_time, which is what makes playback run at the file's own frame
// rate instead of the display's.
//
// Looping: AVAssetReader cannot seek, so end of file means building a fresh one
// — the counterpart of the source reader's SetCurrentPosition on Windows.
//
// Rotation: phone recordings are stored landscape with a 90° preferred
// transform. It is applied to the texture coordinates (and to the output size),
// because ignoring it is what plays them sideways.
//
// Audio: a second AVAssetReader feeds the file's audio track, as compressed
// CMSampleBuffers, into an AVSampleBufferAudioRenderer — Apple's own decode +
// output path — driven by an AVSampleBufferRenderSynchronizer. No PCM plumbing:
// the renderer decodes, converts and routes to the output device itself, the
// way AVFoundation-based players do. The synchronizer's clock is started at the
// same instant the first video frame is shown, so audio follows the file's own
// timeline in lockstep with the picture; both run at real time, so the two
// clocks stay close on their own. The audio reader loops the same way the video
// one does (a reader cannot be rewound), flushing the renderer first so the
// timeline restarts cleanly.

#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#import <dispatch/dispatch.h>
#import <jni.h>

#include <mach/mach_time.h>
#include <math.h>
#include <simd/simd.h>
#include <stdio.h>
#include <stdlib.h>

#define NUCLEUS_LOG(...) do { fprintf(stderr, "avfoundation-demo: " __VA_ARGS__); fputc('\n', stderr); } while (0)

// How many empty reads are tolerated before a pull gives up. A reader hands out
// gaps and, at end of file, a whole restart — a handful of iterations, never 64.
#define NUCLEUS_READ_ATTEMPTS 64

// Y'CbCr → RGB in one full-screen quad. Compiled at runtime: the Metal
// compiler is part of the framework, so the sample needs no metallib build step.
static NSString *const kShaderSource =
    @"#include <metal_stdlib>\n"
    @"using namespace metal;\n"
    @"\n"
    @"struct Vertex { float4 position [[position]]; float2 uv; };\n"
    @"\n"
    @"vertex Vertex nucleusVideoVertex(uint vid [[vertex_id]], constant float2 *uv [[buffer(0)]]) {\n"
    @"    const float2 corners[4] = { float2(-1, -1), float2(1, -1), float2(-1, 1), float2(1, 1) };\n"
    @"    return Vertex { float4(corners[vid], 0, 1), uv[vid] };\n"
    @"}\n"
    @"\n"
    @"fragment float4 nucleusVideoFragment(Vertex in [[stage_in]],\n"
    @"                                     texture2d<float> luma [[texture(0)]],\n"
    @"                                     texture2d<float> chroma [[texture(1)]],\n"
    @"                                     constant float4x4 &conversion [[buffer(0)]]) {\n"
    @"    constexpr sampler bilinear(filter::linear, address::clamp_to_edge);\n"
    @"    const float y = luma.sample(bilinear, in.uv).r;\n"
    @"    const float2 cbcr = chroma.sample(bilinear, in.uv).rg;\n"
    @"    const float3 rgb = (conversion * float4(y, cbcr, 1)).rgb;\n"
    @"    return float4(saturate(rgb), 1);\n"
    @"}\n";

typedef struct {
    // Our own device and queue, distinct from the window's: the IOSurface is the
    // only object shared with the compositor, exactly like a real producer.
    id<MTLDevice>              device;
    id<MTLCommandQueue>        queue;
    id<MTLRenderPipelineState> pipeline;

    // The frame the consumer imports — stable for the whole playback, which is
    // what keeps TextureView at one import and no recomposition per frame.
    id<MTLTexture>             output;
    IOSurfaceRef               surface;
    CVMetalTextureCacheRef     textureCache;

    AVURLAsset               *asset;
    AVAssetTrack             *track;
    AVAssetReader            *reader;
    AVAssetReaderTrackOutput *readerOutput;

    // The sample read ahead, waiting for its presentation time.
    CMSampleBufferRef pending;
    double            pendingSeconds;

    double   baseSeconds;    /* presentation time of the first frame played, <0 = not started */
    uint64_t startTicks;     /* mach_absolute_time when that frame was shown */
    double   secondsPerTick;

    int sourceWidthPx;  /* what the decoder hands out */
    int sourceHeightPx;
    int widthPx;        /* what the consumer imports — the rotated size */
    int heightPx;

    // Source uv of the four output corners, in the vertex shader's order.
    simd_float2 uv[4];

    // Audio: a renderer fed from a second AVAssetReader, clocked by a
    // synchronizer. nil throughout when the asset has no audio track.
    AVSampleBufferAudioRenderer     *audioRenderer;
    AVSampleBufferRenderSynchronizer *audioSync;
    AVAssetReader                   *audioReader;
    AVAssetReaderTrackOutput         *audioReaderOutput;
    dispatch_queue_t                  audioQueue;
    float                             audioVolume; /* last set, applied at setup too */
    BOOL                              audioMuted;
    BOOL                             audioEnabled;  /* setup succeeded */
    BOOL                             audioStarted;  /* synchronizer clock is running */
} NucleusAvfVideo;

#define VIDEO_OF(ptr) ((NucleusAvfVideo *)(uintptr_t)(ptr))

static const char *nucleusReason(NSError *error) {
    return error != nil ? error.localizedDescription.UTF8String : "no reason given";
}

static double nucleusNowSeconds(NucleusAvfVideo *v) {
    return (double)mach_absolute_time() * v->secondsPerTick;
}

/* ================================================================== */
/*  Colour                                                             */
/* ================================================================== */

// Y'CbCr → RGB from a standard's luma coefficients. Full range takes Y' and the
// chroma as they are; limited (studio swing) rescales 16..235 / 16..240 to
// 0..1 first — the two things Media Foundation's video processor calls
// YCbCr_Matrix and Nominal_Range.
static simd_float4x4 nucleusConversion(float kr, float kb, BOOL fullRange) {
    const float kg      = 1.0f - kr - kb;
    const float yScale  = fullRange ? 1.0f : 255.0f / 219.0f;
    const float cScale  = fullRange ? 1.0f : 255.0f / 224.0f;
    const float yOffset = fullRange ? 0.0f : 16.0f / 255.0f;

    const float rCr =  2.0f * (1.0f - kr) * cScale;
    const float bCb =  2.0f * (1.0f - kb) * cScale;
    const float gCb = -2.0f * kb * (1.0f - kb) / kg * cScale;
    const float gCr = -2.0f * kr * (1.0f - kr) / kg * cScale;

    // Rows multiply (Y, Cb, Cr, 1); the last column folds the offsets in — the
    // chroma ones being the 0.5 that turns unsigned samples into ±0.5.
    return simd_matrix_from_rows(
        simd_make_float4(yScale, 0.0f, rCr,  -yScale * yOffset - 0.5f * rCr),
        simd_make_float4(yScale, gCb,  gCr,  -yScale * yOffset - 0.5f * (gCb + gCr)),
        simd_make_float4(yScale, bCb,  0.0f, -yScale * yOffset - 0.5f * bCb),
        simd_make_float4(0.0f,   0.0f, 0.0f, 1.0f));
}

// What the buffer itself says about its colour, which is where the truth lives:
// the decoder copies the bitstream's VUI onto every frame it hands out.
static simd_float4x4 nucleusConversionFor(CVPixelBufferRef buffer, int heightPx) {
    const BOOL fullRange =
        CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;
    CFStringRef matrix = (CFStringRef)CVBufferGetAttachment(buffer, kCVImageBufferYCbCrMatrixKey, NULL);
    if (matrix != NULL) {
        if (CFEqual(matrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2)) {
            return nucleusConversion(0.2126f, 0.0722f, fullRange);
        }
        if (CFEqual(matrix, kCVImageBufferYCbCrMatrix_ITU_R_601_4)) {
            return nucleusConversion(0.299f, 0.114f, fullRange);
        }
        if (CFEqual(matrix, kCVImageBufferYCbCrMatrix_ITU_R_2020)) {
            return nucleusConversion(0.2627f, 0.0593f, fullRange);
        }
    }
    // BT.601 below the SD/HD boundary, BT.709 above — the same guess every
    // player makes when the frame says nothing.
    return heightPx >= 720 ? nucleusConversion(0.2126f, 0.0722f, fullRange)
                           : nucleusConversion(0.299f, 0.114f, fullRange);
}

/* ================================================================== */
/*  Geometry                                                           */
/* ================================================================== */

// Degrees the frame must be turned clockwise to be seen upright.
static int nucleusRotation(AVAssetTrack *track) {
    const CGAffineTransform t = track.preferredTransform;
    const double degrees = atan2(t.b, t.a) * 180.0 / M_PI;
    int rotation = (int)lround(degrees / 90.0) * 90 % 360;
    if (rotation < 0) rotation += 360;
    return rotation;
}

// Source uv for the four output corners the vertex shader emits, in its order:
// bottom-left, bottom-right, top-left, top-right, with (0, 0) the top-left of
// both spaces.
static void nucleusFillUv(NucleusAvfVideo *v, int rotation) {
    static const float outX[4] = { 0.0f, 1.0f, 0.0f, 1.0f };
    static const float outY[4] = { 1.0f, 1.0f, 0.0f, 0.0f };
    for (int i = 0; i < 4; ++i) {
        const float x = outX[i];
        const float y = outY[i];
        switch (rotation) {
            case 90:  v->uv[i] = simd_make_float2(y, 1.0f - x);        break;
            case 180: v->uv[i] = simd_make_float2(1.0f - x, 1.0f - y); break;
            case 270: v->uv[i] = simd_make_float2(1.0f - y, x);        break;
            default:  v->uv[i] = simd_make_float2(x, y);               break;
        }
    }
}

/* ================================================================== */
/*  Metal                                                              */
/* ================================================================== */

static BOOL nucleusCreateDevice(NucleusAvfVideo *v) {
    v->device = MTLCreateSystemDefaultDevice();
    if (v->device == nil) {
        NUCLEUS_LOG("no Metal device on this machine");
        return NO;
    }
    v->queue = [v->device newCommandQueue];
    if (v->queue == nil) return NO;

    NSError *error = nil;
    id<MTLLibrary> library = [v->device newLibraryWithSource:kShaderSource options:nil error:&error];
    if (library == nil) {
        NUCLEUS_LOG("the conversion shader did not compile: %s", nucleusReason(error));
        return NO;
    }
    MTLRenderPipelineDescriptor *desc = [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction   = [library newFunctionWithName:@"nucleusVideoVertex"];
    desc.fragmentFunction = [library newFunctionWithName:@"nucleusVideoFragment"];
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    v->pipeline = [v->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (v->pipeline == nil) {
        NUCLEUS_LOG("the conversion pipeline was refused: %s", nucleusReason(error));
        return NO;
    }

    // The cache is what maps a decoder's IOSurface-backed planes as textures on
    // our device without copying them; it also pools those mappings, which is
    // why it is created once and flushed per frame rather than rebuilt.
    if (CVMetalTextureCacheCreate(kCFAllocatorDefault, NULL, v->device, NULL,
                                  &v->textureCache) != kCVReturnSuccess) {
        NUCLEUS_LOG("CVMetalTextureCacheCreate failed");
        return NO;
    }
    return YES;
}

static BOOL nucleusCreateOutput(NucleusAvfVideo *v) {
    NSDictionary *props = @{
        (__bridge NSString *)kIOSurfaceWidth:           @(v->widthPx),
        (__bridge NSString *)kIOSurfaceHeight:          @(v->heightPx),
        (__bridge NSString *)kIOSurfaceBytesPerElement: @(4),
        // 'BGRA', which the consumer maps as MTLPixelFormatBGRA8Unorm.
        (__bridge NSString *)kIOSurfacePixelFormat:     @((int)'BGRA'),
    };
    v->surface = IOSurfaceCreate((__bridge CFDictionaryRef)props);
    if (v->surface == NULL) {
        NUCLEUS_LOG("IOSurfaceCreate failed for %dx%d", v->widthPx, v->heightPx);
        return NO;
    }

    MTLTextureDescriptor *desc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                          width:(NSUInteger)v->widthPx
                                                         height:(NSUInteger)v->heightPx
                                                      mipmapped:NO];
    desc.usage       = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    // Shared, so the pixels the consumer maps are the pixels we wrote: a Managed
    // texture keeps a device-private copy nothing here would synchronize.
    desc.storageMode = MTLStorageModeShared;
    v->output = [v->device newTextureWithDescriptor:desc iosurface:v->surface plane:0];
    if (v->output == nil) {
        NUCLEUS_LOG("could not wrap the output IOSurface as a Metal render target");
        return NO;
    }
    return YES;
}

/* ================================================================== */
/*  Reading                                                            */
/* ================================================================== */

// 8-bit bi-planar NV12 is the decoder's own output, so this asks for no
// conversion on the common path; a 10-bit source is brought down to it by the
// reader, which is the only place a pixel transfer can still appear.
static NSDictionary *nucleusOutputSettings(void) {
    return @{
        (__bridge NSString *)kCVPixelBufferPixelFormatTypeKey:
            @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
        (__bridge NSString *)kCVPixelBufferMetalCompatibilityKey: @YES,
        (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{},
    };
}

static BOOL nucleusStartReader(NucleusAvfVideo *v) {
    NSError *error = nil;
    AVAssetReader *reader = [[AVAssetReader alloc] initWithAsset:v->asset error:&error];
    if (reader == nil) {
        NUCLEUS_LOG("AVAssetReader could not open the asset: %s", nucleusReason(error));
        return NO;
    }
    AVAssetReaderTrackOutput *output =
        [[AVAssetReaderTrackOutput alloc] initWithTrack:v->track
                                        outputSettings:nucleusOutputSettings()];
    // The render pass reads the buffer and is done with it before the next
    // sample is asked for, so AVFoundation need not hand out a copy of it.
    output.alwaysCopiesSampleData = NO;
    if (![reader canAddOutput:output]) {
        NUCLEUS_LOG("the track output was refused — unsupported codec or pixel format");
        return NO;
    }
    [reader addOutput:output];
    if (![reader startReading]) {
        NUCLEUS_LOG("startReading failed: %s", nucleusReason(reader.error));
        return NO;
    }
    v->reader       = reader;
    v->readerOutput = output;
    v->baseSeconds  = -1.0; /* a fresh reader restarts the clock */
    return YES;
}

// Fills `pending` with the next decodable sample, looping at end of stream.
// NO means the reader gave up for good.
static BOOL nucleusReadAhead(NucleusAvfVideo *v) {
    if (v->pending != NULL) return YES;
    for (int attempt = 0; attempt < NUCLEUS_READ_ATTEMPTS; ++attempt) {
        CMSampleBufferRef sample = [v->readerOutput copyNextSampleBuffer];
        if (sample != NULL) {
            if (CMSampleBufferGetImageBuffer(sample) == NULL) {
                CFRelease(sample); /* a marker sample carrying no frame — ask again */
                continue;
            }
            const CMTime pts = CMSampleBufferGetPresentationTimeStamp(sample);
            v->pending        = sample;
            v->pendingSeconds = CMTIME_IS_NUMERIC(pts) ? CMTimeGetSeconds(pts) : 0.0;
            return YES;
        }
        const AVAssetReaderStatus status = v->reader.status;
        if (status != AVAssetReaderStatusCompleted) {
            NUCLEUS_LOG("the reader stopped (status %ld): %s",
                        (long)status, nucleusReason(v->reader.error));
            return NO;
        }
        // End of file. A reader cannot be rewound, so looping means a new one.
        v->readerOutput = nil;
        v->reader       = nil;
        if (!nucleusStartReader(v)) return NO;
    }
    NUCLEUS_LOG("the reader produced no frame in %d attempts", NUCLEUS_READ_ATTEMPTS);
    return NO;
}

/* ================================================================== */
/*  Conversion                                                         */
/* ================================================================== */

static void nucleusEncode(NucleusAvfVideo *v,
                          CVPixelBufferRef buffer,
                          id<MTLTexture> luma,
                          id<MTLTexture> chroma) {
    MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture     = v->output;
    // The quad covers every pixel, so the previous frame need not be loaded.
    pass.colorAttachments[0].loadAction  = MTLLoadActionDontCare;
    pass.colorAttachments[0].storeAction = MTLStoreActionStore;

    simd_float4x4 conversion = nucleusConversionFor(buffer, v->sourceHeightPx);
    id<MTLCommandBuffer> commands = [v->queue commandBuffer];
    id<MTLRenderCommandEncoder> encoder = [commands renderCommandEncoderWithDescriptor:pass];
    [encoder setRenderPipelineState:v->pipeline];
    [encoder setVertexBytes:v->uv length:sizeof(v->uv) atIndex:0];
    [encoder setFragmentTexture:luma atIndex:0];
    [encoder setFragmentTexture:chroma atIndex:1];
    [encoder setFragmentBytes:&conversion length:sizeof(conversion) atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
    [encoder endEncoding];
    [commands commit];
    // The consumer copies the frame out on its own queue as soon as it is told
    // about it, and nothing fences the two devices against each other: the
    // write has to be complete before this returns.
    [commands waitUntilCompleted];
}

// Converts `pending` into the shared surface. Consumes the sample either way.
static int nucleusConvertPending(NucleusAvfVideo *v) {
    CVPixelBufferRef buffer = (CVPixelBufferRef)CMSampleBufferGetImageBuffer(v->pending);
    const int width  = (int)CVPixelBufferGetWidth(buffer);
    const int height = (int)CVPixelBufferGetHeight(buffer);
    int result = -1;

    if (width != v->sourceWidthPx || height != v->sourceHeightPx) {
        // The consumer has already imported a surface of the old size.
        NUCLEUS_LOG("frame size changed mid-playback (%dx%d → %dx%d) — stopping",
                    v->sourceWidthPx, v->sourceHeightPx, width, height);
    } else {
        CVMetalTextureRef luma = NULL;
        CVMetalTextureRef chroma = NULL;
        const CVReturn lumaStatus = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, v->textureCache, buffer, NULL, MTLPixelFormatR8Unorm,
            CVPixelBufferGetWidthOfPlane(buffer, 0), CVPixelBufferGetHeightOfPlane(buffer, 0),
            0, &luma);
        const CVReturn chromaStatus = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, v->textureCache, buffer, NULL, MTLPixelFormatRG8Unorm,
            CVPixelBufferGetWidthOfPlane(buffer, 1), CVPixelBufferGetHeightOfPlane(buffer, 1),
            1, &chroma);
        if (lumaStatus == kCVReturnSuccess && chromaStatus == kCVReturnSuccess) {
            nucleusEncode(v, buffer, CVMetalTextureGetTexture(luma), CVMetalTextureGetTexture(chroma));
            result = 1;
        } else {
            NUCLEUS_LOG("the decoder's planes could not be mapped as Metal textures (%d/%d)",
                        (int)lumaStatus, (int)chromaStatus);
        }
        if (luma != NULL) CFRelease(luma);
        if (chroma != NULL) CFRelease(chroma);
        // Returns the mappings released above to the cache's pool.
        CVMetalTextureCacheFlush(v->textureCache, 0);
    }

    CFRelease(v->pending);
    v->pending = NULL;
    return result;
}

/* ================================================================== */
/*  Audio                                                              */
/* ================================================================== */

// The asset's first audio track — same synchronous-access note as the video one.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
static AVAssetTrack *nucleusFirstAudioTrack(AVURLAsset *asset) {
    return [asset tracksWithMediaType:AVMediaTypeAudio].firstObject;
}
#pragma clang diagnostic pop

// Builds (or rebuilds, at loop time) the audio reader. nil output settings let
// the samples through in their stored, compressed form — the renderer decodes,
// so nothing here has to know the format ahead of time.
static BOOL nucleusAudioBuildReader(NucleusAvfVideo *v) {
    NSError *error = nil;
    AVAssetReader *reader = [[AVAssetReader alloc] initWithAsset:v->asset error:&error];
    if (reader == nil) {
        NUCLEUS_LOG("audio AVAssetReader could not open the asset: %s", nucleusReason(error));
        return NO;
    }
    AVAssetTrack *audioTrack = nucleusFirstAudioTrack(v->asset);
    AVAssetReaderTrackOutput *output =
        [[AVAssetReaderTrackOutput alloc] initWithTrack:audioTrack outputSettings:nil];
    output.alwaysCopiesSampleData = NO;
    // Output must be added *before* reading starts — the video reader does the
    // same; addOutput after startReading throws "cannot add an output after
    // reading has started".
    if (![reader canAddOutput:output]) {
        NUCLEUS_LOG("audio track output was refused — unsupported codec");
        return NO;
    }
    [reader addOutput:output];
    if (![reader startReading]) {
        NUCLEUS_LOG("audio reader would not start: %s", nucleusReason(reader.error));
        return NO;
    }
    v->audioReader       = reader;
    v->audioReaderOutput = output;
    return YES;
}

// Enqueues audio samples until the renderer is full, skipping marker-only ones.
// Called on `audioQueue` whenever the renderer is ready for more. End of file
// flushes the renderer and rebuilds it, so audio loops like the video does.
static void nucleusAudioFeed(NucleusAvfVideo *v) {
    while (v->audioRenderer.isReadyForMoreMediaData) {
        if (!v->audioEnabled ||
            v->audioRenderer.status == AVQueuedSampleBufferRenderingStatusFailed) return;
        CMSampleBufferRef sample = [v->audioReaderOutput copyNextSampleBuffer];
        if (sample != NULL) {
            // Audio samples carry no image buffer and a non-zero sample count;
            // marker-only buffers (no samples) are a reader bookkeeping tick.
            if (CMSampleBufferGetNumSamples(sample) > 0) {
                [v->audioRenderer enqueueSampleBuffer:sample];
            }
            CFRelease(sample);
            continue;
        }
        if (v->audioReader.status == AVAssetReaderStatusCompleted) {
            // A reader cannot be rewound: flush the renderer so the timeline
            // restarts from the new reader's first sample, then rebuild.
            [v->audioRenderer flush];
            v->audioReaderOutput = nil;
            v->audioReader       = nil;
            nucleusAudioBuildReader(v);
        }
        return;
    }
}

// True once enough is queued that the synchronizer can start without a hiccup.
// `hasSufficientMediaDataForReliablePlaybackStart` is macOS 11.3+, so on an older
// OS the start falls back to the timed priming path below.
static BOOL nucleusAudioHasSufficientData(NucleusAvfVideo *v) {
    if (@available(macOS 11.3, *)) {
        return v->audioRenderer.hasSufficientMediaDataForReliablePlaybackStart;
    }
    return NO;
}

/**
 * Sets the audio chain up against the asset's audio track. Returns NO — and
 * leaves the chain nil — when there is no audio track, so the sample plays the
 * picture only. Any thread; called once from `nativeOpen`.
 */
static BOOL nucleusAudioSetup(NucleusAvfVideo *v) {
    if (nucleusFirstAudioTrack(v->asset) == nil) return NO;
    if (!nucleusAudioBuildReader(v)) return NO;
    v->audioRenderer = [[AVSampleBufferAudioRenderer alloc] init];
    v->audioSync     = [[AVSampleBufferRenderSynchronizer alloc] init];
    [v->audioSync addRenderer:v->audioRenderer];
    v->audioRenderer.volume = v->audioVolume;
    v->audioRenderer.muted  = v->audioMuted;
    v->audioQueue = dispatch_queue_create("dev.nucleus.avf.audio", DISPATCH_QUEUE_SERIAL);
    v->audioEnabled = YES;
    return YES;
}

/**
 * Starts the synchronizer's clock. Called once, the instant the first video
 * frame is shown, so audio begins against the same wall-clock moment the
 * picture does. Any thread; the priming enqueue is synchronous on `audioQueue`,
 * then continuous feeding is handed off to it.
 */
static void nucleusAudioStart(NucleusAvfVideo *v) {
    if (!v->audioEnabled || v->audioStarted) return;
    v->audioStarted = YES;

    // Prime synchronously so `setRate` has data behind it: a renderer started
    // with nothing queued underflows for the first instant of playback.
    dispatch_sync(v->audioQueue, ^{ nucleusAudioFeed(v); });
    // Let the renderer pull more as it drains, on its own serial queue so the
    // feed and any volume / mute changes stay ordered against each other.
    [v->audioRenderer requestMediaDataWhenReadyOnQueue:v->audioQueue usingBlock:^{
        nucleusAudioFeed(v);
    }];
    // Wait for the priming to reach a steady amount if the OS supports it, then
    // start the clock at zero — the first audio sample's presentation time.
    if (nucleusAudioHasSufficientData(v)) {
        [v->audioSync setRate:1 time:kCMTimeZero];
    } else {
        // Fall back to a short priming window: a few queued buffers is enough
        // for a sample, and avoids blocking the video pull on slow audio setup.
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.05 * NSEC_PER_SEC)),
                       v->audioQueue, ^{
            [v->audioSync setRate:1 time:kCMTimeZero];
        });
    }
}

static void nucleusAudioDestroy(NucleusAvfVideo *v) {
    if (!v->audioEnabled) return;
    v->audioEnabled = NO;
    v->audioStarted = NO;
    // Stop the feed before touching the queue: no new block invocations after.
    [v->audioRenderer stopRequestingMediaData];
    v->audioSync.rate = 0;
    // Drain whatever the queue already has so a feed block cannot run after the
    // objects it reads are gone — and so a setRate racing the teardown cannot.
    if (v->audioQueue != nil) dispatch_sync(v->audioQueue, ^{});
    v->audioReaderOutput = nil;
    v->audioReader       = nil;
    v->audioRenderer     = nil;
    v->audioSync         = nil;
    v->audioQueue        = nil;
}

/* ================================================================== */
/*  Lifecycle                                                          */
/* ================================================================== */

static void nucleusDestroy(NucleusAvfVideo *v) {
    // Audio first: its reader keeps decoding ahead on its own queue, and that
    // queue must not outlive the objects it reads.
    nucleusAudioDestroy(v);

    if (v->pending != NULL) {
        CFRelease(v->pending);
        v->pending = NULL;
    }
    // Cancelled explicitly: a reader keeps decoding ahead on its own queue, and
    // that queue must not outlive the Metal objects below.
    [v->reader cancelReading];
    v->readerOutput = nil;
    v->reader       = nil;
    v->track        = nil;
    v->asset        = nil;

    if (v->textureCache != NULL) {
        CVMetalTextureCacheFlush(v->textureCache, 0);
        CFRelease(v->textureCache);
        v->textureCache = NULL;
    }
    // Nil the strong fields before free() — ARC releases on assignment, and a
    // plain free() would leak the device, the queue and the textures.
    v->output   = nil;
    v->pipeline = nil;
    v->queue    = nil;
    v->device   = nil;
    if (v->surface != NULL) {
        CFRelease(v->surface);
        v->surface = NULL;
    }
    free(v);
}

// The URL AVFoundation should open. A plain path is the friendlier thing to
// type, and the one the picker hands over.
static NSURL *nucleusUrlOf(NSString *spec) {
    if ([spec containsString:@"://"]) return [NSURL URLWithString:spec];
    return [NSURL fileURLWithPath:spec];
}

// The asset's first video track. AVFoundation's asynchronous loaders are macOS
// 12+, and this helper opens synchronously by contract (Kotlin calls it from a
// background dispatcher), so the direct accessors are the right ones here.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
static AVAssetTrack *nucleusFirstVideoTrack(AVURLAsset *asset) {
    return [asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
}
#pragma clang diagnostic pop

/* ================================================================== */
/*  JNI                                                                */
/* ================================================================== */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeOpen(
        JNIEnv *env, jclass clazz, jstring url) {
    (void)clazz;
    if (url == NULL) return 0;

    // Autorelease pool: this runs on a JVM thread, which has none of its own.
    @autoreleasepool {
        const jchar *chars = (*env)->GetStringChars(env, url, NULL);
        if (chars == NULL) return 0;
        NSString *spec = [NSString stringWithCharacters:(const unichar *)chars
                                                 length:(NSUInteger)(*env)->GetStringLength(env, url)];
        (*env)->ReleaseStringChars(env, url, chars);

        NucleusAvfVideo *v = calloc(1, sizeof(NucleusAvfVideo));
        if (v == NULL) return 0;
        mach_timebase_info_data_t timebase;
        mach_timebase_info(&timebase);
        v->secondsPerTick = (double)timebase.numer / (double)timebase.denom / 1e9;
        v->baseSeconds = -1.0;
        v->audioVolume = 1.0f;

        v->asset = [AVURLAsset URLAssetWithURL:nucleusUrlOf(spec) options:nil];
        v->track = nucleusFirstVideoTrack(v->asset);
        BOOL ok = v->track != nil;
        if (!ok) NUCLEUS_LOG("no video track in %s", spec.UTF8String);
        ok = ok && nucleusCreateDevice(v) && nucleusStartReader(v);

        // Decode the first frame before sizing anything: the buffer the decoder
        // hands out is the size the consumer has to import, and it can differ
        // from the track's natural size (alignment padding). It also makes an
        // undecodable file fail at open rather than silently at the first pull.
        ok = ok && nucleusReadAhead(v);
        if (ok) {
            CVPixelBufferRef first = (CVPixelBufferRef)CMSampleBufferGetImageBuffer(v->pending);
            v->sourceWidthPx  = (int)CVPixelBufferGetWidth(first);
            v->sourceHeightPx = (int)CVPixelBufferGetHeight(first);
            const int rotation = nucleusRotation(v->track);
            const BOOL turned = rotation == 90 || rotation == 270;
            v->widthPx  = turned ? v->sourceHeightPx : v->sourceWidthPx;
            v->heightPx = turned ? v->sourceWidthPx : v->sourceHeightPx;
            nucleusFillUv(v, rotation);
            ok = v->sourceWidthPx > 0 && v->sourceHeightPx > 0 && nucleusCreateOutput(v);
        }
        // Audio is best-effort: a file without a sound track leaves the chain
        // nil, and the sample then plays the picture only. Failing here never
        // undoes the video setup above.
        if (ok) nucleusAudioSetup(v);
        if (!ok) {
            nucleusDestroy(v);
            return 0;
        }
        return (jlong)(uintptr_t)v;
    }
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeWidth(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return handle == 0 ? 0 : (jint)VIDEO_OF(handle)->widthPx;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeHeight(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return handle == 0 ? 0 : (jint)VIDEO_OF(handle)->heightPx;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeAudioEnabled(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return handle == 0 ? JNI_FALSE : (jboolean)(VIDEO_OF(handle)->audioEnabled ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeIoSurface(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return handle == 0 ? 0 : (jlong)(uintptr_t)VIDEO_OF(handle)->surface;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativePullFrame(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (handle == 0) return -1;
    NucleusAvfVideo *v = VIDEO_OF(handle);

    @autoreleasepool {
        if (!nucleusReadAhead(v)) return -1;

        // Pacing against the file's own timeline: the first frame starts the
        // clock, every later frame waits for its presentation time. The caller
        // ticks once per composited frame, so waiting here simply means "no
        // frame yet".
        if (v->baseSeconds < 0.0) {
            v->baseSeconds = v->pendingSeconds;
            v->startTicks  = mach_absolute_time();
            // The picture just started: start the audio clock against this same
            // instant, so sound begins in step with the first shown frame.
            nucleusAudioStart(v);
        } else {
            const double due = v->pendingSeconds - v->baseSeconds;
            const double elapsed = nucleusNowSeconds(v) - (double)v->startTicks * v->secondsPerTick;
            if (elapsed < due) return 0;
        }
        return nucleusConvertPending(v);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (handle == 0) return;
    @autoreleasepool {
        nucleusDestroy(VIDEO_OF(handle));
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeSetVolume(
        JNIEnv *env, jclass clazz, jlong handle, jfloat volume) {
    (void)env; (void)clazz;
    if (handle == 0) return;
    NucleusAvfVideo *v = VIDEO_OF(handle);
    v->audioVolume = (float)volume;
    // Applied on the audio queue so it stays ordered against the feed block.
    if (v->audioQueue != nil) {
        const float value = (float)volume;
        dispatch_async(v->audioQueue, ^{
            if (v->audioRenderer != nil) v->audioRenderer.volume = value;
        });
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampleavf_NativeAvfVideoBridge_nativeSetMuted(
        JNIEnv *env, jclass clazz, jlong handle, jboolean muted) {
    (void)env; (void)clazz;
    if (handle == 0) return;
    NucleusAvfVideo *v = VIDEO_OF(handle);
    v->audioMuted = (BOOL)muted;
    if (v->audioQueue != nil) {
        const BOOL value = (BOOL)muted;
        dispatch_async(v->audioQueue, ^{
            if (v->audioRenderer != nil) v->audioRenderer.muted = value;
        });
    }
}
