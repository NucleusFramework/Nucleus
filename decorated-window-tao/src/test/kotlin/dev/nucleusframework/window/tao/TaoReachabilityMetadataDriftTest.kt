package dev.nucleusframework.window.tao

import java.io.File
import java.lang.reflect.Executable
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every method `reachability-metadata.json` registers must exist on its class
 * with exactly the listed parameter types. The JVM resolves JNI callbacks from
 * the classes themselves, so a stale entry passes every JVM test; in the
 * native image the lookup fails instead — `GetMethodID` returns null and the
 * callback is never installed (a key callback that gained a parameter took
 * keyboard input, and on Windows popups pointer input too, out of the image).
 */
class TaoReachabilityMetadataDriftTest {
    @Test
    fun `every registered method matches its class`() {
        @Suppress("UNCHECKED_CAST")
        val entries =
            (
                Json(
                    metadata().readText(),
                ).parse() as Map<String, Any?>
            )["reflection"] as List<Map<String, Any?>>
        var checked = 0
        val drift =
            entries.flatMap { entry ->
                val type = entry["type"] as String

                @Suppress("UNCHECKED_CAST")
                val methods = entry["methods"] as List<Map<String, Any?>>? ?: return@flatMap emptyList()
                val owner = Class.forName(type, false, javaClass.classLoader)
                methods.mapNotNull { method ->
                    checked++
                    val name = method["name"] as String

                    // No parameterTypes registers every overload of that name.
                    @Suppress("UNCHECKED_CAST")
                    val params = method["parameterTypes"] as List<String>?
                    val candidates = owner.executablesNamed(name)
                    if (candidates.any { params == null || it.parameterTypes.map(Class<*>::getTypeName) == params }) {
                        null
                    } else {
                        "$type.$name$params — the class has " +
                            candidates.map { it.parameterTypes.map(Class<*>::getTypeName) }
                    }
                }
            }
        assertTrue(checked > 0, "no method entries found in ${metadata()}")
        if (drift.isNotEmpty()) fail("reachability-metadata.json drifted:\n" + drift.joinToString("\n"))
    }

    /** Methods declared on the class or anything it inherits from, or constructors for `<init>`. */
    private fun Class<*>.executablesNamed(name: String): List<Executable> =
        if (name == "<init>") {
            declaredConstructors.toList()
        } else {
            generateSequence(listOf(this)) { level -> level.flatMap { listOfNotNull(it.superclass) + it.interfaces } }
                .takeWhile { it.isNotEmpty() }
                .flatten()
                .flatMap { it.declaredMethods.asSequence() }
                .filter { it.name == name }
                .toList()
        }

    private fun metadata(): File =
        listOf(File("src/main/resources"), File("decorated-window-tao/src/main/resources"))
            .map { File(it, METADATA_PATH) }
            .firstOrNull { it.isFile }
            ?: error("reachability-metadata.json not found from ${File(".").absolutePath}")

    /** Just enough JSON for the metadata file: objects, arrays, strings, literals. */
    private class Json(
        private val text: String,
    ) {
        private var pos = 0

        fun parse(): Any? {
            skipWhitespace()
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                else -> parseLiteral(c)
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>()
            pos++
            while (true) {
                skipWhitespace()
                if (text[pos] == '}') {
                    pos++
                    return result
                }
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parse()
                skipWhitespace()
                if (text[pos] == ',') pos++
            }
        }

        private fun parseArray(): List<Any?> {
            val result = mutableListOf<Any?>()
            pos++
            while (true) {
                skipWhitespace()
                if (text[pos] == ']') {
                    pos++
                    return result
                }
                result += parse()
                skipWhitespace()
                if (text[pos] == ',') pos++
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (text[pos] != '"') {
                if (text[pos] == '\\') pos++
                result.append(text[pos++])
            }
            pos++
            return result.toString()
        }

        private fun parseLiteral(first: Char): Any? {
            val start = pos
            while (pos < text.length && text[pos] !in ",}] \t\r\n") pos++
            return when (val word = text.substring(start, pos)) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> word.toDoubleOrNull() ?: error("unexpected '$first' at $start")
            }
        }

        private fun expect(c: Char) {
            check(text[pos] == c) { "expected '$c' at $pos" }
            pos++
        }

        private fun skipWhitespace() {
            while (text[pos].isWhitespace()) pos++
        }
    }

    private companion object {
        const val METADATA_PATH =
            "META-INF/native-image/dev.nucleusframework/nucleus.decorated-window-tao/" +
                "reachability-metadata.json"
    }
}
