package dev.nucleusframework.updater.exception

public open class UpdateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

public class NetworkException(
    message: String,
    cause: Throwable? = null,
) : UpdateException(message, cause)

public class ChecksumException(
    expected: String,
    actual: String,
) : UpdateException("SHA-512 mismatch: expected=$expected, actual=$actual")

public class NoMatchingFileException(
    platform: String,
    arch: String,
    format: String,
) : UpdateException("No matching file for $platform/$arch/$format")

public class ParseException(
    message: String,
) : UpdateException(message)
