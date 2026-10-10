package dev.nucleusframework.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards issue #486: native bridges must report a pending JNI exception
 * through [JniExceptionReporter] before clearing it. `ExceptionClear` /
 * `ExceptionDescribe` live only in `native-common/nucleus_jni.h`.
 */
class NativeJniExceptionHygieneTest {
    private val nativeExts = setOf("c", "m", "h", "cpp", "mm")
    private val skipDirs = setOf("vendor", "target", ".git", "build")

    @Test
    fun `shared helper reports through JniExceptionReporter then clears`() {
        val header = File(repoRoot(), "native-common/nucleus_jni.h")
        assertTrue("missing ${header.path}", header.isFile)
        val text = header.readText()
        assertTrue(text.contains("JniExceptionReporter"))
        assertTrue(text.contains("ExceptionOccurred"))
        assertTrue(text.contains("ExceptionDescribe"))
        assertTrue(text.contains("ExceptionClear"))
        assertTrue(text.contains("nucleus_jni_clear_exception"))
    }

    @Test
    fun `native sources do not silently ExceptionClear`() {
        val root = repoRoot()
        val violations = mutableListOf<String>()
        nativeFiles(root).forEach { file ->
            val rel = file.relativeTo(root).path
            if (rel.replace('\\', '/') == "native-common/nucleus_jni.h") return@forEach
            file.readLines().forEachIndexed { index, line ->
                if ("ExceptionClear" in line || "ExceptionDescribe" in line) {
                    violations += "$rel:${index + 1}: $line"
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "JNI ExceptionClear/ExceptionDescribe must go through " +
                    "nucleus_jni_clear_exception (issue #486):\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `native sources that check exceptions include the shared helper`() {
        val root = repoRoot()
        val missing = mutableListOf<String>()
        nativeFiles(root).forEach { file ->
            val rel = file.relativeTo(root).path.replace('\\', '/')
            if (rel == "native-common/nucleus_jni.h") return@forEach
            val text = file.readText()
            if ("ExceptionCheck" in text && "nucleus_jni.h" !in text) {
                missing += rel
            }
        }
        assertFalse(
            "files with ExceptionCheck must include nucleus_jni.h:\n${missing.joinToString("\n")}",
            missing.isNotEmpty(),
        )
    }

    private fun nativeFiles(root: File): Sequence<File> =
        root
            .walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && it.extension in nativeExts }
            .filter { "/src/main/native/" in it.path.replace('\\', '/') || it.name == "nucleus_jni.h" }

    private fun repoRoot(): File {
        val cwd = File("").absoluteFile
        val candidates = listOfNotNull(cwd, cwd.parentFile)
        return candidates.firstOrNull { dir ->
            File(dir, "settings.gradle.kts").isFile && File(dir, "core-runtime").isDirectory
        } ?: error("cannot locate repository root from $cwd")
    }
}
