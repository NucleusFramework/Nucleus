#ifndef NUCLEUS_JNI_H
#define NUCLEUS_JNI_H

#include <jni.h>

/*
 * Shared JNI helpers for every Nucleus native bridge.
 *
 * Pending exceptions must never be ExceptionClear'd silently: a Kotlin
 * listener that throws would otherwise vanish with no log line. Call
 * nucleus_jni_clear_exception() instead; it reports through
 * JniExceptionReporter (JUL) and falls back to ExceptionDescribe.
 */

#ifdef __cplusplus
#define NUCLEUS_JNI_EXCEPTION_CHECK(env) ((env)->ExceptionCheck())
#define NUCLEUS_JNI_EXCEPTION_OCCURRED(env) ((env)->ExceptionOccurred())
#define NUCLEUS_JNI_EXCEPTION_CLEAR(env) ((env)->ExceptionClear())
#define NUCLEUS_JNI_EXCEPTION_DESCRIBE(env) ((env)->ExceptionDescribe())
#define NUCLEUS_JNI_EXCEPTION_THROW(env, thrown) ((env)->Throw(thrown))
#define NUCLEUS_JNI_FIND_CLASS(env, name) ((env)->FindClass(name))
#define NUCLEUS_JNI_GET_STATIC_METHOD_ID(env, cls, name, sig) \
    ((env)->GetStaticMethodID(cls, name, sig))
#define NUCLEUS_JNI_CALL_STATIC_VOID_METHOD(env, cls, mid, arg) \
    ((env)->CallStaticVoidMethod(cls, mid, arg))
#define NUCLEUS_JNI_DELETE_LOCAL_REF(env, ref) ((env)->DeleteLocalRef(ref))
#else
#define NUCLEUS_JNI_EXCEPTION_CHECK(env) ((*(env))->ExceptionCheck(env))
#define NUCLEUS_JNI_EXCEPTION_OCCURRED(env) ((*(env))->ExceptionOccurred(env))
#define NUCLEUS_JNI_EXCEPTION_CLEAR(env) ((*(env))->ExceptionClear(env))
#define NUCLEUS_JNI_EXCEPTION_DESCRIBE(env) ((*(env))->ExceptionDescribe(env))
#define NUCLEUS_JNI_EXCEPTION_THROW(env, thrown) ((*(env))->Throw(env, thrown))
#define NUCLEUS_JNI_FIND_CLASS(env, name) ((*(env))->FindClass(env, name))
#define NUCLEUS_JNI_GET_STATIC_METHOD_ID(env, cls, name, sig) \
    ((*(env))->GetStaticMethodID(env, cls, name, sig))
#define NUCLEUS_JNI_CALL_STATIC_VOID_METHOD(env, cls, mid, arg) \
    ((*(env))->CallStaticVoidMethod(env, cls, mid, arg))
#define NUCLEUS_JNI_DELETE_LOCAL_REF(env, ref) ((*(env))->DeleteLocalRef(env, ref))
#endif

#define NUCLEUS_JNI_REPORTER_CLASS "dev/nucleusframework/core/runtime/JniExceptionReporter"
#define NUCLEUS_JNI_REPORTER_METHOD "report"
#define NUCLEUS_JNI_REPORTER_SIGNATURE "(Ljava/lang/Throwable;)V"

/**
 * If a JNI exception is pending, report it and clear it so native code can
 * continue. Returns JNI_TRUE when an exception was present.
 */
static inline jboolean nucleus_jni_clear_exception(JNIEnv *env) {
    if (env == NULL || !NUCLEUS_JNI_EXCEPTION_CHECK(env)) {
        return JNI_FALSE;
    }

    jthrowable thrown = NUCLEUS_JNI_EXCEPTION_OCCURRED(env);
    NUCLEUS_JNI_EXCEPTION_CLEAR(env);

    jclass reporter = NUCLEUS_JNI_FIND_CLASS(env, NUCLEUS_JNI_REPORTER_CLASS);
    if (reporter == NULL) {
        NUCLEUS_JNI_EXCEPTION_CLEAR(env);
        if (thrown != NULL) {
            NUCLEUS_JNI_EXCEPTION_THROW(env, thrown);
            NUCLEUS_JNI_EXCEPTION_DESCRIBE(env);
            NUCLEUS_JNI_EXCEPTION_CLEAR(env);
            NUCLEUS_JNI_DELETE_LOCAL_REF(env, thrown);
        }
        return JNI_TRUE;
    }

    jmethodID report = NUCLEUS_JNI_GET_STATIC_METHOD_ID(
        env,
        reporter,
        NUCLEUS_JNI_REPORTER_METHOD,
        NUCLEUS_JNI_REPORTER_SIGNATURE
    );
    if (report == NULL) {
        NUCLEUS_JNI_EXCEPTION_CLEAR(env);
        NUCLEUS_JNI_DELETE_LOCAL_REF(env, reporter);
        if (thrown != NULL) {
            NUCLEUS_JNI_EXCEPTION_THROW(env, thrown);
            NUCLEUS_JNI_EXCEPTION_DESCRIBE(env);
            NUCLEUS_JNI_EXCEPTION_CLEAR(env);
            NUCLEUS_JNI_DELETE_LOCAL_REF(env, thrown);
        }
        return JNI_TRUE;
    }

    NUCLEUS_JNI_CALL_STATIC_VOID_METHOD(env, reporter, report, thrown);
    if (NUCLEUS_JNI_EXCEPTION_CHECK(env)) {
        NUCLEUS_JNI_EXCEPTION_CLEAR(env);
        if (thrown != NULL) {
            NUCLEUS_JNI_EXCEPTION_THROW(env, thrown);
            NUCLEUS_JNI_EXCEPTION_DESCRIBE(env);
            NUCLEUS_JNI_EXCEPTION_CLEAR(env);
        }
    }

    NUCLEUS_JNI_DELETE_LOCAL_REF(env, reporter);
    if (thrown != NULL) {
        NUCLEUS_JNI_DELETE_LOCAL_REF(env, thrown);
    }
    return JNI_TRUE;
}

#endif /* NUCLEUS_JNI_H */
