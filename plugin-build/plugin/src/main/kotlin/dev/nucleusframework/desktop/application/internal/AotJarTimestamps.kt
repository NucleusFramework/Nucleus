package dev.nucleusframework.desktop.application.internal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

/**
 * Keeps the classpath JARs' modification times stable between the AOT training run and the
 * installed app.
 *
 * The JDK's AOT cache records each classpath JAR's size and modification time and refuses the whole
 * cache when either changed (enforced since JDK-8377932: 25.0.4, 26.0.2, 27). electron-builder
 * builds the NSIS payload with `7z -mtm=off`, so every installed file gets the install time as its
 * modification time and the cache never applied to an NSIS install. The JARs are therefore pinned
 * to [INSTANT] before training ([normalize]) and pinned again by the installer ([nsisMacros]) —
 * the approach the Paketo buildpacks take for container images.
 */
internal object AotJarTimestamps {
    /** 1980-01-01T00:00:02Z: the ZIP epoch, on an even second so a DOS timestamp can hold it too. */
    val INSTANT: Instant = Instant.parse("1980-01-01T00:00:02Z")

    /** Name of the NSIS macro that pins the installed JARs, inserted from `customInstall`. */
    const val NSIS_MACRO = "nucleusPinAotJarTimestamps"

    /** 100-ns intervals between the FILETIME epoch (1601-01-01) and the Unix epoch. */
    private const val FILETIME_UNIX_EPOCH = 116_444_736_000_000_000L
    private const val FILETIME_TICKS_PER_SECOND = 10_000_000L
    private const val INT_BITS = 32
    private const val LOW_INT_MASK = 0xFFFFFFFFL

    /** Pins the modification time of every JAR in [appJarDir] to [INSTANT]. */
    fun normalize(appJarDir: File) {
        val time = FileTime.from(INSTANT)
        appJarDir
            .listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            .orEmpty()
            .forEach { Files.setLastModifiedTime(it.toPath(), time) }
    }

    /** [INSTANT] as a Windows FILETIME. */
    fun fileTime(): Long = FILETIME_UNIX_EPOCH + INSTANT.epochSecond * FILETIME_TICKS_PER_SECOND

    /**
     * The NSIS macro [NSIS_MACRO], pinning the JARs under `$INSTDIR\<jarDir>` with `SetFileTime`
     * through NSIS's bundled System plugin. [jarDir] is relative to the install directory.
     */
    fun nsisMacros(jarDir: String): String {
        val fileTime = fileTime()
        val low = fileTime and LOW_INT_MASK
        val high = fileTime ushr INT_BITS
        val dir = "\$INSTDIR\\$jarDir"
        return """
            |!macro $NSIS_MACRO
            |  Push ${'$'}0
            |  Push ${'$'}1
            |  Push ${'$'}2
            |  Push ${'$'}3
            |  System::Call '*(i $low, i $high) p .r2'
            |  FindFirst ${'$'}0 ${'$'}1 "$dir\*.jar"
            |  nucleus_aot_jar_loop:
            |    StrCmp ${'$'}1 "" nucleus_aot_jar_done
            |    System::Call 'kernel32::CreateFileW(w "$dir\${'$'}1", i 0x100, i 7, p 0, i 3, i 0x80, p 0) p .r3'
            |    System::Call 'kernel32::SetFileTime(p r3, p 0, p 0, p r2)'
            |    System::Call 'kernel32::CloseHandle(p r3)'
            |    FindNext ${'$'}0 ${'$'}1
            |    Goto nucleus_aot_jar_loop
            |  nucleus_aot_jar_done:
            |  FindClose ${'$'}0
            |  System::Free ${'$'}2
            |  Pop ${'$'}3
            |  Pop ${'$'}2
            |  Pop ${'$'}1
            |  Pop ${'$'}0
            |!macroend
            |
        """.trimMargin()
    }
}
