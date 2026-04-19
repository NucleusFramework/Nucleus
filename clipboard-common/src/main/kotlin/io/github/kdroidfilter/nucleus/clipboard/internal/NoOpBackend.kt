package io.github.kdroidfilter.nucleus.clipboard.internal

import io.github.kdroidfilter.nucleus.clipboard.AccessBehavior
import io.github.kdroidfilter.nucleus.clipboard.ClipboardFormat
import java.nio.file.Path

@Suppress("TooManyFunctions")
internal object NoOpBackend : ClipboardBackend {
    override val name: String = "no-op"

    override fun isAvailable(): Boolean = false

    override suspend fun readText(): String? = null

    override suspend fun readHtml(): String? = null

    override suspend fun readRtf(): String? = null

    override suspend fun readImagePng(): ByteArray? = null

    override suspend fun readFiles(): List<Path> = emptyList()

    override suspend fun availableFormats(): Set<ClipboardFormat> = emptySet()

    override suspend fun write(payload: ClipboardWritePayload): Boolean = false

    override suspend fun clear(): Boolean = false

    override fun changeCount(): Long = 0L

    override fun setAccessBehavior(behavior: AccessBehavior) = Unit

    override fun accessBehavior(): AccessBehavior? = null

    override fun isAccessBehaviorSupported(): Boolean = false
}
