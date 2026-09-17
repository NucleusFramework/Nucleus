package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlProfileInferenceArgsTest {
    @Test
    fun `enabled leaves args empty on any toolchain`() {
        listOf(true, false).forEach { oracle ->
            val resolution =
                resolveMlProfileInferenceArgs(
                    mlProfileInference = true,
                    isOracleGraalvm = oracle,
                    graalvmHome = "/opt/graalvm",
                )
            assertEquals(emptyList<String>(), resolution.args)
            assertNull(resolution.warning)
        }
    }

    @Test
    fun `disabled emits the opt-out flag on Oracle GraalVM`() {
        val resolution =
            resolveMlProfileInferenceArgs(
                mlProfileInference = false,
                isOracleGraalvm = true,
                graalvmHome = "/opt/graalvm-oracle",
            )
        assertEquals(listOf("-H:-MLProfileInference"), resolution.args)
        assertNull(resolution.warning)
    }

    @Test
    fun `disabled drops the flag and warns on a community toolchain`() {
        val resolution =
            resolveMlProfileInferenceArgs(
                mlProfileInference = false,
                isOracleGraalvm = false,
                graalvmHome = "/opt/graalvm-ce",
            )
        assertEquals(emptyList<String>(), resolution.args)
        val warning = requireNotNull(resolution.warning)
        assertTrue(warning.contains("mlProfileInference = false ignored"))
        assertTrue(warning.contains("requires Oracle GraalVM"))
        assertTrue(warning.contains("/opt/graalvm-ce"))
    }
}
