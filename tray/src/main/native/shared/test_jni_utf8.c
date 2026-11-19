/*
 * test_jni_utf8.c – regression test for issue #441.
 *
 * jni_utf8_dup must produce standard UTF-8, not JNI's modified UTF-8: a
 * supplementary character has to come out as one 4-byte sequence, not as two
 * 3-byte surrogate sequences. Windows renders the latter as mojibake and
 * sd-bus rejects it outright, which drops the whole Linux menu.
 *
 * Runs without a JVM: a stub JNIEnv serves UTF-16 straight from the test.
 */

#include "jni_utf8.h"

#include <stdio.h>
#include <string.h>

/* ── Stub JNIEnv: a jstring is just a NUL-terminated jchar array ─────────── */

static jsize stub_GetStringLength(JNIEnv *env, jstring str) {
    (void)env;
    const jchar *s = (const jchar *)str;
    jsize n = 0;
    while (s[n]) n++;
    return n;
}

static const jchar *stub_GetStringChars(JNIEnv *env, jstring str, jboolean *isCopy) {
    (void)env;
    if (isCopy) *isCopy = JNI_FALSE;
    return (const jchar *)str;
}

static void stub_ReleaseStringChars(JNIEnv *env, jstring str, const jchar *chars) {
    (void)env; (void)str; (void)chars;
}

static JNIEnv *make_stub_env(struct JNINativeInterface_ *fns) {
    static JNIEnv env;
    memset(fns, 0, sizeof(*fns));
    fns->GetStringLength = stub_GetStringLength;
    fns->GetStringChars = stub_GetStringChars;
    fns->ReleaseStringChars = stub_ReleaseStringChars;
    env = fns;
    return &env;
}

/* ── Cases ──────────────────────────────────────────────────────────────── */

static int failures = 0;

static void expect(JNIEnv *env, const char *what, const jchar *utf16, const char *expected) {
    char *got = jni_utf8_dup(env, (jstring)utf16);
    if (!got || strcmp(got, expected) != 0) {
        fprintf(stderr, "FAIL: %s → \"%s\", expected \"%s\"\n",
                what, got ? got : "(null)", expected);
        failures++;
    }
    free(got);
}

int main(void) {
    struct JNINativeInterface_ fns;
    JNIEnv *env = make_stub_env(&fns);

    /* "Exit" – plain ASCII */
    const jchar ascii[] = { 'E', 'x', 'i', 't', 0 };
    expect(env, "ASCII", ascii, "Exit");

    /* "退出" – BMP, three bytes each; worked before the fix too */
    const jchar cjk[] = { 0x9000, 0x51FA, 0 };
    expect(env, "CJK", cjk, "\xE9\x80\x80\xE5\x87\xBA");

    /* "🚀 Launch" – U+1F680 is a surrogate pair and must become F0 9F 9A 80 */
    const jchar rocket[] = { 0xD83D, 0xDE80, ' ', 'L', 'a', 'u', 'n', 'c', 'h', 0 };
    expect(env, "supplementary", rocket, "\xF0\x9F\x9A\x80 Launch");

    /* U+20BB7 then U+1F525 – two consecutive surrogate pairs */
    const jchar pairs[] = { 0xD842, 0xDFB7, 0xD83D, 0xDD25, 0 };
    expect(env, "consecutive pairs", pairs, "\xF0\xA0\xAE\xB7\xF0\x9F\x94\xA5");

    /* A lone high surrogate has no UTF-8 form and becomes U+FFFD */
    const jchar lone_high[] = { 0xD83D, 'x', 0 };
    expect(env, "unpaired high surrogate", lone_high, "\xEF\xBF\xBDx");

    /* A lone low surrogate likewise */
    const jchar lone_low[] = { 'x', 0xDE80, 0 };
    expect(env, "unpaired low surrogate", lone_low, "x\xEF\xBF\xBD");

    /* U+00E9 and U+20AC cover the two- and three-byte boundaries */
    const jchar mixed[] = { 0x00E9, 0x20AC, 0 };
    expect(env, "two/three byte", mixed, "\xC3\xA9\xE2\x82\xAC");

    if (jni_utf8_dup(env, NULL) != NULL) {
        fprintf(stderr, "FAIL: NULL jstring did not map to NULL\n");
        failures++;
    }

    if (failures) return 1;
    printf("PASS: jni_utf8_dup emits standard UTF-8 for all cases\n");
    return 0;
}
