package dev.nucleusframework.tray.utils

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlin.io.path.createTempFile

/**
 * Extracts a specified file or entry from a JAR archive to a temporary file.
 * If the specified file or entry is already extracted and matches the content, the existing temporary file is returned.
 * Otherwise, a new temporary file is created with the extracted content.
 *
 * @param jarPath The path to the JAR file and entry to extract. It should be in the format
 *                "jar:file:/path/to/jarfile.jar!/path/inside/jar".
 * @return A temporary file containing the extracted content, or null if the entry does not exist in the JAR.
 * @throws FileNotFoundException If the JAR file does not exist.
 */
internal fun extractToTempIfDifferent(jarPath: String): File? {
    // Analyze the path to get the file path and the entry path
    val correctedJarFilePath =
        URLDecoder.decode(jarPath.substringAfter("jar:file:").substringBefore("!"), Charsets.UTF_8.name())

    // Encode special characters to be URI compatible
    val encodedJarFilePath = correctedJarFilePath.replace(" ", "%20")

    // Convert the path to File via URI
    val jarFile =
        try {
            File(URI(encodedJarFilePath))
        } catch (e: IllegalArgumentException) {
            File(correctedJarFilePath.removePrefix("file:"))
        }

    // Check if the file exists
    if (!jarFile.exists()) {
        throw FileNotFoundException("File does not exist: $correctedJarFilePath")
    }

    val entryPath = jarPath.substringAfter("!").trimStart('/')

    // If the file is not a JAR, handle it differently
    if (!correctedJarFilePath.endsWith(".jar")) {
        val tempFile =
            createTempFile("extracted_", ".tmp").toFile().apply {
                deleteOnExit()
            }

        // Copy the file directly if it is not a JAR
        Files.copy(jarFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return tempFile
    }

    // Open the JAR from the absolute path
    JarFile(jarFile).use { jar ->
        val entry = jar.getJarEntry(entryPath) ?: return null

        // Create a temporary file to store the extracted resource
        val tempFile =
            createTempFile("extracted_", ".tmp").toFile().apply {
                deleteOnExit()
            }

        // Check if the temporary file already exists and compare the hash
        if (tempFile.exists()) {
            val tempFileHash = tempFile.sha256()
            jar.getInputStream(entry).use { input ->
                val jarEntryHash = input.sha256()
                // If the hash is identical, no need to copy again
                if (tempFileHash == jarEntryHash) {
                    return tempFile
                }
            }
        }

        // Copy the content of the JAR entry to the temporary file
        jar.getInputStream(entry).use { input ->
            Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        return tempFile
    }
}

// Extension to calculate SHA-256 of a file
internal fun File.sha256(): String = inputStream().use { it.sha256() }

// Extension to calculate SHA-256 of an InputStream
internal fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024)
    var bytesRead: Int
    while (this.read(buffer).also { bytesRead = it } != -1) {
        digest.update(buffer, 0, bytesRead)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
