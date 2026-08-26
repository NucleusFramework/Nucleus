/**
 * JNI bridge for Linux Hunspell spell checking.
 *
 * libhunspell is loaded at runtime via dlopen so the .so has no hard
 * link-time dependency beyond libc/libdl. Dictionary .aff/.dic paths are
 * supplied by Kotlin (locale matching lives there).
 *
 * Linked libraries: -ldl
 */

#include <jni.h>
#include <dlfcn.h>
#include <iconv.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

typedef struct Hunhandle Hunhandle;

typedef Hunhandle *(*fn_create)(const char *, const char *);
typedef void (*fn_destroy)(Hunhandle *);
typedef int (*fn_spell)(Hunhandle *, const char *);
typedef int (*fn_suggest)(Hunhandle *, char ***, const char *);
typedef void (*fn_free_list)(Hunhandle *, char ***, int);
typedef int (*fn_add)(Hunhandle *, const char *);
typedef char *(*fn_encoding)(Hunhandle *);

static void *g_lib = NULL;
static fn_create g_create = NULL;
static fn_destroy g_destroy = NULL;
static fn_spell g_spell = NULL;
static fn_suggest g_suggest = NULL;
static fn_free_list g_free_list = NULL;
static fn_add g_add = NULL;
static fn_encoding g_encoding = NULL;

static const char *const HUNSPELL_SONAMES[] = {
    "libhunspell-1.7.so.0",
    "libhunspell-1.7.so",
    "libhunspell.so.0",
    "libhunspell-1.6.so.0",
    "libhunspell.so",
    NULL
};

static int load_hunspell(void) {
    if (g_lib) return 1;
    for (int i = 0; HUNSPELL_SONAMES[i]; i++) {
        g_lib = dlopen(HUNSPELL_SONAMES[i], RTLD_LAZY | RTLD_LOCAL);
        if (g_lib) break;
    }
    if (!g_lib) return 0;

    g_create = (fn_create)dlsym(g_lib, "Hunspell_create");
    g_destroy = (fn_destroy)dlsym(g_lib, "Hunspell_destroy");
    g_spell = (fn_spell)dlsym(g_lib, "Hunspell_spell");
    g_suggest = (fn_suggest)dlsym(g_lib, "Hunspell_suggest");
    g_free_list = (fn_free_list)dlsym(g_lib, "Hunspell_free_list");
    g_add = (fn_add)dlsym(g_lib, "Hunspell_add");
    g_encoding = (fn_encoding)dlsym(g_lib, "Hunspell_get_dic_encoding");

    if (!g_create || !g_destroy || !g_spell || !g_suggest || !g_free_list || !g_add) {
        dlclose(g_lib);
        g_lib = NULL;
        g_create = NULL;
        return 0;
    }
    return 1;
}

static int encoding_is_utf8(const char *enc) {
    return enc == NULL || enc[0] == '\0' ||
           strcasecmp(enc, "UTF-8") == 0 ||
           strcasecmp(enc, "UTF8") == 0;
}

static char *iconv_convert(const char *from, const char *to, const char *input) {
    if (!input) return NULL;
    iconv_t cd = iconv_open(to, from);
    if (cd == (iconv_t)-1) return NULL;

    size_t inleft = strlen(input);
    size_t outcap = inleft * 4 + 4;
    char *out = (char *)malloc(outcap);
    if (!out) {
        iconv_close(cd);
        return NULL;
    }

    char *inptr = (char *)input;
    char *outptr = out;
    size_t outleft = outcap - 1;
    if (iconv(cd, &inptr, &inleft, &outptr, &outleft) == (size_t)-1) {
        free(out);
        iconv_close(cd);
        return NULL;
    }
    *outptr = '\0';
    iconv_close(cd);
    return out;
}

static const char *dict_encoding(Hunhandle *h) {
    if (!g_encoding || !h) return "UTF-8";
    char *enc = g_encoding(h);
    return (enc && enc[0]) ? enc : "UTF-8";
}

static char *to_dict(Hunhandle *h, const char *utf8) {
    const char *enc = dict_encoding(h);
    if (encoding_is_utf8(enc)) {
        return strdup(utf8);
    }
    char *converted = iconv_convert("UTF-8", enc, utf8);
    return converted ? converted : strdup(utf8);
}

static char *from_dict(Hunhandle *h, const char *encoded) {
    const char *enc = dict_encoding(h);
    if (encoding_is_utf8(enc)) {
        return strdup(encoded);
    }
    char *converted = iconv_convert(enc, "UTF-8", encoded);
    return converted ? converted : strdup(encoded);
}

static Hunhandle *handle_from_jlong(jlong handle) {
    return (Hunhandle *)(intptr_t)handle;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_spellcheck_linux_NativeSpellcheckBridge_nativeIsHunspellPresent(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return load_hunspell() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_spellcheck_linux_NativeSpellcheckBridge_nativeCreate(
    JNIEnv *env, jclass clazz, jstring affPath, jstring dicPath)
{
    (void)clazz;
    if (!load_hunspell() || !affPath || !dicPath) return 0;

    const char *aff = (*env)->GetStringUTFChars(env, affPath, NULL);
    const char *dic = (*env)->GetStringUTFChars(env, dicPath, NULL);
    if (!aff || !dic) {
        if (aff) (*env)->ReleaseStringUTFChars(env, affPath, aff);
        if (dic) (*env)->ReleaseStringUTFChars(env, dicPath, dic);
        return 0;
    }

    Hunhandle *h = g_create(aff, dic);
    (*env)->ReleaseStringUTFChars(env, affPath, aff);
    (*env)->ReleaseStringUTFChars(env, dicPath, dic);
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_spellcheck_linux_NativeSpellcheckBridge_nativeDestroy(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    Hunhandle *h = handle_from_jlong(handle);
    if (h && g_destroy) g_destroy(h);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_spellcheck_linux_NativeSpellcheckBridge_nativeSpell(
    JNIEnv *env, jclass clazz, jlong handle, jstring word)
{
    (void)clazz;
    Hunhandle *h = handle_from_jlong(handle);
    if (!h || !g_spell || !word) return JNI_FALSE;

    const char *utf8 = (*env)->GetStringUTFChars(env, word, NULL);
    if (!utf8) return JNI_FALSE;
    char *encoded = to_dict(h, utf8);
    (*env)->ReleaseStringUTFChars(env, word, utf8);
    if (!encoded) return JNI_FALSE;

    int ok = g_spell(h, encoded);
    free(encoded);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_spellcheck_linux_NativeSpellcheckBridge_nativeSuggest(
    JNIEnv *env, jclass clazz, jlong handle, jstring word)
{
    (void)clazz;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;

    Hunhandle *h = handle_from_jlong(handle);
    if (!h || !g_suggest || !g_free_list || !word) {
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    const char *utf8 = (*env)->GetStringUTFChars(env, word, NULL);
    if (!utf8) return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    char *encoded = to_dict(h, utf8);
    (*env)->ReleaseStringUTFChars(env, word, utf8);
    if (!encoded) return (*env)->NewObjectArray(env, 0, stringClass, NULL);

    char **list = NULL;
    int n = g_suggest(h, &list, encoded);
    free(encoded);
    if (n < 0) n = 0;

    jobjectArray result = (*env)->NewObjectArray(env, n, stringClass, NULL);
    if (result && list) {
        for (int i = 0; i < n; i++) {
            if (!list[i]) continue;
            char *utf = from_dict(h, list[i]);
            if (utf) {
                jstring js = (*env)->NewStringUTF(env, utf);
                if (js) (*env)->SetObjectArrayElement(env, result, i, js);
                free(utf);
            }
        }
    }
    if (list) g_free_list(h, &list, n);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_spellcheck_linux_NativeSpellcheckBridge_nativeAdd(
    JNIEnv *env, jclass clazz, jlong handle, jstring word)
{
    (void)clazz;
    Hunhandle *h = handle_from_jlong(handle);
    if (!h || !g_add || !word) return JNI_FALSE;

    const char *utf8 = (*env)->GetStringUTFChars(env, word, NULL);
    if (!utf8) return JNI_FALSE;
    char *encoded = to_dict(h, utf8);
    (*env)->ReleaseStringUTFChars(env, word, utf8);
    if (!encoded) return JNI_FALSE;

    int rc = g_add(h, encoded);
    free(encoded);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}
