package dev.nucleusframework.core.runtime

import java.util.logging.Level
import java.util.logging.Logger

/**
 * JUL sink for pending JNI exceptions that native code has to clear before it
 * can continue. Native bridges call this through `nucleus_jni_clear_exception`
 * in `native-common/nucleus_jni.h`; without it a Kotlin listener that throws
 * from a JNI upcall vanishes with no log line.
 */
internal object JniExceptionReporter {
    private val logger = Logger.getLogger(JniExceptionReporter::class.java.name)

    @JvmStatic
    fun report(thrown: Throwable?) {
        if (thrown == null) return
        logger.log(
            Level.WARNING,
            "Native JNI callback cleared a pending Kotlin exception",
            thrown,
        )
    }
}
