package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class JniExceptionReporterTest {
    @Test
    fun `report logs the throwable at warning`() {
        val logger = Logger.getLogger(JniExceptionReporter::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val previousLevel = logger.level
        val previousUseParent = logger.useParentHandlers
        logger.addHandler(handler)
        logger.useParentHandlers = false
        logger.level = Level.ALL
        try {
            val boom = IllegalStateException("listener failed")
            JniExceptionReporter.report(boom)
            assertEquals(1, records.size)
            assertEquals(Level.WARNING, records[0].level)
            assertSame(boom, records[0].thrown)
            assertTrue(records[0].message.contains("JNI"))
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousUseParent
        }
    }

    @Test
    fun `report ignores null`() {
        val logger = Logger.getLogger(JniExceptionReporter::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        logger.useParentHandlers = false
        logger.level = Level.ALL
        try {
            JniExceptionReporter.report(null)
            assertTrue(records.isEmpty())
        } finally {
            logger.removeHandler(handler)
        }
    }
}
