package dev.nucleusframework.core.runtime

import dev.nucleusframework.core.runtime.tools.NucleusLoggingLevel
import dev.nucleusframework.core.runtime.tools.allowNucleusRuntimeLogging
import dev.nucleusframework.core.runtime.tools.debugln
import dev.nucleusframework.core.runtime.tools.errorln
import dev.nucleusframework.core.runtime.tools.infoln
import dev.nucleusframework.core.runtime.tools.nucleusLoggingLevel
import dev.nucleusframework.core.runtime.tools.verboseln
import dev.nucleusframework.core.runtime.tools.warnln
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleusLoggingTest {
    private val previousAllowed = allowNucleusRuntimeLogging
    private val previousLevel = nucleusLoggingLevel

    @After
    fun restoreLogging() {
        nucleusLoggingLevel = previousLevel
        allowNucleusRuntimeLogging = previousAllowed
    }

    @Test
    fun `logging levels are ordered from verbose to error`() {
        assertTrue(NucleusLoggingLevel.VERBOSE < NucleusLoggingLevel.DEBUG)
        assertTrue(NucleusLoggingLevel.DEBUG < NucleusLoggingLevel.INFO)
        assertTrue(NucleusLoggingLevel.INFO < NucleusLoggingLevel.WARN)
        assertTrue(NucleusLoggingLevel.WARN < NucleusLoggingLevel.ERROR)
        assertEquals("VERBOSE", NucleusLoggingLevel.VERBOSE.toString())
        assertEquals("ERROR", NucleusLoggingLevel.ERROR.toString())
    }

    @Test
    fun `opt-in logging can be toggled and emits without throwing`() {
        nucleusLoggingLevel = NucleusLoggingLevel.VERBOSE
        allowNucleusRuntimeLogging = true
        verboseln { "verbose" }
        debugln { "debug" }
        infoln { "info" }
        warnln { "warn" }
        errorln { "error" }

        allowNucleusRuntimeLogging = false
        debugln { "still safe after disable" }
        assertEquals(NucleusLoggingLevel.VERBOSE, nucleusLoggingLevel)
    }
}
