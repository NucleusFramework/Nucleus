/**
 * JNI bridge for macOS NSSpellChecker.
 *
 * The shared spell checker is process-wide; we isolate sessions with
 * uniqueSpellDocumentTag so ignored-word lists cannot leak across
 * SpellcheckSession instances. User-added words stay in Kotlin (same
 * Nucleus user-dictionary file as Linux) — learnWord: is never called,
 * so tests cannot pollute ~/Library/Spelling.
 *
 * Linked frameworks: Cocoa (AppKit + Foundation)
 */

#import <Cocoa/Cocoa.h>
#include <jni.h>
#include <stdlib.h>

static NSLock *spell_lock(void) {
    static NSLock *lock;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        lock = [[NSLock alloc] init];
    });
    return lock;
}

static NSString *nsstring_from_jstring(JNIEnv *env, jstring js) {
    if (!js) return nil;
    jsize len = (*env)->GetStringLength(env, js);
    const jchar *chars = (*env)->GetStringChars(env, js, NULL);
    if (!chars) return nil;
    NSString *s = [[NSString alloc] initWithCharacters:(const unichar *)chars
                                                length:(NSUInteger)len];
    (*env)->ReleaseStringChars(env, js, chars);
    return s;
}

static jstring jstring_from_nsstring(JNIEnv *env, NSString *s) {
    if (!s) return NULL;
    NSUInteger len = [s length];
    if (len == 0) {
        return (*env)->NewString(env, NULL, 0);
    }
    unichar *buf = (unichar *)malloc(len * sizeof(unichar));
    if (!buf) return NULL;
    [s getCharacters:buf range:NSMakeRange(0, len)];
    jstring js = (*env)->NewString(env, (const jchar *)buf, (jsize)len);
    free(buf);
    return js;
}

static NSString *match_language(NSArray<NSString *> *available, NSString *cand) {
    if (!available || !cand || [cand length] == 0) return nil;

    for (NSString *lang in available) {
        if ([lang caseInsensitiveCompare:cand] == NSOrderedSame) return lang;
    }

    NSString *hyphen = [cand stringByReplacingOccurrencesOfString:@"_" withString:@"-"];
    NSString *underscore = [cand stringByReplacingOccurrencesOfString:@"-" withString:@"_"];
    for (NSString *alt in @[ hyphen, underscore ]) {
        if ([alt isEqualToString:cand]) continue;
        for (NSString *lang in available) {
            if ([lang caseInsensitiveCompare:alt] == NSOrderedSame) return lang;
        }
    }

    NSCharacterSet *seps = [NSCharacterSet characterSetWithCharactersInString:@"_-"];
    NSRange sep = [cand rangeOfCharacterFromSet:seps];
    NSString *prefix = (sep.location != NSNotFound) ? [cand substringToIndex:sep.location] : cand;
    NSString *prefixLower = [prefix lowercaseString];
    for (NSString *lang in available) {
        if ([lang caseInsensitiveCompare:prefix] == NSOrderedSame) return lang;
        NSString *lower = [lang lowercaseString];
        if ([lower hasPrefix:[prefixLower stringByAppendingString:@"_"]] ||
            [lower hasPrefix:[prefixLower stringByAppendingString:@"-"]]) {
            return lang;
        }
    }
    return nil;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_8;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_spellcheck_macos_NativeSpellcheckBridge_nativeIsAvailable(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    @autoreleasepool {
        [spell_lock() lock];
        NSSpellChecker *checker = [NSSpellChecker sharedSpellChecker];
        NSArray *languages = [checker availableLanguages];
        [spell_lock() unlock];
        return (checker != nil && languages.count > 0) ? JNI_TRUE : JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_spellcheck_macos_NativeSpellcheckBridge_nativeResolveLanguage(
    JNIEnv *env, jclass clazz, jobjectArray candidates)
{
    (void)clazz;
    @autoreleasepool {
        if (!candidates) return NULL;
        [spell_lock() lock];
        NSArray<NSString *> *available = [[NSSpellChecker sharedSpellChecker] availableLanguages];
        [spell_lock() unlock];
        if (!available) return NULL;

        jsize n = (*env)->GetArrayLength(env, candidates);
        for (jsize i = 0; i < n; i++) {
            jstring jc = (jstring)(*env)->GetObjectArrayElement(env, candidates, i);
            NSString *cand = nsstring_from_jstring(env, jc);
            if (jc) (*env)->DeleteLocalRef(env, jc);
            NSString *matched = match_language(available, cand);
            if (matched) {
                return jstring_from_nsstring(env, matched);
            }
        }
        return NULL;
    }
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_spellcheck_macos_NativeSpellcheckBridge_nativeCreateDocument(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    @autoreleasepool {
        [spell_lock() lock];
        NSInteger tag = [NSSpellChecker uniqueSpellDocumentTag];
        [spell_lock() unlock];
        return (jlong)tag;
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_spellcheck_macos_NativeSpellcheckBridge_nativeDestroyDocument(
    JNIEnv *env, jclass clazz, jlong tag)
{
    (void)env;
    (void)clazz;
    @autoreleasepool {
        [spell_lock() lock];
        [[NSSpellChecker sharedSpellChecker] closeSpellDocumentWithTag:(NSInteger)tag];
        [spell_lock() unlock];
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_spellcheck_macos_NativeSpellcheckBridge_nativeSpell(
    JNIEnv *env, jclass clazz, jlong tag, jstring language, jstring word)
{
    (void)clazz;
    @autoreleasepool {
        NSString *nsWord = nsstring_from_jstring(env, word);
        NSString *nsLang = nsstring_from_jstring(env, language);
        if (!nsWord || [nsWord length] == 0) return JNI_FALSE;

        [spell_lock() lock];
        NSRange range = [[NSSpellChecker sharedSpellChecker]
            checkSpellingOfString:nsWord
                       startingAt:0
                         language:nsLang
                             wrap:NO
         inSpellDocumentWithTag:(NSInteger)tag
                       wordCount:NULL];
        [spell_lock() unlock];
        return range.location == NSNotFound ? JNI_TRUE : JNI_FALSE;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_spellcheck_macos_NativeSpellcheckBridge_nativeSuggest(
    JNIEnv *env, jclass clazz, jlong tag, jstring language, jstring word)
{
    (void)clazz;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;

    @autoreleasepool {
        NSString *nsWord = nsstring_from_jstring(env, word);
        NSString *nsLang = nsstring_from_jstring(env, language);
        if (!nsWord || [nsWord length] == 0) {
            return (*env)->NewObjectArray(env, 0, stringClass, NULL);
        }

        NSRange full = NSMakeRange(0, [nsWord length]);
        [spell_lock() lock];
        NSArray<NSString *> *guesses = [[NSSpellChecker sharedSpellChecker]
            guessesForWordRange:full
                       inString:nsWord
                       language:nsLang
       inSpellDocumentWithTag:(NSInteger)tag];
        [spell_lock() unlock];

        jsize n = guesses ? (jsize)guesses.count : 0;
        jobjectArray result = (*env)->NewObjectArray(env, n, stringClass, NULL);
        if (!result || !guesses) return result;
        for (jsize i = 0; i < n; i++) {
            jstring js = jstring_from_nsstring(env, guesses[(NSUInteger)i]);
            if (js) {
                (*env)->SetObjectArrayElement(env, result, i, js);
                (*env)->DeleteLocalRef(env, js);
            }
        }
        return result;
    }
}
