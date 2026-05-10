package dev.nucleusframework.nucleus.updater.exception

open class UpdateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class NetworkException(
    message: String,
    cause: Throwable? = null,
) : UpdateException(message, cause)

class ChecksumException(
    expected: String,
    actual: String,
) : UpdateException("SHA-512 mismatch: expected=$expected, actual=$actual")

class NoMatchingFileException(
    platform: String,
    arch: String,
    format: String,
) : UpdateException("No matching file for $platform/$arch/$format")

class ParseException(
    message: String,
) : UpdateException(message)
