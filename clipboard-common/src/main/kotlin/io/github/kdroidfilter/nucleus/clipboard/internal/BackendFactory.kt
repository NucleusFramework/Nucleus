package io.github.kdroidfilter.nucleus.clipboard.internal

import java.util.ServiceLoader
import java.util.logging.Level
import java.util.logging.Logger

internal object BackendFactory {
    private val logger = Logger.getLogger(BackendFactory::class.java.simpleName)

    fun discover(): ClipboardBackend {
        val loader = ServiceLoader.load(ClipboardBackend::class.java, ClipboardBackend::class.java.classLoader)
        for (candidate in loader) {
            try {
                if (candidate.isAvailable()) {
                    logger.fine("Clipboard backend selected: ${candidate.name}")
                    return candidate
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                logger.log(Level.WARNING, "Clipboard backend ${candidate.name} failed probe", e)
            }
        }
        logger.fine("No clipboard backend available — falling back to no-op")
        return NoOpBackend
    }
}
