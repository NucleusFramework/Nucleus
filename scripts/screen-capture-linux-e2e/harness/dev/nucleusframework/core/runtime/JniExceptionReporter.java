package dev.nucleusframework.core.runtime;

/** Stand-in for core-runtime's reporter, which nucleus_jni_clear_exception() calls. */
public final class JniExceptionReporter {
    public static volatile int reported = 0;

    public static void report(Throwable t) {
        reported++;
        System.out.println("JniExceptionReporter: " + t);
    }
}
