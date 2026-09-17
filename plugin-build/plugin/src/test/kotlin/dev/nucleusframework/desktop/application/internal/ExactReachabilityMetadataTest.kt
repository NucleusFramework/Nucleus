package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.ExactReachabilityMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExactReachabilityMetadataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `package prefix strips the simple class name`() {
        assertEquals("com.example.demo", packagePrefixOfMainClass("com.example.demo.MainKt"))
        assertEquals("com.example", packagePrefixOfMainClass("com.example.Main"))
    }

    @Test
    fun `package prefix is null for the default package or blank main class`() {
        assertNull(packagePrefixOfMainClass("MainKt"))
        assertNull(packagePrefixOfMainClass(""))
        assertNull(packagePrefixOfMainClass(null))
        assertNull(packagePrefixOfMainClass("."))
    }

    @Test
    fun `APP_PACKAGES resolves to the main class package`() {
        val (packages, warning) =
            resolveExactReachabilityPackages(
                ExactReachabilityMetadata.APP_PACKAGES,
                "com.example.demo.MainKt",
            )
        assertEquals(listOf("com.example.demo"), packages)
        assertNull(warning)
    }

    @Test
    fun `APP_PACKAGES without a package warns and yields nothing`() {
        val (packages, warning) =
            resolveExactReachabilityPackages(ExactReachabilityMetadata.APP_PACKAGES, "MainKt")
        assertTrue(packages.isEmpty())
        assertTrue(warning!!.contains("no package"))
    }

    @Test
    fun `explicit packages are cleaned and de-duplicated`() {
        val setting =
            ExactReachabilityMetadata.packages(
                " io.github.acme ",
                "com.acme.shared.",
                "io.github.acme",
                "",
                "  ",
            )
        val (packages, warning) = resolveExactReachabilityPackages(setting, "ignored.Main")
        assertEquals(listOf("io.github.acme", "com.acme.shared"), packages)
        assertNull(warning)
    }

    @Test
    fun `OFF never resolves packages`() {
        val (packages, warning) =
            resolveExactReachabilityPackages(ExactReachabilityMetadata.OFF, "com.example.Main")
        assertTrue(packages.isEmpty())
        assertNull(warning)
    }

    @Test
    fun `distributable build never emits exact reachability args`() {
        val javaHome = fakeJavaHome(feature = 25)
        val resolution =
            resolveExactReachabilityMetadata(
                setting = ExactReachabilityMetadata.APP_PACKAGES,
                mainClassName = "com.example.demo.MainKt",
                quickBuild = false,
                javaHome = javaHome,
                reportingMode = MissingRegistrationReportingMode.WARN,
            )
        assertTrue(resolution.buildArgs.isEmpty())
        assertTrue(resolution.runtimeArgs.isEmpty())
        assertNull(resolution.warning)
        assertNull(resolution.lifecycleMessage)
    }

    @Test
    fun `quick build on JDK 23+ emits scoped exact-reachability-metadata`() {
        val javaHome = fakeJavaHome(feature = 25)
        val resolution =
            resolveExactReachabilityMetadata(
                setting = ExactReachabilityMetadata.APP_PACKAGES,
                mainClassName = "com.example.demo.MainKt",
                quickBuild = true,
                javaHome = javaHome,
                reportingMode = MissingRegistrationReportingMode.WARN,
            )
        assertEquals(
            listOf("--exact-reachability-metadata=com.example.demo"),
            resolution.buildArgs,
        )
        assertEquals(
            listOf("-XX:MissingRegistrationReportingMode=Warn"),
            resolution.runtimeArgs,
        )
        assertEquals(listOf("com.example.demo"), resolution.packages)
        assertNull(resolution.warning)
        assertTrue(resolution.lifecycleMessage!!.contains("--exact-reachability-metadata=com.example.demo"))
    }

    @Test
    fun `quick build on pre-23 falls back to ThrowMissingRegistrationErrors`() {
        val javaHome = fakeJavaHome(feature = 21)
        val resolution =
            resolveExactReachabilityMetadata(
                setting = ExactReachabilityMetadata.packages("io.acme", "com.acme"),
                mainClassName = "io.acme.Main",
                quickBuild = true,
                javaHome = javaHome,
                reportingMode = MissingRegistrationReportingMode.EXIT,
            )
        assertEquals(
            listOf("-H:ThrowMissingRegistrationErrors=io.acme,com.acme"),
            resolution.buildArgs,
        )
        assertEquals(
            listOf("-XX:MissingRegistrationReportingMode=Exit"),
            resolution.runtimeArgs,
        )
        val warning = requireNotNull(resolution.warning)
        assertTrue(warning.contains("JDK 21"))
        assertTrue(warning.contains("ThrowMissingRegistrationErrors"))
    }

    @Test
    fun `unreadable release file skips with a warning`() {
        val javaHome = tmp.newFolder("no-release")
        val resolution =
            resolveExactReachabilityMetadata(
                setting = ExactReachabilityMetadata.APP_PACKAGES,
                mainClassName = "com.example.Main",
                quickBuild = true,
                javaHome = javaHome,
                reportingMode = MissingRegistrationReportingMode.THROW,
            )
        assertTrue(resolution.buildArgs.isEmpty())
        assertTrue(resolution.runtimeArgs.isEmpty())
        assertTrue(requireNotNull(resolution.warning).contains("could not read JAVA_VERSION"))
    }

    @Test
    fun `missing registration mode parses warn exit throw and defaults to warn`() {
        assertEquals(MissingRegistrationReportingMode.WARN, MissingRegistrationReportingMode.parse(null))
        assertEquals(MissingRegistrationReportingMode.WARN, MissingRegistrationReportingMode.parse(""))
        assertEquals(MissingRegistrationReportingMode.WARN, MissingRegistrationReportingMode.parse("warn"))
        assertEquals(MissingRegistrationReportingMode.EXIT, MissingRegistrationReportingMode.parse("Exit"))
        assertEquals(MissingRegistrationReportingMode.THROW, MissingRegistrationReportingMode.parse("THROW"))
        assertEquals(MissingRegistrationReportingMode.WARN, MissingRegistrationReportingMode.parse("nope"))
    }

    @Test
    fun `empty packages factory collapses to OFF`() {
        assertEquals(ExactReachabilityMetadata.OFF, ExactReachabilityMetadata.packages())
        assertEquals(ExactReachabilityMetadata.OFF, ExactReachabilityMetadata.packages("", "  "))
    }

    private fun fakeJavaHome(feature: Int): File {
        val home = tmp.newFolder("graalvm-$feature")
        File(home, "release").writeText(
            """
            IMPLEMENTOR="GraalVM Community"
            JAVA_VERSION="$feature.0.0"
            """.trimIndent(),
        )
        return home
    }
}
