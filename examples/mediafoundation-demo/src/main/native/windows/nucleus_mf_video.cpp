/**
 * Sample helper for the Media Foundation → TextureView demo: decodes a video
 * file on the GPU and publishes each frame in the shared D3D11 texture the
 * TextureView import path expects, and plays its audio through WASAPI.
 *
 * Path, all of it on the GPU:
 *   IMFSourceReader (DXVA2 via IMFDXGIDeviceManager) → NV12 ID3D11Texture2D →
 *   ID3D11VideoProcessor (colour conversion + scale) → our own
 *   R8G8B8A8_UNORM texture, shared with a legacy DXGI shared handle and a
 *   keyed mutex → ANGLE opens it, Skia samples it.
 *
 * Why a video processor rather than a shader: the textures a DXVA decoder
 * hands out carry D3D11_BIND_DECODER and nothing else, so they cannot be
 * bound as shader resources. VideoProcessorBlt is the only sanctioned way to
 * read them, and it brings the colour matrix and nominal range along — the
 * same reason Chromium's Windows video path uses it.
 *
 * Output format is R8G8B8A8_UNORM because that is what the consumer samples
 * as premultiplied RGBA; the alpha fill mode is OPAQUE, so a video without an
 * alpha channel stays opaque instead of inheriting whatever the driver left.
 *
 * Synchronization: the output texture carries D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX,
 * so writes are bracketed by AcquireSync(0)/ReleaseSync(0) and the compositor
 * takes its tear-free staging path.
 *
 * Audio: the file's own track, decoded off the same reader on a thread of its
 * own and rendered by WASAPI (shared mode, event-driven). It also carries the
 * clock — see below.
 *
 * Pacing: a source reader has no clock, it decodes as fast as it is asked to,
 * so a sample is read ahead and held until its presentation time is due. When
 * there is audio, "due" is measured against the audio device's own position,
 * which is what a real player does: the ear notices drift the eye does not, and
 * a QueryPerformanceCounter clock drifts against the sound card's crystal.
 * Without audio the QPC clock is the master instead.
 *
 * Software fallback: when DXVA is unavailable (VMs, some codecs) the reader
 * returns NV12 in system memory. That is uploaded through a staging texture
 * and converted by the same video processor, so the sample still plays.
 */

#include <jni.h>
#include <windows.h>

#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>

#include <d3d11.h>
#include <d3d11_4.h>

#include <audioclient.h>
#include <mmdeviceapi.h>

#include <cstdio>
#include <new>

/* SDK 10.0.22621.0 is missing these; the engine still supports them at runtime
 * on Windows 10 21H2 and later. */
#ifndef AUDCLNT_STREAMFLAGS_AUTOCONVERT_PCM
#define AUDCLNT_STREAMFLAGS_AUTOCONVERT_PCM 0x80000000
#endif
#ifndef AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
#define AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY 0x08000000
#endif

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfreadwrite.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "ole32.lib")

#define NUCLEUS_LOG(...) do { fprintf(stderr, "mediafoundation-demo: " __VA_ARGS__); fputc('\n', stderr); } while (0)

namespace {

/** 100-nanosecond units, Media Foundation's time base. */
constexpr LONGLONG kHnsPerSecond = 10000000LL;

/** How long a frame waits for the compositor's side of the keyed mutex. */
constexpr DWORD kMutexTimeoutMs = 100;

/**
 * WASAPI buffer. Long enough that a slow decode or a scheduling hiccup does not
 * become an audible dropout, short enough that the clock it provides stays a
 * useful reference for the video.
 */
constexpr REFERENCE_TIME kAudioBufferHns = 2000000; /* 200 ms */

/** The audio track of the file, rendered by WASAPI, and the playback clock. */
struct MfAudio {
    IMMDeviceEnumerator *enumerator = nullptr;
    IMMDevice *endpoint = nullptr;
    IAudioClient *client = nullptr;
    IAudioRenderClient *render = nullptr;
    IAudioClock *audioClock = nullptr;
    WAVEFORMATEX *mixFormat = nullptr; /* CoTaskMemFree */
    WAVEFORMATEX *ownFormat = nullptr; /* our fallback format, if any */
    HANDLE readyEvent = nullptr;
    HANDLE thread = nullptr;
    UINT32 bufferFrames = 0;
    UINT32 frameBytes = 0;
    UINT32 sampleRate = 0;
    UINT64 clockFrequency = 0;
    volatile LONG stop = 0;
    volatile LONG muted = 0;
    bool active = false;

    /* The decoded sample being written out, which rarely fits the space the
     * device has free in one go. */
    IMFMediaBuffer *heldBuffer = nullptr;
    BYTE *heldData = nullptr;
    UINT32 heldFrames = 0;
    LONGLONG heldEndHns = 0; /* presentation time of the end of that sample */

    /* The clock, read by the video thread: the stream time of the last frame
     * handed to the device, and how many frames that is in total. */
    CRITICAL_SECTION lock;
    LONGLONG writtenEndHns = 0;
    UINT64 framesWritten = 0;
};

template <class T>
void release(T *&p) {
    if (p) {
        p->Release();
        p = nullptr;
    }
}

struct MfVideo {
    ID3D11Device *device = nullptr;
    ID3D11DeviceContext *context = nullptr;
    IMFDXGIDeviceManager *deviceManager = nullptr;
    IMFSourceReader *reader = nullptr;

    ID3D11VideoDevice *videoDevice = nullptr;
    ID3D11VideoContext *videoContext = nullptr;
    ID3D11VideoProcessorEnumerator *processorEnum = nullptr;
    ID3D11VideoProcessor *processor = nullptr;

    /* The frame the consumer imports — stable for the whole playback, which is
     * what keeps TextureView at one import and no recomposition per frame. */
    ID3D11Texture2D *outputTexture = nullptr;
    ID3D11VideoProcessorOutputView *outputView = nullptr;
    IDXGIKeyedMutex *keyedMutex = nullptr;
    HANDLE sharedHandle = nullptr; /* legacy handle, not an NT handle — never closed */

    /* Software-decode fallback: staging upload + a shader-readable NV12 copy. */
    ID3D11Texture2D *cpuStaging = nullptr;
    ID3D11Texture2D *cpuNv12 = nullptr;

    /* Input views are cached: a decoder recycles a small pool of textures, so
     * this hits on nearly every frame. */
    ID3D11VideoProcessorInputView *inputView = nullptr;
    ID3D11Texture2D *inputViewTexture = nullptr;
    UINT inputViewSlice = 0;

    /* The sample read ahead, waiting for its presentation time. */
    IMFSample *pending = nullptr;
    LONGLONG pendingTimeHns = 0;

    LONGLONG baseTimeHns = -1; /* presentation time of the first frame played */
    LONGLONG startTicks = 0;   /* QPC value when that frame was shown */
    LONGLONG ticksPerSecond = 0;

    int widthPx = 0;
    int heightPx = 0;
    UINT defaultStride = 0; /* only used by the software path */

    MfAudio audio;
};

LONGLONG nowTicks() {
    LARGE_INTEGER v;
    QueryPerformanceCounter(&v);
    return v.QuadPart;
}

/** MFStartup once per process; MFShutdown is deliberately never called. */
bool ensureMediaFoundation() {
    static bool started = false;
    static bool ok = false;
    if (started) return ok;
    started = true;
    /* WASAPI is COM, and the apartment has to outlive every object created in
     * it — so, like MFShutdown, CoUninitialize is deliberately never called. */
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    ok = SUCCEEDED(MFStartup(MF_VERSION, MFSTARTUP_LITE));
    if (!ok) NUCLEUS_LOG("MFStartup failed — Media Foundation unavailable (N edition without the media pack?)");
    return ok;
}

bool createDevice(MfVideo *v) {
    /* VIDEO_SUPPORT is what makes ID3D11VideoDevice available; BGRA_SUPPORT is
     * required of any device Media Foundation is handed. */
    const UINT flags = D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags,
                                   nullptr, 0, D3D11_SDK_VERSION,
                                   &v->device, nullptr, &v->context);
    if (FAILED(hr)) {
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, flags,
                               nullptr, 0, D3D11_SDK_VERSION,
                               &v->device, nullptr, &v->context);
    }
    if (FAILED(hr)) {
        NUCLEUS_LOG("D3D11CreateDevice failed (0x%08lX)", (unsigned long)hr);
        return false;
    }

    /* Media Foundation drives this device from its own worker threads. */
    ID3D11Multithread *mt = nullptr;
    if (SUCCEEDED(v->context->QueryInterface(IID_PPV_ARGS(&mt)))) {
        mt->SetMultithreadProtected(TRUE);
        mt->Release();
    }
    return true;
}

/**
 * Builds the source reader and negotiates NV12 output. [allowConversion] adds
 * the reader's own video processor, which is only needed when the decoder
 * cannot produce NV12 itself (10-bit sources come out as P010) — it is left
 * off first because it can also move the frames back into system memory.
 */
bool createReader(MfVideo *v, LPCWSTR url, bool allowConversion) {
    if (!v->deviceManager) {
        UINT resetToken = 0;
        if (FAILED(MFCreateDXGIDeviceManager(&resetToken, &v->deviceManager)) ||
            FAILED(v->deviceManager->ResetDevice(v->device, resetToken))) {
            NUCLEUS_LOG("could not set up the DXGI device manager");
            return false;
        }
    }

    IMFAttributes *attrs = nullptr;
    if (FAILED(MFCreateAttributes(&attrs, 3))) return false;
    attrs->SetUnknown(MF_SOURCE_READER_D3D_MANAGER, v->deviceManager);
    /* Hardware decode into D3D11 textures — the whole point of this path. */
    attrs->SetUINT32(MF_SOURCE_READER_DISABLE_DXVA, FALSE);
    if (allowConversion) {
        attrs->SetUINT32(MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, TRUE);
    }

    HRESULT hr = MFCreateSourceReaderFromURL(url, attrs, &v->reader);
    attrs->Release();
    if (FAILED(hr)) {
        if (allowConversion) { /* the second and last attempt */
            NUCLEUS_LOG("MFCreateSourceReaderFromURL failed (0x%08lX) — unsupported container or missing codec",
                        (unsigned long)hr);
        }
        return false;
    }

    v->reader->SetStreamSelection((DWORD)MF_SOURCE_READER_ALL_STREAMS, FALSE);
    v->reader->SetStreamSelection((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE);

    IMFMediaType *wanted = nullptr;
    if (FAILED(MFCreateMediaType(&wanted))) return false;
    wanted->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    wanted->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    hr = v->reader->SetCurrentMediaType((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, wanted);
    wanted->Release();
    if (FAILED(hr)) {
        if (!allowConversion) return false; /* retried with the converter enabled */
        NUCLEUS_LOG("no NV12 output available for the first video stream (0x%08lX)", (unsigned long)hr);
        return false;
    }
    return true;
}

/** What the negotiated output type says about the frames to come. */
struct StreamInfo {
    int widthPx = 0;
    int heightPx = 0;
    UINT stride = 0;
    UINT matrix = 1;                                                 /* VP YCbCr_Matrix */
    UINT nominalRange = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;   /* VP Nominal_Range */
};

bool readStreamInfo(MfVideo *v, StreamInfo *out) {
    IMFMediaType *type = nullptr;
    if (FAILED(v->reader->GetCurrentMediaType((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, &type))) {
        return false;
    }
    UINT32 w = 0, h = 0;
    if (SUCCEEDED(MFGetAttributeSize(type, MF_MT_FRAME_SIZE, &w, &h))) {
        out->widthPx = (int)w;
        out->heightPx = (int)h;
    }

    UINT32 stride = 0;
    if (SUCCEEDED(type->GetUINT32(MF_MT_DEFAULT_STRIDE, &stride))) {
        out->stride = stride;
    } else {
        out->stride = w; /* NV12 luma stride when the type does not say */
    }

    /* BT.601 below the SD/HD boundary, BT.709 above, unless the type is
     * explicit — the same guess every player makes. */
    UINT32 transferMatrix = 0;
    if (SUCCEEDED(type->GetUINT32(MF_MT_YUV_MATRIX, &transferMatrix)) &&
        (transferMatrix == MFVideoTransferMatrix_BT601 ||
         transferMatrix == MFVideoTransferMatrix_BT709)) {
        out->matrix = transferMatrix == MFVideoTransferMatrix_BT709 ? 1u : 0u;
    } else {
        out->matrix = h >= 720 ? 1u : 0u;
    }

    UINT32 mfRange = 0;
    if (SUCCEEDED(type->GetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, &mfRange)) &&
        mfRange == MFNominalRange_0_255) {
        out->nominalRange = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255;
    } else {
        out->nominalRange = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    }

    type->Release();
    return out->widthPx > 0 && out->heightPx > 0;
}

bool createSharedOutput(MfVideo *v) {
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = (UINT)v->widthPx;
    desc.Height = (UINT)v->heightPx;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    /* RGBA8, premultiplied — what the consumer samples. */
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    /* Keyed mutex: yields a legacy shared handle too, and puts the consumer on
     * its tear-free staging path. */
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;
    if (FAILED(v->device->CreateTexture2D(&desc, nullptr, &v->outputTexture))) {
        NUCLEUS_LOG("could not create the shared output texture");
        return false;
    }

    IDXGIResource *res = nullptr;
    if (FAILED(v->outputTexture->QueryInterface(IID_PPV_ARGS(&res)))) return false;
    HRESULT hr = res->GetSharedHandle(&v->sharedHandle);
    res->Release();
    if (FAILED(hr) || !v->sharedHandle) {
        NUCLEUS_LOG("GetSharedHandle failed — the consumer needs a legacy shared handle");
        return false;
    }
    return SUCCEEDED(v->outputTexture->QueryInterface(IID_PPV_ARGS(&v->keyedMutex)));
}

/** Tells the video processor how to read the luma/chroma it is given. */
void applyColorSpace(MfVideo *v, const StreamInfo &info) {
    if (!v->processor) return;
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE inputSpace = {};
    inputSpace.Usage = 0; /* playback, not video processing */
    inputSpace.YCbCr_Matrix = info.matrix;
    inputSpace.Nominal_Range = info.nominalRange;
    v->videoContext->VideoProcessorSetStreamColorSpace(v->processor, 0, &inputSpace);
}

bool createProcessor(MfVideo *v, const StreamInfo &info) {
    if (FAILED(v->device->QueryInterface(IID_PPV_ARGS(&v->videoDevice))) ||
        FAILED(v->context->QueryInterface(IID_PPV_ARGS(&v->videoContext)))) {
        NUCLEUS_LOG("no D3D11 video device on this adapter");
        return false;
    }

    D3D11_VIDEO_PROCESSOR_CONTENT_DESC content = {};
    content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    content.InputWidth = (UINT)v->widthPx;
    content.InputHeight = (UINT)v->heightPx;
    content.OutputWidth = (UINT)v->widthPx;
    content.OutputHeight = (UINT)v->heightPx;
    content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    if (FAILED(v->videoDevice->CreateVideoProcessorEnumerator(&content, &v->processorEnum))) {
        NUCLEUS_LOG("CreateVideoProcessorEnumerator failed");
        return false;
    }

    /* RGBA8 output is what the consumer samples; refuse rather than silently
     * hand over swapped channels through a BGRA fallback. */
    UINT support = 0;
    if (FAILED(v->processorEnum->CheckVideoProcessorFormat(DXGI_FORMAT_R8G8B8A8_UNORM, &support)) ||
        !(support & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_OUTPUT)) {
        NUCLEUS_LOG("the video processor cannot output R8G8B8A8_UNORM on this adapter");
        return false;
    }

    if (FAILED(v->videoDevice->CreateVideoProcessor(v->processorEnum, 0, &v->processor))) {
        NUCLEUS_LOG("CreateVideoProcessor failed");
        return false;
    }

    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outDesc = {};
    outDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    outDesc.Texture2D.MipSlice = 0;
    if (FAILED(v->videoDevice->CreateVideoProcessorOutputView(
            v->outputTexture, v->processorEnum, &outDesc, &v->outputView))) {
        NUCLEUS_LOG("CreateVideoProcessorOutputView failed");
        return false;
    }

    applyColorSpace(v, info);

    D3D11_VIDEO_PROCESSOR_COLOR_SPACE outputSpace = {};
    outputSpace.Usage = 0;
    outputSpace.RGB_Range = 0; /* full range RGB */
    v->videoContext->VideoProcessorSetOutputColorSpace(v->processor, &outputSpace);

    /* A video carries no alpha; without this the driver is free to leave it at 0
     * and the frame composites as fully transparent. */
    v->videoContext->VideoProcessorSetOutputAlphaFillMode(
        v->processor, D3D11_VIDEO_PROCESSOR_ALPHA_FILL_MODE_OPAQUE, 0);
    /* Driver "enhancements" (sharpening, denoise) have no place in a sample. */
    v->videoContext->VideoProcessorSetStreamAutoProcessingMode(v->processor, 0, FALSE);
    v->videoContext->VideoProcessorSetStreamFrameFormat(
        v->processor, 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
    return true;
}

/* ================================================================== */
/*  Audio: the file's own track, rendered by WASAPI                    */
/* ================================================================== */

void releaseHeldAudio(MfAudio *a) {
    if (a->heldBuffer) {
        a->heldBuffer->Unlock();
        release(a->heldBuffer);
    }
    a->heldData = nullptr;
    a->heldFrames = 0;
}

/** Decodes the next audio sample and locks it for the writer. */
bool fetchAudioSample(MfVideo *v) {
    MfAudio *a = &v->audio;
    DWORD flags = 0;
    LONGLONG timeHns = 0;
    IMFSample *sample = nullptr;
    if (FAILED(v->reader->ReadSample((DWORD)MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0,
                                     nullptr, &flags, &timeHns, &sample))) {
        return false;
    }
    /* End of stream or a gap: the video side owns the loop, so there is simply
     * nothing to play until it seeks. */
    if (!sample) return false;

    IMFMediaBuffer *buffer = nullptr;
    BYTE *data = nullptr;
    DWORD length = 0;
    if (FAILED(sample->ConvertToContiguousBuffer(&buffer)) ||
        FAILED(buffer->Lock(&data, nullptr, &length))) {
        release(buffer);
        release(sample);
        return false;
    }
    release(sample);

    a->heldBuffer = buffer;
    a->heldData = data;
    a->heldFrames = a->frameBytes ? (UINT32)(length / a->frameBytes) : 0u;
    a->heldEndHns = timeHns + (LONGLONG)a->heldFrames * kHnsPerSecond / (LONGLONG)a->sampleRate;
    if (a->heldFrames == 0) {
        releaseHeldAudio(a);
        return false;
    }
    return true;
}

/** Hands the device as much as it has room for. */
void fillAudioBuffer(MfVideo *v) {
    MfAudio *a = &v->audio;
    UINT32 padding = 0;
    if (FAILED(a->client->GetCurrentPadding(&padding))) return;
    UINT32 freeFrames = a->bufferFrames - padding;

    while (freeFrames > 0 && !a->stop) {
        if (a->heldFrames == 0 && !fetchAudioSample(v)) return;
        const UINT32 count = freeFrames < a->heldFrames ? freeFrames : a->heldFrames;
        BYTE *dst = nullptr;
        if (FAILED(a->render->GetBuffer(count, &dst))) return;
        /* Muting writes silence rather than stopping the stream: the clock has to
         * keep running, or the video would stop with it. */
        if (a->muted) {
            memset(dst, 0, (size_t)count * a->frameBytes);
        } else {
            memcpy(dst, a->heldData, (size_t)count * a->frameBytes);
        }
        a->render->ReleaseBuffer(count, 0);

        a->heldData += (size_t)count * a->frameBytes;
        a->heldFrames -= count;
        freeFrames -= count;

        EnterCriticalSection(&a->lock);
        a->framesWritten += count;
        /* Stream time of what has now been handed over: the end of the sample,
         * less whatever of it is still waiting. */
        a->writtenEndHns =
            a->heldEndHns - (LONGLONG)a->heldFrames * kHnsPerSecond / (LONGLONG)a->sampleRate;
        LeaveCriticalSection(&a->lock);

        if (a->heldFrames == 0) releaseHeldAudio(a);
    }
}

DWORD WINAPI audioThread(LPVOID param) {
    MfVideo *v = (MfVideo *)param;
    MfAudio *a = &v->audio;
    /* WASAPI is COM, and this thread creates nothing but calls plenty. */
    const HRESULT coHr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    /* Audio is the one thing the user hears drop out. */
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);

    /* Prime the buffer before starting, so the stream does not open on silence. */
    fillAudioBuffer(v);
    if (FAILED(a->client->Start())) {
        NUCLEUS_LOG("IAudioClient::Start failed — playing without sound");
    } else {
        while (!a->stop) {
            if (WaitForSingleObject(a->readyEvent, 200) == WAIT_FAILED) break;
            fillAudioBuffer(v);
        }
        a->client->Stop();
    }

    releaseHeldAudio(a);
    if (SUCCEEDED(coHr)) CoUninitialize();
    return 0;
}

/** Asks the reader for [format] on the audio stream. */
bool setAudioOutputType(MfVideo *v, const WAVEFORMATEX *format) {
    IMFMediaType *type = nullptr;
    if (FAILED(MFCreateMediaType(&type))) return false;
    HRESULT hr = MFInitMediaTypeFromWaveFormatEx(
        type, format, (UINT32)(sizeof(WAVEFORMATEX) + format->cbSize));
    if (SUCCEEDED(hr)) {
        hr = v->reader->SetCurrentMediaType((DWORD)MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, type);
    }
    type->Release();
    return SUCCEEDED(hr);
}

/** The file's own sample rate, for the fallback format. */
UINT32 nativeAudioSampleRate(MfVideo *v) {
    IMFMediaType *type = nullptr;
    UINT32 rate = 0;
    if (SUCCEEDED(v->reader->GetNativeMediaType(
            (DWORD)MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, &type))) {
        type->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &rate);
        type->Release();
    }
    return rate ? rate : 48000u;
}

/**
 * Selects the audio stream, negotiates a format both the decoder and the device
 * accept, and starts the render thread. False simply means "no sound" — a file
 * without audio, a machine without an endpoint — and playback goes on.
 */
bool setupAudio(MfVideo *v) {
    MfAudio *a = &v->audio;
    if (FAILED(v->reader->SetStreamSelection((DWORD)MF_SOURCE_READER_FIRST_AUDIO_STREAM, TRUE))) {
        return false; /* no audio stream in this file */
    }

    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                IID_PPV_ARGS(&a->enumerator))) ||
        FAILED(a->enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &a->endpoint)) ||
        FAILED(a->endpoint->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                     (void **)&a->client)) ||
        FAILED(a->client->GetMixFormat(&a->mixFormat))) {
        NUCLEUS_LOG("no usable audio endpoint — playing without sound");
        return false;
    }

    /* First choice is the device's own mix format: the decoder converts straight
     * to it and the audio engine has nothing left to do. */
    const WAVEFORMATEX *format = a->mixFormat;
    DWORD streamFlags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
    if (!setAudioOutputType(v, format)) {
        /* Some decoders will not produce a multichannel float layout. 16-bit
         * stereo at the file's own rate is universally available, and
         * AUTOCONVERT_PCM lets the engine take it from there. */
        a->ownFormat = (WAVEFORMATEX *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY,
                                                 sizeof(WAVEFORMATEX));
        if (!a->ownFormat) return false;
        a->ownFormat->wFormatTag = WAVE_FORMAT_PCM;
        a->ownFormat->nChannels = 2;
        a->ownFormat->nSamplesPerSec = nativeAudioSampleRate(v);
        a->ownFormat->wBitsPerSample = 16;
        a->ownFormat->nBlockAlign = (WORD)(a->ownFormat->nChannels * 2);
        a->ownFormat->nAvgBytesPerSec = a->ownFormat->nSamplesPerSec * a->ownFormat->nBlockAlign;
        format = a->ownFormat;
        streamFlags |= AUDCLNT_STREAMFLAGS_AUTOCONVERT_PCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        if (!setAudioOutputType(v, format)) {
            NUCLEUS_LOG("the audio decoder offered no usable format — playing without sound");
            return false;
        }
    }

    if (FAILED(a->client->Initialize(AUDCLNT_SHAREMODE_SHARED, streamFlags,
                                     kAudioBufferHns, 0, format, nullptr))) {
        NUCLEUS_LOG("IAudioClient::Initialize failed — playing without sound");
        return false;
    }
    a->readyEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!a->readyEvent ||
        FAILED(a->client->SetEventHandle(a->readyEvent)) ||
        FAILED(a->client->GetBufferSize(&a->bufferFrames)) ||
        FAILED(a->client->GetService(IID_PPV_ARGS(&a->render))) ||
        FAILED(a->client->GetService(IID_PPV_ARGS(&a->audioClock))) ||
        FAILED(a->audioClock->GetFrequency(&a->clockFrequency)) ||
        a->clockFrequency == 0) {
        NUCLEUS_LOG("could not set the audio render client up — playing without sound");
        return false;
    }
    a->frameBytes = format->nBlockAlign;
    a->sampleRate = format->nSamplesPerSec;

    InitializeCriticalSection(&a->lock);
    a->active = true;
    a->thread = CreateThread(nullptr, 0, audioThread, v, 0, nullptr);
    if (!a->thread) {
        DeleteCriticalSection(&a->lock);
        a->active = false;
        return false;
    }
    return true;
}

/**
 * Where playback actually is, in the stream's own time base, taken from the
 * audio device: the time of the last frame handed over, less what is still
 * queued ahead of the speaker. Self-correcting after a seek — the queue then
 * holds pre-seek audio for one buffer's worth of time, during which the video
 * waits rather than run ahead.
 */
bool audioTimeHns(MfVideo *v, LONGLONG *outHns) {
    MfAudio *a = &v->audio;
    if (!a->active) return false;
    UINT64 position = 0;
    if (FAILED(a->audioClock->GetPosition(&position, nullptr))) return false;

    EnterCriticalSection(&a->lock);
    const LONGLONG writtenEndHns = a->writtenEndHns;
    const UINT64 framesWritten = a->framesWritten;
    LeaveCriticalSection(&a->lock);
    if (framesWritten == 0) return false; /* nothing has reached the device yet */

    const LONGLONG playedHns = (LONGLONG)(position * kHnsPerSecond / a->clockFrequency);
    const LONGLONG writtenHns =
        (LONGLONG)(framesWritten * kHnsPerSecond / (UINT64)a->sampleRate);
    const LONGLONG queuedHns = writtenHns > playedHns ? writtenHns - playedHns : 0;
    *outHns = writtenEndHns - queuedHns;
    return true;
}

void destroyAudio(MfAudio *a) {
    if (a->thread) {
        InterlockedExchange(&a->stop, 1);
        SetEvent(a->readyEvent);
        WaitForSingleObject(a->thread, 2000);
        CloseHandle(a->thread);
        a->thread = nullptr;
    }
    if (a->active) {
        DeleteCriticalSection(&a->lock);
        a->active = false;
    }
    releaseHeldAudio(a);
    release(a->audioClock);
    release(a->render);
    release(a->client);
    release(a->endpoint);
    release(a->enumerator);
    if (a->mixFormat) {
        CoTaskMemFree(a->mixFormat);
        a->mixFormat = nullptr;
    }
    if (a->ownFormat) {
        HeapFree(GetProcessHeap(), 0, a->ownFormat);
        a->ownFormat = nullptr;
    }
    if (a->readyEvent) {
        CloseHandle(a->readyEvent);
        a->readyEvent = nullptr;
    }
}

/** Rewinds to the start, so the sample loops like the GStreamer one does. */
void seekToStart(MfVideo *v) {
    PROPVARIANT pos;
    PropVariantInit(&pos);
    pos.vt = VT_I8;
    pos.hVal.QuadPart = 0;
    v->reader->SetCurrentPosition(GUID_NULL, pos);
    PropVariantClear(&pos);
    v->baseTimeHns = -1;
}

/**
 * Fills [pending] with the next decodable sample, looping at end of stream.
 * False means the reader gave up for good.
 */
bool readAhead(MfVideo *v) {
    for (int attempt = 0; attempt < 64; ++attempt) {
        DWORD flags = 0;
        LONGLONG timeHns = 0;
        IMFSample *sample = nullptr;
        HRESULT hr = v->reader->ReadSample((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                                           nullptr, &flags, &timeHns, &sample);
        if (FAILED(hr)) {
            NUCLEUS_LOG("ReadSample failed (0x%08lX)", (unsigned long)hr);
            release(sample);
            return false;
        }
        if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
            release(sample);
            seekToStart(v);
            continue;
        }
        if (flags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) {
            /* The decoder settles its output type on the first sample, and again
             * after a seek — expected, and not a reason to stop. Only a change of
             * frame size is fatal: the consumer has already imported a texture of
             * the old one. */
            StreamInfo info;
            if (!readStreamInfo(v, &info)) {
                release(sample);
                return false;
            }
            /* Before the output texture exists this is just the size settling;
             * afterwards the consumer has already imported the old one. */
            if (v->outputTexture &&
                (info.widthPx != v->widthPx || info.heightPx != v->heightPx)) {
                NUCLEUS_LOG("frame size changed mid-playback (%dx%d → %dx%d) — stopping",
                            v->widthPx, v->heightPx, info.widthPx, info.heightPx);
                release(sample);
                return false;
            }
            v->widthPx = info.widthPx;
            v->heightPx = info.heightPx;
            v->defaultStride = info.stride;
            applyColorSpace(v, info); /* a no-op until the processor exists */
        }
        if (!sample) continue; /* stream tick or a gap — ask again */

        v->pending = sample;
        v->pendingTimeHns = timeHns;
        return true;
    }
    return false;
}

/** Uploads a system-memory NV12 sample and returns it as a VP-readable texture. */
ID3D11Texture2D *uploadSoftwareFrame(MfVideo *v, IMFSample *sample) {
    IMFMediaBuffer *buffer = nullptr;
    if (FAILED(sample->ConvertToContiguousBuffer(&buffer))) return nullptr;

    BYTE *src = nullptr;
    LONG srcPitch = (LONG)v->defaultStride;
    DWORD length = 0;
    IMF2DBuffer2 *buffer2d = nullptr;
    BYTE *scanline0 = nullptr;
    bool locked2d = false;
    if (SUCCEEDED(buffer->QueryInterface(IID_PPV_ARGS(&buffer2d))) &&
        SUCCEEDED(buffer2d->Lock2DSize(MF2DBuffer_LockFlags_Read, &scanline0, &srcPitch, &src, &length))) {
        locked2d = true;
        src = scanline0;
    } else if (FAILED(buffer->Lock(&src, nullptr, &length))) {
        release(buffer2d);
        release(buffer);
        return nullptr;
    }

    if (!v->cpuStaging) {
        D3D11_TEXTURE2D_DESC desc = {};
        desc.Width = (UINT)v->widthPx;
        desc.Height = (UINT)v->heightPx;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_NV12;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_STAGING;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
        v->device->CreateTexture2D(&desc, nullptr, &v->cpuStaging);

        /* The video processor reads a DEFAULT texture, not a staging one. */
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.CPUAccessFlags = 0;
        desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        v->device->CreateTexture2D(&desc, nullptr, &v->cpuNv12);
    }

    ID3D11Texture2D *result = nullptr;
    D3D11_MAPPED_SUBRESOURCE mapped = {};
    if (v->cpuStaging && v->cpuNv12 &&
        SUCCEEDED(v->context->Map(v->cpuStaging, 0, D3D11_MAP_WRITE, 0, &mapped))) {
        /* NV12 maps as the luma plane followed by the interleaved chroma plane,
         * both at the mapped row pitch. */
        BYTE *dst = (BYTE *)mapped.pData;
        const int rows = v->heightPx;
        const size_t copyBytes = (size_t)v->widthPx;
        for (int y = 0; y < rows; ++y) {
            memcpy(dst + (size_t)y * mapped.RowPitch, src + (size_t)y * srcPitch, copyBytes);
        }
        const BYTE *srcUv = src + (size_t)srcPitch * rows;
        BYTE *dstUv = dst + (size_t)mapped.RowPitch * rows;
        for (int y = 0; y < rows / 2; ++y) {
            memcpy(dstUv + (size_t)y * mapped.RowPitch, srcUv + (size_t)y * srcPitch, copyBytes);
        }
        v->context->Unmap(v->cpuStaging, 0);
        v->context->CopyResource(v->cpuNv12, v->cpuStaging);
        result = v->cpuNv12;
        result->AddRef(); /* the caller releases what it gets, on both paths */
    }

    if (locked2d) {
        buffer2d->Unlock2D();
    } else {
        buffer->Unlock();
    }
    release(buffer2d);
    release(buffer);
    return result;
}

/**
 * The decoder's own texture when the sample is DXGI-backed, else an upload of
 * its system-memory pixels. Returns a reference the caller releases.
 */
ID3D11Texture2D *frameTexture(MfVideo *v, IMFSample *sample, UINT *slice) {
    *slice = 0;
    IMFMediaBuffer *buffer = nullptr;
    if (FAILED(sample->GetBufferByIndex(0, &buffer)) || !buffer) return nullptr;

    IMFDXGIBuffer *dxgi = nullptr;
    ID3D11Texture2D *texture = nullptr;
    if (SUCCEEDED(buffer->QueryInterface(IID_PPV_ARGS(&dxgi)))) {
        if (SUCCEEDED(dxgi->GetResource(IID_PPV_ARGS(&texture)))) {
            /* A decoder hands out array textures: the slice says which frame. */
            dxgi->GetSubresourceIndex(slice);
        }
        dxgi->Release();
    }
    release(buffer);

    if (texture) return texture;
    return uploadSoftwareFrame(v, sample);
}

bool bindInputView(MfVideo *v, ID3D11Texture2D *texture, UINT slice) {
    if (v->inputView && v->inputViewTexture == texture && v->inputViewSlice == slice) return true;

    release(v->inputView);
    v->inputViewTexture = nullptr;
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC desc = {};
    desc.FourCC = 0; /* the texture's own format */
    desc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    desc.Texture2D.MipSlice = 0;
    desc.Texture2D.ArraySlice = slice;
    if (FAILED(v->videoDevice->CreateVideoProcessorInputView(
            texture, v->processorEnum, &desc, &v->inputView))) {
        return false;
    }
    v->inputViewTexture = texture;
    v->inputViewSlice = slice;
    return true;
}

/** Converts [pending] into the shared texture. Consumes the sample either way. */
int blitPending(MfVideo *v) {
    UINT slice = 0;
    ID3D11Texture2D *texture = frameTexture(v, v->pending, &slice);
    int result = -1;
    if (texture && bindInputView(v, texture, slice)) {
        if (v->keyedMutex->AcquireSync(0, kMutexTimeoutMs) == S_OK) {
            D3D11_VIDEO_PROCESSOR_STREAM stream = {};
            stream.Enable = TRUE;
            stream.pInputSurface = v->inputView;
            HRESULT hr = v->videoContext->VideoProcessorBlt(v->processor, v->outputView, 0, 1, &stream);
            /* Producer and consumer sit on different devices: flush so the write
             * is visible through the shared resource before the next composite. */
            v->context->Flush();
            v->keyedMutex->ReleaseSync(0);
            result = SUCCEEDED(hr) ? 1 : -1;
        } else {
            result = 0; /* consumer holds the mutex — drop this frame, not the clock */
        }
    }
    release(texture);
    release(v->pending);
    return result;
}

void destroy(MfVideo *v) {
    /* The render thread reads from the reader: it has to be gone first. */
    destroyAudio(&v->audio);
    release(v->pending);
    release(v->inputView);
    v->inputViewTexture = nullptr;
    release(v->cpuNv12);
    release(v->cpuStaging);
    release(v->outputView);
    release(v->processor);
    release(v->processorEnum);
    release(v->videoContext);
    release(v->videoDevice);
    release(v->keyedMutex);
    release(v->outputTexture);
    release(v->reader);
    release(v->deviceManager);
    release(v->context);
    release(v->device);
    delete v;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativeOpen(
    JNIEnv *env, jclass clazz, jstring url)
{
    (void)clazz;
    if (!url || !ensureMediaFoundation()) return 0;

    const jchar *chars = env->GetStringChars(url, nullptr);
    const jsize length = env->GetStringLength(url);
    if (!chars) return 0;
    WCHAR *wide = new (std::nothrow) WCHAR[(size_t)length + 1];
    if (!wide) {
        env->ReleaseStringChars(url, chars);
        return 0;
    }
    memcpy(wide, chars, (size_t)length * sizeof(WCHAR));
    wide[length] = L'\0';
    env->ReleaseStringChars(url, chars);

    MfVideo *v = new (std::nothrow) MfVideo();
    if (!v) {
        delete[] wide;
        return 0;
    }
    LARGE_INTEGER freq;
    QueryPerformanceFrequency(&freq);
    v->ticksPerSecond = freq.QuadPart;

    StreamInfo info;
    bool ok = createDevice(v);
    if (ok && !createReader(v, wide, /* allowConversion = */ false)) {
        /* The decoder refused NV12 — build the reader again, this time with its
         * own converter in the chain. */
        release(v->reader);
        ok = createReader(v, wide, /* allowConversion = */ true);
    }
    /* Decode the first frame before sizing anything: a decoder only settles its
     * output type once it produces a sample, and that is the size the consumer
     * has to import. It also makes an undecodable file fail at open rather than
     * silently at the first pull. */
    ok = ok && readAhead(v) && readStreamInfo(v, &info);
    if (ok) {
        v->widthPx = info.widthPx;
        v->heightPx = info.heightPx;
        v->defaultStride = info.stride;
    }
    ok = ok && createSharedOutput(v) && createProcessor(v, info);
    delete[] wide;
    if (!ok) {
        destroy(v);
        return 0;
    }
    /* Sound is a bonus, not a requirement: a file without an audio track, or a
     * machine without an endpoint, still plays. */
    if (!setupAudio(v)) destroyAudio(&v->audio);
    return (jlong)(uintptr_t)v;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    MfVideo *v = (MfVideo *)(uintptr_t)handle;
    return v ? (jint)v->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    MfVideo *v = (MfVideo *)(uintptr_t)handle;
    return v ? (jint)v->heightPx : 0;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativeSharedHandle(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    MfVideo *v = (MfVideo *)(uintptr_t)handle;
    return v ? (jlong)(uintptr_t)v->sharedHandle : 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativeHasAudio(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    MfVideo *v = (MfVideo *)(uintptr_t)handle;
    return (v && v->audio.active) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativeSetMuted(
    JNIEnv *env, jclass clazz, jlong handle, jboolean muted)
{
    (void)env; (void)clazz;
    MfVideo *v = (MfVideo *)(uintptr_t)handle;
    if (v) InterlockedExchange(&v->audio.muted, muted ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativePullFrame(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    MfVideo *v = (MfVideo *)(uintptr_t)handle;
    if (!v) return -1;

    if (!v->pending && !readAhead(v)) return -1;

    /* Pacing against the file's own timeline. The caller ticks once per
     * composited frame, so waiting here simply means "no frame yet". */
    LONGLONG audioHns = 0;
    if (audioTimeHns(v, &audioHns)) {
        /* Sound leads: the frame goes up when the speaker reaches its time. */
        if (v->pendingTimeHns > audioHns) return 0;
    } else if (v->baseTimeHns < 0) {
        /* No audio (or none playing yet): the first frame starts a QPC clock. */
        v->baseTimeHns = v->pendingTimeHns;
        v->startTicks = nowTicks();
    } else {
        const LONGLONG dueHns = v->pendingTimeHns - v->baseTimeHns;
        const LONGLONG elapsedHns =
            (nowTicks() - v->startTicks) * kHnsPerSecond / v->ticksPerSecond;
        if (elapsedHns < dueHns) return 0;
    }
    return blitPending(v);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_samplemf_NativeMfVideoBridge_nativeClose(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    MfVideo *v = (MfVideo *)(uintptr_t)handle;
    if (v) destroy(v);
}

} // extern "C"
