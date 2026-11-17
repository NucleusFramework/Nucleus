package dev.nucleusframework.tray.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * 2.0.3 [NativeLibraryLoader] verbatim: fixed shared cache path + hash check +
 * `Files.move(REPLACE_EXISTING)`. Used by [Issue534ReproTest] as the "other
 * version" process.
 */
private object Legacy203NativeLibraryLoader {
    private const val RESOURCE_PREFIX = "composetray/native"
    private val loadedLibraries = mutableSetOf<String>()

    @Synchronized
    fun load(
        libraryName: String,
        callerClass: Class<*>,
    ): Boolean {
        if (libraryName in loadedLibraries) return true
        try {
            System.loadLibrary(libraryName)
            loadedLibraries += libraryName
            return true
        } catch (_: UnsatisfiedLinkError) {
            // Not on java.library.path
        }
        val file = extractToCache(libraryName, callerClass) ?: return false
        System.load(file.absolutePath)
        loadedLibraries += libraryName
        return true
    }

    private fun extractToCache(
        libraryName: String,
        callerClass: Class<*>,
    ): File? {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val arch = System.getProperty("os.arch") ?: ""
        val platform =
            if (arch.contains("aarch64") || arch.contains("arm")) "win32-arm64" else "win32-x86-64"
        val fileName = if (os.contains("win")) "$libraryName.dll" else return null
        val resourcePath = "$RESOURCE_PREFIX/$platform/$fileName"
        val resourceUrl = callerClass.classLoader?.getResource(resourcePath) ?: return null

        val base = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
        val cacheDir = File(base, "composetray/native/$platform")
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, fileName)

        val resourceHash = resourceUrl.openStream().use { it.sha256() }
        if (cachedFile.exists() && cachedFile.sha256() == resourceHash) {
            return cachedFile
        }

        val tmpFile = File(cacheDir, "$fileName.tmp")
        try {
            resourceUrl.openStream().use { input ->
                Files.copy(input, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(tmpFile.toPath(), cachedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            cachedFile.setExecutable(true)
        } finally {
            tmpFile.delete()
        }
        return cachedFile
    }
}

/**
 * Two-JVM helper: load WinTray the way ComposeNativeTray 2.0.3 did.
 *
 * `--hold` keeps the process alive (DLL mapped) until `--stop-file=` appears.
 */
object Issue534OldVersionMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val ok = Legacy203NativeLibraryLoader.load("nucleus_tray", Issue534OldVersionMain::class.java)
            println("LOAD_OK=$ok")
            System.out.flush()
            if ("--hold" in args) {
                val stopPath =
                    args.firstOrNull { it.startsWith("--stop-file=") }
                        ?.removePrefix("--stop-file=")
                        ?: error("--hold requires --stop-file=<path>")
                val stopFile = File(stopPath)
                println("HOLDING")
                System.out.flush()
                while (!stopFile.exists()) {
                    Thread.sleep(50)
                }
            }
        } catch (t: Throwable) {
            System.err.println("LOAD_FAIL=${t.javaClass.name}: ${t.message}")
            t.printStackTrace()
            exitProcess(1)
        }
    }
}
