// JNI bridge for the Win32 system clipboard.
//
// Supports CF_UNICODETEXT, CF_HTML, "Rich Text Format", "PNG" + CF_DIBV5,
// and CF_HDROP (with a "Preferred DropEffect" companion). Every entry opens
// the clipboard with a bounded retry loop to ride out transient contention
// from rdpclip.exe and clipboard-history viewers.

#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <shellapi.h>
#include <gdiplus.h>
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")

namespace {

// ---------------------------------------------------------------------------
// GDI+ lifecycle — init once on DLL load, shut down on unload.
// ---------------------------------------------------------------------------

ULONG_PTR g_gdiplusToken = 0;

void initGdiplus() {
    if (g_gdiplusToken != 0) return;
    Gdiplus::GdiplusStartupInput input;
    Gdiplus::GdiplusStartup(&g_gdiplusToken, &input, nullptr);
}

void shutdownGdiplus() {
    if (g_gdiplusToken != 0) {
        Gdiplus::GdiplusShutdown(g_gdiplusToken);
        g_gdiplusToken = 0;
    }
}

// ---------------------------------------------------------------------------
// Scoped clipboard open with retry.
// ---------------------------------------------------------------------------

class ScopedClipboard {
public:
    explicit ScopedClipboard(HWND owner = nullptr) {
        for (int i = 0; i < 5; ++i) {
            if (OpenClipboard(owner)) {
                opened_ = true;
                return;
            }
            Sleep(10);
        }
    }
    ~ScopedClipboard() { if (opened_) CloseClipboard(); }
    bool isOpen() const { return opened_; }
private:
    bool opened_ = false;
};

// ---------------------------------------------------------------------------
// Registered format cache.
// ---------------------------------------------------------------------------

UINT regFmt(const wchar_t* name) {
    static struct { const wchar_t* n; UINT v; } cache[8] = {};
    for (auto& e : cache) {
        if (e.n != nullptr && wcscmp(e.n, name) == 0) return e.v;
    }
    UINT v = RegisterClipboardFormatW(name);
    for (auto& e : cache) {
        if (e.n == nullptr) { e.n = name; e.v = v; break; }
    }
    return v;
}

UINT cfHtml()            { return regFmt(L"HTML Format"); }
UINT cfRtf()             { return regFmt(L"Rich Text Format"); }
UINT cfPng()             { return regFmt(L"PNG"); }
UINT cfPreferredDropEffect() { return regFmt(L"Preferred DropEffect"); }

// ---------------------------------------------------------------------------
// JNI string helpers.
// ---------------------------------------------------------------------------

std::wstring jstringToWString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return {};
    const jchar* raw = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring out(reinterpret_cast<const wchar_t*>(raw), len);
    env->ReleaseStringChars(jstr, raw);
    return out;
}

jstring wstringToJString(JNIEnv* env, const std::wstring& s) {
    return env->NewString(reinterpret_cast<const jchar*>(s.data()), static_cast<jsize>(s.size()));
}

jstring utf8ToJString(JNIEnv* env, const std::string& utf8) {
    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), static_cast<int>(utf8.size()), nullptr, 0);
    std::wstring w(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), static_cast<int>(utf8.size()), w.data(), wlen);
    return wstringToJString(env, w);
}

std::string wstringToUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    int len = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string out(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), out.data(), len, nullptr, nullptr);
    return out;
}

jbyteArray bytesToJByteArray(JNIEnv* env, const void* data, size_t len) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (arr == nullptr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len),
                            reinterpret_cast<const jbyte*>(data));
    return arr;
}

// ---------------------------------------------------------------------------
// HGLOBAL helpers for SetClipboardData payloads.
// ---------------------------------------------------------------------------

HGLOBAL makeGlobal(const void* data, size_t len) {
    HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, len);
    if (h == nullptr) return nullptr;
    void* p = GlobalLock(h);
    if (p == nullptr) { GlobalFree(h); return nullptr; }
    memcpy(p, data, len);
    GlobalUnlock(h);
    return h;
}

// ---------------------------------------------------------------------------
// CF_HTML wrapper — ASCII header + UTF-8 payload.
// learn.microsoft.com/en-us/windows/win32/dataxchg/html-clipboard-format
// ---------------------------------------------------------------------------

std::string wrapCfHtml(const std::string& fragmentUtf8) {
    // Placeholder header with 10-digit offsets — patched after assembly.
    std::string prefix =
        "Version:0.9\r\n"
        "StartHTML:0000000000\r\n"
        "EndHTML:0000000000\r\n"
        "StartFragment:0000000000\r\n"
        "EndFragment:0000000000\r\n";
    std::string open  = "<html><body>\r\n<!--StartFragment-->";
    std::string close = "<!--EndFragment-->\r\n</body></html>";

    std::string all = prefix + open + fragmentUtf8 + close;

    size_t startHtml     = prefix.size();
    size_t startFragment = prefix.size() + open.size();
    size_t endFragment   = startFragment + fragmentUtf8.size();
    size_t endHtml       = all.size();

    auto patch = [&](const char* key, size_t value) {
        size_t pos = all.find(key);
        if (pos == std::string::npos) return;
        pos += strlen(key);
        char buf[11];
        snprintf(buf, sizeof(buf), "%010zu", value);
        memcpy(&all[pos], buf, 10);
    };
    patch("StartHTML:", startHtml);
    patch("EndHTML:", endHtml);
    patch("StartFragment:", startFragment);
    patch("EndFragment:", endFragment);
    return all;
}

// Extracts the fragment between StartFragment/EndFragment byte offsets. Falls
// back to the whole payload if the header is malformed.
std::string unwrapCfHtml(const std::string& raw) {
    auto findNum = [&](const char* key) -> long long {
        size_t p = raw.find(key);
        if (p == std::string::npos) return -1;
        p += strlen(key);
        while (p < raw.size() && (raw[p] == ' ' || raw[p] == '\t')) ++p;
        long long n = 0; bool any = false;
        while (p < raw.size() && raw[p] >= '0' && raw[p] <= '9') {
            n = n * 10 + (raw[p] - '0'); ++p; any = true;
        }
        return any ? n : -1;
    };
    long long sf = findNum("StartFragment:");
    long long ef = findNum("EndFragment:");
    if (sf >= 0 && ef > sf && static_cast<size_t>(ef) <= raw.size()) {
        return raw.substr(static_cast<size_t>(sf),
                          static_cast<size_t>(ef - sf));
    }
    return raw;
}

// ---------------------------------------------------------------------------
// GDI+ image helpers — PNG ↔ DIB.
// ---------------------------------------------------------------------------

// Loads PNG bytes into a GDI+ Bitmap.
std::unique_ptr<Gdiplus::Bitmap> pngToBitmap(const BYTE* data, size_t len) {
    HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE, len);
    if (h == nullptr) return nullptr;
    void* p = GlobalLock(h);
    memcpy(p, data, len);
    GlobalUnlock(h);
    IStream* stream = nullptr;
    if (CreateStreamOnHGlobal(h, TRUE, &stream) != S_OK) { GlobalFree(h); return nullptr; }
    auto* bmp = Gdiplus::Bitmap::FromStream(stream);
    stream->Release(); // also frees the HGLOBAL (fDeleteOnRelease=TRUE)
    if (bmp == nullptr || bmp->GetLastStatus() != Gdiplus::Ok) {
        delete bmp; return nullptr;
    }
    return std::unique_ptr<Gdiplus::Bitmap>(bmp);
}

// Locates the GDI+ PNG encoder CLSID.
bool getPngEncoderClsid(CLSID& out) {
    UINT num = 0, size = 0;
    Gdiplus::GetImageEncodersSize(&num, &size);
    if (size == 0) return false;
    std::vector<BYTE> buf(size);
    auto* infos = reinterpret_cast<Gdiplus::ImageCodecInfo*>(buf.data());
    Gdiplus::GetImageEncoders(num, size, infos);
    for (UINT i = 0; i < num; ++i) {
        if (wcscmp(infos[i].MimeType, L"image/png") == 0) {
            out = infos[i].Clsid; return true;
        }
    }
    return false;
}

// Saves a GDI+ Bitmap as PNG bytes.
std::vector<BYTE> bitmapToPng(Gdiplus::Bitmap& bmp) {
    std::vector<BYTE> result;
    CLSID clsid;
    if (!getPngEncoderClsid(clsid)) return result;
    IStream* stream = nullptr;
    if (CreateStreamOnHGlobal(nullptr, TRUE, &stream) != S_OK) return result;
    if (bmp.Save(stream, &clsid, nullptr) == Gdiplus::Ok) {
        HGLOBAL h = nullptr;
        if (GetHGlobalFromStream(stream, &h) == S_OK && h != nullptr) {
            SIZE_T sz = GlobalSize(h);
            void* p = GlobalLock(h);
            if (p != nullptr) {
                result.assign(static_cast<BYTE*>(p), static_cast<BYTE*>(p) + sz);
                GlobalUnlock(h);
            }
        }
    }
    stream->Release();
    return result;
}

// Decodes a CF_DIB / CF_DIBV5 buffer into a GDI+ Bitmap by prefixing a
// synthetic BITMAPFILEHEADER and feeding it as a BMP to GDI+.
std::unique_ptr<Gdiplus::Bitmap> dibToBitmap(const BYTE* dib, size_t dibLen) {
    if (dibLen < sizeof(BITMAPINFOHEADER)) return nullptr;
    const auto* bih = reinterpret_cast<const BITMAPINFOHEADER*>(dib);
    DWORD hdrSize = bih->biSize;
    DWORD palSize = 0;
    if (bih->biBitCount <= 8) {
        palSize = (bih->biClrUsed != 0 ? bih->biClrUsed
                                       : (1u << bih->biBitCount)) * sizeof(RGBQUAD);
    } else if (bih->biCompression == BI_BITFIELDS && hdrSize == sizeof(BITMAPINFOHEADER)) {
        palSize = 3 * sizeof(DWORD);
    }
    DWORD pixelOffset = sizeof(BITMAPFILEHEADER) + hdrSize + palSize;

    std::vector<BYTE> bmp(sizeof(BITMAPFILEHEADER) + dibLen);
    auto* bfh = reinterpret_cast<BITMAPFILEHEADER*>(bmp.data());
    bfh->bfType = 0x4D42;
    bfh->bfSize = static_cast<DWORD>(bmp.size());
    bfh->bfReserved1 = 0;
    bfh->bfReserved2 = 0;
    bfh->bfOffBits = pixelOffset;
    memcpy(bmp.data() + sizeof(BITMAPFILEHEADER), dib, dibLen);

    HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE, bmp.size());
    if (h == nullptr) return nullptr;
    void* p = GlobalLock(h);
    memcpy(p, bmp.data(), bmp.size());
    GlobalUnlock(h);
    IStream* stream = nullptr;
    if (CreateStreamOnHGlobal(h, TRUE, &stream) != S_OK) { GlobalFree(h); return nullptr; }
    auto* b = Gdiplus::Bitmap::FromStream(stream);
    stream->Release();
    if (b == nullptr || b->GetLastStatus() != Gdiplus::Ok) {
        delete b; return nullptr;
    }
    return std::unique_ptr<Gdiplus::Bitmap>(b);
}

// Converts a PNG-bearing GDI+ Bitmap into a DIBV5 byte buffer (BITMAPV5HEADER
// + 32-bpp premultiplied-safe BGRA rows, bottom-up). Applies the Chromium
// alpha sanitization (R>A||G>A||B>A → force opaque) which prevents Word from
// rendering black halos around semi-transparent pixels.
std::vector<BYTE> bitmapToDibV5(Gdiplus::Bitmap& bmp) {
    std::vector<BYTE> out;
    UINT w = bmp.GetWidth(), hgt = bmp.GetHeight();
    if (w == 0 || hgt == 0) return out;

    Gdiplus::Rect rect(0, 0, static_cast<INT>(w), static_cast<INT>(hgt));
    Gdiplus::BitmapData data{};
    if (bmp.LockBits(&rect, Gdiplus::ImageLockModeRead,
                     PixelFormat32bppARGB, &data) != Gdiplus::Ok) {
        return out;
    }

    size_t rowBytes = static_cast<size_t>(w) * 4;
    out.resize(sizeof(BITMAPV5HEADER) + rowBytes * hgt);
    auto* hdr = reinterpret_cast<BITMAPV5HEADER*>(out.data());
    hdr->bV5Size          = sizeof(BITMAPV5HEADER);
    hdr->bV5Width         = static_cast<LONG>(w);
    hdr->bV5Height        = static_cast<LONG>(hgt); // positive = bottom-up
    hdr->bV5Planes        = 1;
    hdr->bV5BitCount      = 32;
    hdr->bV5Compression   = BI_BITFIELDS;
    hdr->bV5SizeImage     = static_cast<DWORD>(rowBytes * hgt);
    hdr->bV5RedMask       = 0x00FF0000;
    hdr->bV5GreenMask     = 0x0000FF00;
    hdr->bV5BlueMask      = 0x000000FF;
    hdr->bV5AlphaMask     = 0xFF000000;
    hdr->bV5CSType        = 0x73524742; // 'sRGB'
    hdr->bV5Intent        = LCS_GM_GRAPHICS;

    BYTE* dst = out.data() + sizeof(BITMAPV5HEADER);
    for (UINT y = 0; y < hgt; ++y) {
        // Flip vertically (GDI+ scan0 is top-down; DIB is bottom-up).
        const BYTE* src = static_cast<const BYTE*>(data.Scan0) +
                          static_cast<size_t>(data.Stride) * (hgt - 1 - y);
        BYTE* row = dst + y * rowBytes;
        memcpy(row, src, rowBytes);
        // Chromium fix: sanitize broken premultiplication.
        for (UINT x = 0; x < w; ++x) {
            BYTE* px = row + x * 4; // BGRA in memory, premul candidate
            BYTE b = px[0], g = px[1], r = px[2], a = px[3];
            if (a == 0) continue;
            if (r > a || g > a || b > a) {
                px[3] = 0xFF; // force opaque
            }
        }
    }
    bmp.UnlockBits(&data);
    return out;
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

std::wstring readCfUnicodeText() {
    ScopedClipboard lock;
    if (!lock.isOpen()) return {};
    HANDLE h = GetClipboardData(CF_UNICODETEXT);
    if (h == nullptr) return {};
    auto* p = static_cast<const wchar_t*>(GlobalLock(h));
    if (p == nullptr) return {};
    std::wstring out(p);
    GlobalUnlock(h);
    return out;
}

std::string readRegisteredAsBytes(UINT fmt) {
    ScopedClipboard lock;
    if (!lock.isOpen()) return {};
    HANDLE h = GetClipboardData(fmt);
    if (h == nullptr) return {};
    SIZE_T sz = GlobalSize(h);
    auto* p = static_cast<const char*>(GlobalLock(h));
    if (p == nullptr) return {};
    // Strip trailing NUL padding, which CF_HTML and RTF producers commonly emit.
    size_t n = sz;
    while (n > 0 && p[n - 1] == '\0') --n;
    std::string out(p, n);
    GlobalUnlock(h);
    return out;
}

std::vector<BYTE> readRawFormat(UINT fmt) {
    std::vector<BYTE> result;
    ScopedClipboard lock;
    if (!lock.isOpen()) return result;
    HANDLE h = GetClipboardData(fmt);
    if (h == nullptr) return result;
    SIZE_T sz = GlobalSize(h);
    auto* p = static_cast<const BYTE*>(GlobalLock(h));
    if (p == nullptr) return result;
    result.assign(p, p + sz);
    GlobalUnlock(h);
    return result;
}

std::vector<std::wstring> readHdropPaths() {
    std::vector<std::wstring> out;
    ScopedClipboard lock;
    if (!lock.isOpen()) return out;
    HANDLE h = GetClipboardData(CF_HDROP);
    if (h == nullptr) return out;
    auto hDrop = static_cast<HDROP>(h);
    UINT count = DragQueryFileW(hDrop, 0xFFFFFFFF, nullptr, 0);
    for (UINT i = 0; i < count; ++i) {
        UINT len = DragQueryFileW(hDrop, i, nullptr, 0);
        std::wstring path(len, L'\0');
        DragQueryFileW(hDrop, i, path.data(), len + 1);
        out.push_back(std::move(path));
    }
    return out;
}

// ---------------------------------------------------------------------------
// Write — atomic multi-format.
// ---------------------------------------------------------------------------

// Builds a CF_HDROP HGLOBAL from wide paths. Caller loses ownership on success.
HGLOBAL buildHdrop(const std::vector<std::wstring>& paths) {
    // Layout: DROPFILES + path1\0 path2\0 ... \0
    size_t pathChars = 1; // trailing extra NUL
    for (const auto& p : paths) pathChars += p.size() + 1;
    size_t total = sizeof(DROPFILES) + pathChars * sizeof(wchar_t);
    HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, total);
    if (h == nullptr) return nullptr;
    auto* df = static_cast<DROPFILES*>(GlobalLock(h));
    df->pFiles = sizeof(DROPFILES);
    df->fWide = TRUE;
    auto* dst = reinterpret_cast<wchar_t*>(reinterpret_cast<BYTE*>(df) + sizeof(DROPFILES));
    for (const auto& p : paths) {
        memcpy(dst, p.c_str(), (p.size() + 1) * sizeof(wchar_t));
        dst += p.size() + 1;
    }
    *dst = L'\0';
    GlobalUnlock(h);
    return h;
}

} // anonymous namespace

// ===========================================================================
// DllMain — init GDI+.
// ===========================================================================

BOOL APIENTRY DllMain(HMODULE, DWORD reason, LPVOID) {
    switch (reason) {
        case DLL_PROCESS_ATTACH: initGdiplus(); break;
        case DLL_PROCESS_DETACH: shutdownGdiplus(); break;
        default: break;
    }
    return TRUE;
}

// ===========================================================================
// JNI entry points
// ===========================================================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    initGdiplus();
    return JNI_VERSION_1_8;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeChangeCount(
    JNIEnv*, jclass) {
    return static_cast<jlong>(GetClipboardSequenceNumber());
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeAvailableFormats(
    JNIEnv* env, jclass) {
    std::vector<std::wstring> names;
    {
        ScopedClipboard lock;
        if (!lock.isOpen()) {
            jclass sc = env->FindClass("java/lang/String");
            return env->NewObjectArray(0, sc, nullptr);
        }
        UINT fmt = 0;
        while ((fmt = EnumClipboardFormats(fmt)) != 0) {
            const wchar_t* builtin = nullptr;
            switch (fmt) {
                case CF_TEXT:         builtin = L"CF_TEXT"; break;
                case CF_BITMAP:       builtin = L"CF_BITMAP"; break;
                case CF_METAFILEPICT: builtin = L"CF_METAFILEPICT"; break;
                case CF_SYLK:         builtin = L"CF_SYLK"; break;
                case CF_DIF:          builtin = L"CF_DIF"; break;
                case CF_TIFF:         builtin = L"CF_TIFF"; break;
                case CF_OEMTEXT:      builtin = L"CF_OEMTEXT"; break;
                case CF_DIB:          builtin = L"CF_DIB"; break;
                case CF_PALETTE:      builtin = L"CF_PALETTE"; break;
                case CF_PENDATA:      builtin = L"CF_PENDATA"; break;
                case CF_RIFF:         builtin = L"CF_RIFF"; break;
                case CF_WAVE:         builtin = L"CF_WAVE"; break;
                case CF_UNICODETEXT:  builtin = L"CF_UNICODETEXT"; break;
                case CF_ENHMETAFILE:  builtin = L"CF_ENHMETAFILE"; break;
                case CF_HDROP:        builtin = L"CF_HDROP"; break;
                case CF_LOCALE:       builtin = L"CF_LOCALE"; break;
                case CF_DIBV5:        builtin = L"CF_DIBV5"; break;
                default: break;
            }
            if (builtin != nullptr) {
                names.emplace_back(builtin);
            } else {
                wchar_t buf[256] = {};
                int n = GetClipboardFormatNameW(fmt, buf, 256);
                if (n > 0) names.emplace_back(buf, n);
            }
        }
    }
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), sc, nullptr);
    for (size_t i = 0; i < names.size(); ++i) {
        jstring js = wstringToJString(env, names[i]);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeReadText(
    JNIEnv* env, jclass) {
    std::wstring w = readCfUnicodeText();
    if (w.empty()) {
        // IsClipboardFormatAvailable is cheap and avoids returning "" when the
        // clipboard is truly empty.
        if (!IsClipboardFormatAvailable(CF_UNICODETEXT)) return nullptr;
    }
    return wstringToJString(env, w);
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeReadHtml(
    JNIEnv* env, jclass) {
    if (!IsClipboardFormatAvailable(cfHtml())) return nullptr;
    std::string raw = readRegisteredAsBytes(cfHtml());
    if (raw.empty()) return nullptr;
    std::string fragment = unwrapCfHtml(raw);
    return utf8ToJString(env, fragment);
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeReadRtf(
    JNIEnv* env, jclass) {
    if (!IsClipboardFormatAvailable(cfRtf())) return nullptr;
    std::string raw = readRegisteredAsBytes(cfRtf());
    if (raw.empty()) return nullptr;
    // RTF is pure ASCII with \uXXXX escapes — UTF-8 decodes it unchanged.
    return utf8ToJString(env, raw);
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeReadImagePng(
    JNIEnv* env, jclass) {
    // 1. Prefer the "PNG" registered format — no transcoding.
    if (IsClipboardFormatAvailable(cfPng())) {
        std::vector<BYTE> png = readRawFormat(cfPng());
        if (!png.empty()) return bytesToJByteArray(env, png.data(), png.size());
    }
    // 2. Fall back to DIBv5/DIB transcoded via GDI+.
    UINT fmt = 0;
    if (IsClipboardFormatAvailable(CF_DIBV5))   fmt = CF_DIBV5;
    else if (IsClipboardFormatAvailable(CF_DIB)) fmt = CF_DIB;
    if (fmt == 0) return nullptr;

    std::vector<BYTE> dib = readRawFormat(fmt);
    if (dib.empty()) return nullptr;
    auto bmp = dibToBitmap(dib.data(), dib.size());
    if (!bmp) return nullptr;
    std::vector<BYTE> png = bitmapToPng(*bmp);
    if (png.empty()) return nullptr;
    return bytesToJByteArray(env, png.data(), png.size());
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeReadFilePaths(
    JNIEnv* env, jclass) {
    std::vector<std::wstring> paths = readHdropPaths();
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(paths.size()), sc, nullptr);
    for (size_t i = 0; i < paths.size(); ++i) {
        jstring js = wstringToJString(env, paths[i]);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeWrite(
    JNIEnv* env, jclass,
    jstring jText, jstring jHtml, jstring jRtf, jbyteArray jPng, jobjectArray jPaths) {

    // Pre-convert Java arguments while no clipboard lock is held.
    std::wstring text = jstringToWString(env, jText);
    std::wstring html = jstringToWString(env, jHtml);
    std::wstring rtf  = jstringToWString(env, jRtf);

    std::vector<BYTE> pngBytes;
    if (jPng != nullptr) {
        jsize n = env->GetArrayLength(jPng);
        pngBytes.resize(n);
        env->GetByteArrayRegion(jPng, 0, n, reinterpret_cast<jbyte*>(pngBytes.data()));
    }

    std::vector<std::wstring> paths;
    if (jPaths != nullptr) {
        jsize n = env->GetArrayLength(jPaths);
        paths.reserve(n);
        for (jsize i = 0; i < n; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(jPaths, i));
            paths.push_back(jstringToWString(env, js));
            if (js != nullptr) env->DeleteLocalRef(js);
        }
    }

    // Prebuild DIBV5 from PNG when both present.
    std::vector<BYTE> dibv5;
    if (!pngBytes.empty()) {
        auto bmp = pngToBitmap(pngBytes.data(), pngBytes.size());
        if (bmp) dibv5 = bitmapToDibV5(*bmp);
    }

    ScopedClipboard lock;
    if (!lock.isOpen()) return JNI_FALSE;
    EmptyClipboard();

    bool anyPublished = false;

    // 1. Image first so compatible consumers (Word) see it before text.
    if (!pngBytes.empty()) {
        if (HGLOBAL h = makeGlobal(pngBytes.data(), pngBytes.size())) {
            if (SetClipboardData(cfPng(), h) == nullptr) GlobalFree(h);
            else anyPublished = true;
        }
        if (!dibv5.empty()) {
            if (HGLOBAL h = makeGlobal(dibv5.data(), dibv5.size())) {
                if (SetClipboardData(CF_DIBV5, h) == nullptr) GlobalFree(h);
                else anyPublished = true;
            }
        }
    }

    // 2. HTML — UTF-8 payload wrapped in the ASCII CF_HTML header.
    if (!html.empty()) {
        std::string fragment = wstringToUtf8(html);
        std::string wrapped = wrapCfHtml(fragment);
        // Include terminating NUL — most consumers expect a C-string payload.
        if (HGLOBAL h = makeGlobal(wrapped.c_str(), wrapped.size() + 1)) {
            if (SetClipboardData(cfHtml(), h) == nullptr) GlobalFree(h);
            else anyPublished = true;
        }
    }

    // 3. RTF.
    if (!rtf.empty()) {
        std::string bytes = wstringToUtf8(rtf);
        if (HGLOBAL h = makeGlobal(bytes.c_str(), bytes.size() + 1)) {
            if (SetClipboardData(cfRtf(), h) == nullptr) GlobalFree(h);
            else anyPublished = true;
        }
    }

    // 4. Plain text. Write CF_UNICODETEXT only — the OS synthesizes CF_TEXT /
    //    CF_OEMTEXT on demand via implicit conversion.
    if (!text.empty()) {
        size_t bytes = (text.size() + 1) * sizeof(wchar_t);
        if (HGLOBAL h = makeGlobal(text.c_str(), bytes)) {
            if (SetClipboardData(CF_UNICODETEXT, h) == nullptr) GlobalFree(h);
            else anyPublished = true;
        }
    }

    // 5. Files: CF_HDROP + "Preferred DropEffect" (DROPEFFECT_COPY).
    if (!paths.empty()) {
        if (HGLOBAL h = buildHdrop(paths)) {
            if (SetClipboardData(CF_HDROP, h) == nullptr) GlobalFree(h);
            else anyPublished = true;
        }
        DWORD effect = DROPEFFECT_COPY;
        if (HGLOBAL h = makeGlobal(&effect, sizeof(effect))) {
            if (SetClipboardData(cfPreferredDropEffect(), h) == nullptr) GlobalFree(h);
        }
    }

    return anyPublished ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_clipboard_windows_NativeWindowsClipboardBridge_nativeClear(
    JNIEnv*, jclass) {
    ScopedClipboard lock;
    if (!lock.isOpen()) return JNI_FALSE;
    return EmptyClipboard() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
