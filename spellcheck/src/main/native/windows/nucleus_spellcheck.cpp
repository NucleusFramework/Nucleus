/**
 * JNI bridge for the Windows Spell Checking API (ISpellChecker).
 *
 * ISpellCheckerFactory / ISpellChecker are created per call so the
 * apartment of the JNI thread never has to match a stored COM pointer.
 * User-added words stay in Kotlin (same Nucleus user-dictionary file as
 * Linux/macOS) — ISpellChecker::Add is never called, so tests cannot
 * pollute the OS custom dictionary.
 *
 * Linked libraries: ole32
 */

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN

#include <Windows.h>
#include <spellcheck.h>

#include <jni.h>

#include <mutex>
#include <new>
#include <string>
#include <vector>

#pragma comment(lib, "ole32.lib")

struct WinEngine {
    std::wstring language;
};

static std::mutex g_mutex;
static ISpellCheckerFactory *g_factory = nullptr;

static void ensure_com() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
}

static std::wstring wstring_from_jstring(JNIEnv *env, jstring js) {
    if (!js) return std::wstring();
    const jchar *chars = env->GetStringChars(js, nullptr);
    if (!chars) return std::wstring();
    const jsize len = env->GetStringLength(js);
    std::wstring out(reinterpret_cast<const wchar_t *>(chars), static_cast<size_t>(len));
    env->ReleaseStringChars(js, chars);
    return out;
}

static jstring jstring_from_wstring(JNIEnv *env, const std::wstring &s) {
    return env->NewString(reinterpret_cast<const jchar *>(s.c_str()), static_cast<jsize>(s.size()));
}

static std::wstring to_bcp47(std::wstring tag) {
    for (wchar_t &ch : tag) {
        if (ch == L'_') ch = L'-';
    }
    return tag;
}

static std::wstring language_prefix(const std::wstring &tag) {
    const std::wstring bcp = to_bcp47(tag);
    const size_t sep = bcp.find(L'-');
    return sep == std::wstring::npos ? bcp : bcp.substr(0, sep);
}

static ISpellCheckerFactory *factory() {
    ensure_com();
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_factory) return g_factory;
    ISpellCheckerFactory *created = nullptr;
    const HRESULT hr = CoCreateInstance(
        __uuidof(SpellCheckerFactory),
        nullptr,
        CLSCTX_INPROC_SERVER,
        __uuidof(ISpellCheckerFactory),
        reinterpret_cast<void **>(&created));
    if (FAILED(hr) || !created) return nullptr;
    g_factory = created;
    return g_factory;
}

static bool is_supported(ISpellCheckerFactory *f, const std::wstring &tag) {
    if (!f || tag.empty()) return false;
    BOOL supported = FALSE;
    return SUCCEEDED(f->IsSupported(tag.c_str(), &supported)) && supported;
}

static std::vector<std::wstring> supported_languages(ISpellCheckerFactory *f) {
    std::vector<std::wstring> out;
    if (!f) return out;
    IEnumString *langs = nullptr;
    if (FAILED(f->get_SupportedLanguages(&langs)) || !langs) return out;
    LPOLESTR str = nullptr;
    ULONG fetched = 0;
    while (langs->Next(1, &str, &fetched) == S_OK) {
        if (str) {
            out.emplace_back(str);
            CoTaskMemFree(str);
            str = nullptr;
        }
    }
    langs->Release();
    return out;
}

static std::wstring match_language(ISpellCheckerFactory *f, const std::vector<std::wstring> &candidates) {
    if (!f) return std::wstring();

    for (const std::wstring &cand : candidates) {
        const std::wstring tag = to_bcp47(cand);
        if (is_supported(f, tag)) return tag;
    }

    const std::vector<std::wstring> available = supported_languages(f);
    if (available.empty()) return std::wstring();

    for (const std::wstring &cand : candidates) {
        const std::wstring prefix = language_prefix(cand);
        if (prefix.empty()) continue;
        for (const std::wstring &lang : available) {
            if (_wcsicmp(lang.c_str(), prefix.c_str()) == 0) return lang;
        }
        const std::wstring hyphen = prefix + L"-";
        for (const std::wstring &lang : available) {
            if (lang.size() > hyphen.size() &&
                _wcsnicmp(lang.c_str(), hyphen.c_str(), hyphen.size()) == 0) {
                return lang;
            }
        }
    }
    return std::wstring();
}

static ISpellChecker *create_checker(const std::wstring &language) {
    ISpellCheckerFactory *f = factory();
    if (!f || language.empty()) return nullptr;
    ISpellChecker *checker = nullptr;
    const HRESULT hr = f->CreateSpellChecker(language.c_str(), &checker);
    if (FAILED(hr) || !checker) return nullptr;
    return checker;
}

static WinEngine *engine_from_handle(jlong handle) {
    return handle == 0 ? nullptr : reinterpret_cast<WinEngine *>(static_cast<intptr_t>(handle));
}

static jobjectArray empty_string_array(JNIEnv *env, jclass stringClass) {
    return env->NewObjectArray(0, stringClass, nullptr);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_8;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_spellcheck_windows_NativeSpellcheckBridge_nativeIsAvailable(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    ISpellCheckerFactory *f = factory();
    if (!f) return JNI_FALSE;
    return supported_languages(f).empty() ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_spellcheck_windows_NativeSpellcheckBridge_nativeResolveLanguage(
    JNIEnv *env, jclass clazz, jobjectArray candidates)
{
    (void)clazz;
    ISpellCheckerFactory *f = factory();
    if (!f || !candidates) return nullptr;

    const jsize n = env->GetArrayLength(candidates);
    std::vector<std::wstring> tags;
    tags.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        jstring jc = static_cast<jstring>(env->GetObjectArrayElement(candidates, i));
        tags.push_back(wstring_from_jstring(env, jc));
        if (jc) env->DeleteLocalRef(jc);
    }

    const std::wstring matched = match_language(f, tags);
    if (matched.empty()) return nullptr;
    return jstring_from_wstring(env, matched);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_spellcheck_windows_NativeSpellcheckBridge_nativeCreate(
    JNIEnv *env, jclass clazz, jstring language)
{
    (void)clazz;
    const std::wstring tag = wstring_from_jstring(env, language);
    if (tag.empty()) return 0;

    ISpellChecker *probe = create_checker(tag);
    if (!probe) return 0;
    probe->Release();

    auto *engine = new (std::nothrow) WinEngine{tag};
    if (!engine) return 0;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_spellcheck_windows_NativeSpellcheckBridge_nativeDestroy(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    delete engine_from_handle(handle);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_spellcheck_windows_NativeSpellcheckBridge_nativeSpell(
    JNIEnv *env, jclass clazz, jlong handle, jstring word)
{
    (void)clazz;
    WinEngine *engine = engine_from_handle(handle);
    if (!engine) return JNI_FALSE;

    const std::wstring text = wstring_from_jstring(env, word);
    if (text.empty()) return JNI_FALSE;

    ISpellChecker *checker = create_checker(engine->language);
    if (!checker) return JNI_FALSE;

    IEnumSpellingError *errors = nullptr;
    const HRESULT hr = checker->Check(text.c_str(), &errors);
    jboolean ok = JNI_FALSE;
    if (SUCCEEDED(hr) && errors) {
        ISpellingError *error = nullptr;
        const HRESULT next = errors->Next(&error);
        if (error) error->Release();
        ok = (next == S_FALSE) ? JNI_TRUE : JNI_FALSE;
        errors->Release();
    }
    checker->Release();
    return ok;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_spellcheck_windows_NativeSpellcheckBridge_nativeSuggest(
    JNIEnv *env, jclass clazz, jlong handle, jstring word)
{
    (void)clazz;
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;

    WinEngine *engine = engine_from_handle(handle);
    if (!engine) return empty_string_array(env, stringClass);

    const std::wstring text = wstring_from_jstring(env, word);
    if (text.empty()) return empty_string_array(env, stringClass);

    ISpellChecker *checker = create_checker(engine->language);
    if (!checker) return empty_string_array(env, stringClass);

    IEnumString *suggestions = nullptr;
    const HRESULT hr = checker->Suggest(text.c_str(), &suggestions);
    std::vector<std::wstring> words;
    if (SUCCEEDED(hr) && suggestions) {
        LPOLESTR str = nullptr;
        ULONG fetched = 0;
        while (suggestions->Next(1, &str, &fetched) == S_OK) {
            if (str) {
                words.emplace_back(str);
                CoTaskMemFree(str);
                str = nullptr;
            }
        }
        suggestions->Release();
    }
    checker->Release();

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(words.size()), stringClass, nullptr);
    if (!result) return nullptr;
    for (size_t i = 0; i < words.size(); i++) {
        jstring js = jstring_from_wstring(env, words[i]);
        if (js) {
            env->SetObjectArrayElement(result, static_cast<jsize>(i), js);
            env->DeleteLocalRef(js);
        }
    }
    return result;
}

} // extern "C"
