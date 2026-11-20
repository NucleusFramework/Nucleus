package dev.nucleusframework.authentication

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bridge passes enums by ordinal: the Rust mirrors must list them in the same order. */
class NativeMirrorTest {
    private val native = File("src/main/native/src")

    @Test
    fun errorCodesFollowAuthenticationError() {
        assertEquals(AuthenticationError.entries.map { it.name }, variants(File(native, "error.rs"), "Error"))
    }

    @Test
    fun biometryFollowsBiometryType() {
        assertEquals(BiometryType.entries.map { it.name }, variants(File(native, "lib.rs"), "Biometry"))
    }

    @Test
    fun policyFollowsAuthenticationPolicy() {
        assertEquals(AuthenticationPolicy.entries.map { it.name }, variants(File(native, "lib.rs"), "Policy"))
    }

    /** The variant names of `enum <name> { … }` in [file], in declaration order. */
    private fun variants(
        file: File,
        name: String,
    ): List<String> {
        val body =
            file
                .readText()
                .substringAfter("enum $name {")
                .substringBefore("\n}")
        return body
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .map {
                it
                    .substringBefore(" ")
                    .substringBefore(",")
                    .substringBefore("=")
                    .trim()
            }
    }
}
