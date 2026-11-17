/*
 * jni_utf8.h – jstring to standard UTF-8 conversion, shared by all JNI bridges.
 *
 * GetStringUTFChars hands back JNI's "modified UTF-8", not standard UTF-8: a
 * supplementary character (emoji, rare CJK, ...) comes out as its two UTF-16
 * surrogates encoded separately, 3 bytes each, and U+0000 comes out as 0xC0 0x80.
 * Native consumers that expect real UTF-8 then break: MultiByteToWideChar on
 * Windows substitutes replacement characters, and sd-bus on Linux rejects the
 * whole message with -EINVAL, which costs the entire menu, not just one label.
 *
 * Every bridge therefore reads UTF-16 with GetStringChars and encodes it here.
 */

#ifndef COMPOSENATIVETRAY_JNI_UTF8_H
#define COMPOSENATIVETRAY_JNI_UTF8_H

#include <jni.h>
#include <stdlib.h>
#include <stddef.h>
#include <stdint.h>

/* Encodes one code point into at most 4 bytes; returns the number written. */
static inline size_t jni_utf8_encode(uint32_t cp, char *out) {
    if (cp < 0x80) {
        out[0] = (char)cp;
        return 1;
    }
    if (cp < 0x800) {
        out[0] = (char)(0xC0 | (cp >> 6));
        out[1] = (char)(0x80 | (cp & 0x3F));
        return 2;
    }
    if (cp < 0x10000) {
        out[0] = (char)(0xE0 | (cp >> 12));
        out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char)(0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = (char)(0xF0 | (cp >> 18));
    out[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
    out[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
    out[3] = (char)(0x80 | (cp & 0x3F));
    return 4;
}

/*
 * Returns a freshly allocated, NUL-terminated standard UTF-8 copy of jstr, or
 * NULL when jstr is NULL or the allocation fails. Free it with free().
 *
 * Unpaired surrogates cannot be represented in UTF-8 and become U+FFFD, so the
 * result is always valid UTF-8 whatever the Java string contains. An embedded
 * U+0000 truncates the copy, since the consumers are all C string APIs.
 */
static inline char *jni_utf8_dup(JNIEnv *env, jstring jstr) {
    if (!jstr) return NULL;

    const jchar *utf16 = (*env)->GetStringChars(env, jstr, NULL);
    if (!utf16) return NULL;
    jsize units = (*env)->GetStringLength(env, jstr);

    /* Three bytes per UTF-16 unit is the worst case: a surrogate pair is two
     * units and costs four bytes, every other unit costs at most three. */
    char *out = (char *)malloc((size_t)units * 3 + 1);
    if (!out) {
        (*env)->ReleaseStringChars(env, jstr, utf16);
        return NULL;
    }

    size_t written = 0;
    for (jsize i = 0; i < units; i++) {
        uint32_t cp = utf16[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < units &&
            utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (utf16[++i] - 0xDC00);
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp == 0) break;
        written += jni_utf8_encode(cp, out + written);
    }
    out[written] = '\0';

    (*env)->ReleaseStringChars(env, jstr, utf16);
    return out;
}

#endif /* COMPOSENATIVETRAY_JNI_UTF8_H */
