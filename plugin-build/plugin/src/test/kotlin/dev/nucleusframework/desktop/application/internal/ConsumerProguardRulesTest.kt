package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConsumerProguardRulesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val out by lazy { tmp.newFolder("rules") }

    private fun jar(
        name: String,
        vararg entries: Pair<String, Any?>,
    ): File =
        tmp.root.resolve(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                for ((entry, content) in entries) {
                    zip.putNextEntry(ZipEntry(entry))
                    when (content) {
                        is String -> zip.write(content.toByteArray())
                        is ByteArray -> zip.write(content)
                    }
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `reads files directly under META-INF proguard whatever their name`() {
        val lib =
            jar(
                "lib.jar",
                "META-INF/proguard/" to null,
                "META-INF/proguard/a.pro" to "-keep class a.A",
                "META-INF/proguard/rules.txt" to "-keep class a.B",
                "META-INF/proguard/nested/c.pro" to "-keep class a.C",
                "META-INF/com.android.tools/proguard/d.pro" to "-keep class a.D",
                "META-INF/com.android.tools/r8/e.pro" to "-keep,allowaccessmodification class a.E",
                "META-INF/proguardx/f.pro" to "-keep class a.F",
                "proguard/g.pro" to "-keep class a.G",
                "META-INF/versions/9/META-INF/proguard/h.pro" to "-keep class a.H",
            )

        val result = ConsumerProguardRules.extract(listOf(lib), out)

        assertEquals(listOf("-keep class a.A", "-keep class a.B"), result.files.map { it.readText() })
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `follows the JAR order and skips content already extracted`() {
        val shared = "-keep class kotlinx.coroutines.Job"
        val first = jar("first.jar", "META-INF/proguard/coroutines.pro" to shared)
        val second =
            jar(
                "second.jar",
                "META-INF/proguard/z.pro" to "-keep class b.B",
                "META-INF/proguard/coroutines.pro" to shared,
            )
        val third = jar("third.jar", "META-INF/proguard/a.pro" to "-keep class c.C")

        val result = ConsumerProguardRules.extract(listOf(first, second, third), out)

        assertEquals(
            listOf(shared, "-keep class b.B", "-keep class c.C"),
            result.files.map { it.readText() },
        )
        assertEquals(result.files.map { it.name }.sorted(), result.files.map { it.name })
    }

    @Test
    fun `hostile entry names never leave the destination directory`() {
        val lib =
            jar(
                "evil.jar",
                "META-INF/proguard/..\\..\\..\\evil.pro" to "-keep class e.A",
                "META-INF/proguard/../../escape.pro" to "-keep class e.B",
                "META-INF/proguard/C:\\Windows\\x.pro" to "-keep class e.C",
                "META-INF/proguard/it's \"quoted\" ; * ?.pro" to "-keep class e.D",
                "META-INF/proguard/${"x".repeat(500)}.pro" to "-keep class e.E",
                "META-INF/proguard/規則.pro" to "-keep class e.F",
            )

        val result = ConsumerProguardRules.extract(listOf(lib), out)

        assertEquals(5, result.files.size)
        for (file in result.files) {
            assertEquals(out.canonicalFile, file.canonicalFile.parentFile)
            assertTrue(file.name, file.name.matches(Regex("[A-Za-z0-9._-]+")))
            assertTrue(file.name, file.name.length <= 90)
        }
        assertFalse(tmp.root.resolve("escape.pro").exists())
        assertEquals(setOf("rules"), tmp.root.list()!!.filter { !it.endsWith(".jar") }.toSet())
    }

    @Test
    fun `strips a UTF-8 BOM and keeps the bytes otherwise`() {
        val latin1 = "# r\u00e8gles\n-keep class x.A".toByteArray(Charsets.ISO_8859_1)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val lib =
            jar(
                "lib.jar",
                "META-INF/proguard/bom.pro" to bom + "-keep class x.B".toByteArray(),
                "META-INF/proguard/latin1.pro" to latin1,
                "META-INF/proguard/crlf.pro" to "-keep class x.C\r\n-dontwarn x.**\r\n",
                "META-INF/proguard/empty.pro" to "",
            )

        val files = ConsumerProguardRules.extract(listOf(lib), out).files

        assertEquals("-keep class x.B", files[0].readText())
        assertEquals("-keep class x.C\r\n-dontwarn x.**\r\n", files[1].readText())
        assertEquals("", files[2].readText())
        assertArrayEquals(latin1, files[3].readBytes())
    }

    @Test
    fun `skips rule files with global or file-system options`() {
        val lib =
            jar(
                "lib.jar",
                "META-INF/proguard/io.pro" to "-keep class a.A\n-injars '/etc/passwd'",
                "META-INF/proguard/include.pro" to "-include other.pro",
                "META-INF/proguard/global.pro" to "-keep class a.B\n  -dontobfuscate",
                "META-INF/proguard/inline.pro" to "-keep class a.C { *; } -repackageclasses ''",
                "META-INF/proguard/ignore.pro" to "-ignorewarnings",
                "META-INF/proguard/ok.pro" to
                    """
                    # -dontobfuscate in a comment is fine, so is -injars
                    -keep class a.D { *; } # -printmapping x
                    -keepattributes Signature,InnerClasses
                    -keep,allowobfuscation,allowshrinking class a.E
                    -if class a.F
                    -keep class a.G
                    -assumenosideeffects class a.H { void log(...); }
                    """.trimIndent(),
            )

        val result = ConsumerProguardRules.extract(listOf(lib), out)

        assertEquals(1, result.files.size)
        assertTrue(result.files.single().readText().contains("-keep class a.D"))
        assertEquals(
            mapOf(
                "META-INF/proguard/global.pro" to "uses -dontobfuscate",
                "META-INF/proguard/ignore.pro" to "uses -ignorewarnings",
                "META-INF/proguard/include.pro" to "uses -include",
                "META-INF/proguard/inline.pro" to "uses quotes, -repackageclasses",
                "META-INF/proguard/io.pro" to "uses quotes, -injars",
            ),
            result.skipped.associate { it.entry to it.reason },
        )
    }

    private fun utf8AsLatin1(text: String): String = String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    @Test
    fun `options glued to delimiters and file includes are caught`() {
        val cases =
            mapOf(
                "-keep class a.B { *; }-dontobfuscate" to listOf("-dontobfuscate"),
                "-keep class a.B;-include /etc/x.pro" to listOf("-include"),
                "-keep class a.B {*;}-repackageclasses''" to listOf("quotes", "-repackageclasses"),
                "-keep,allowobfuscation class a.B(-injars)" to listOf("-injars"),
                "@/etc/other.pro" to listOf("@file"),
                "@other.pro\n-keep class a.B" to listOf("@file"),
                "-keep class a.B\n@other.pro" to listOf("@file"),
                "-addconfigurationdebugging" to listOf("-addconfigurationdebugging"),
                // ProGuard accepts any prefix of an option, and a bare `-` is `-include`.
                "-incl /etc/other.pro" to listOf("-incl"),
                "-dontob" to listOf("-dontob"),
                "-keep class a.B\n-repack ''" to listOf("quotes", "-repack"),
                "- /etc/other.pro" to listOf("-"),
                "-dontw a.**" to listOf("-dontw"),
                // Options a deny-list missed: aliases, whole-build switches, keystores, output.
                "-defaultpackage ''" to listOf("quotes", "-defaultpackage"),
                "-optimizeaggressively" to listOf("-optimizeaggressively"),
                "-dontprocesskotlinmetadata" to listOf("-dontprocesskotlinmetadata"),
                "-keystore k.jks\n-keystorepassword x" to listOf("-keystore", "-keystorepassword"),
                "-dontcompress" to listOf("-dontcompress"),
                "-zipalign 4" to listOf("-zipalign"),
                "-Keep class a.B" to listOf("-Keep"),
                // ProGuard ends a word at a quote and unquotes it: quotes are refused outright.
                "-keep class 'a.B'-printconfiguration" to listOf("quotes"),
                "'-printconfiguration'" to listOf("quotes"),
                "-keep class \"a.B\"" to listOf("quotes"),
                "-keep class 'a#'-dontshrink" to listOf("#", "quotes"),
                // A quoted brace hides the include from the brace count; the quote rejects the file anyway.
                "-keep class a.B { '{' }\n@other.pro" to listOf("quotes"),
                "# it's a comment\n-keep class a.B" to emptyList(),
                // A bare `@` is a word of its own for ProGuard: `@ x.pro` includes x.pro.
                "-dontwarn a.B\n@ /some/abs/path.pro\n-keep class c.D" to listOf("@file"),
                "@ other.pro" to listOf("@file"),
                "-keep class a.B\n@other.pro c.D" to listOf("@file"),
                "@C:/x/other.pro\n-keep class a.B" to listOf("@file"),
                "-keep class * implements @evil.pro\n-dontwarn a.**" to listOf("@file"),
                // File filters read file names, where `#` only starts a comment after whitespace.
                "-keepdirectories a#b -dontobfuscate" to listOf("#"),
                "-adaptresourcefilenames x#y -dontshrink\n-keep class a.B" to listOf("#"),
                "-keep class a.B # fine\n#fine too\n\t# and this" to emptyList(),
                // ProGuard splits on Character.isWhitespace and reads UTF-8; only ASCII code is allowed.
                "-dontwarn a\u001C-dontshrink" to listOf("non-ASCII"),
                "-dontwarn a\u001F-include x" to listOf("non-ASCII"),
                "-dontwarn a\u000B-dontobfuscate" to listOf("non-ASCII", "-dontobfuscate"),
                "-dontwarn a\u000C-dontobfuscate" to listOf("non-ASCII", "-dontobfuscate"),
                // U+2028 and U+2003 as their UTF-8 bytes, decoded one char per byte as the guard reads them.
                "a${utf8AsLatin1(Char(0x2028).toString())}-dontshrink" to listOf("non-ASCII"),
                "a${utf8AsLatin1(Char(0x2003).toString())}-dontshrink" to listOf("non-ASCII"),
                "# règles en français, ✓ — comments stay free\n-keep class a.B" to emptyList(),
                "-keep class a.B\t{ *; }" to emptyList(),
                // A file filter takes a bare `{` as a value; ProGuard is then back at top level.
                "-adaptresourcefilenames {\n@evil.pro" to listOf("{"),
                "-keepdirectories {\n@evil.pro\n-keep class a.B" to listOf("{"),
                "-dontwarn a.**\n{ @evil.pro }" to listOf("{"),
                "{ *; }" to listOf("{"),
            )
        for ((rules, expected) in cases) {
            assertEquals(rules, expected, ConsumerRuleGuard.disallowedOptions(rules))
        }
    }

    @Test
    fun `annotations and keep modifiers are not mistaken for options`() {
        val rules =
            """
            -keep @a.Ann class *
            -keep
                @a.Ann class * { *; }
            -keepclassmembers,allowobfuscation,includedescriptorclasses class * {
                @com.google.gson.annotations.SerializedName <fields>;
                @a.Ann
                <methods>;
            }
            -if @a.Ann class **
            -keep class <1>
            -assumevalues class a.C { int f return -1..5; }
            -keepattributes Signature,*Annotation*
            -dontwarn a.**
            -dontnote a.**
            -keepnames,allowshrinking class a.E
            -keepclasseswithmembernames class * { native <methods>; }
            -assumenosideeffects class a.F { void log(...); }
            -adaptclassstrings a.**
            -keep @a.Ann @b.Other public class *
            -keep @interface a.B
            -keepclasseswithmembers @a.** !final class * { <init>(); }
            -keep @a.Ann interface *
            -keep @a.Ann enum *
            -keep class * implements @com.foo.Marker **
            -keep @a.B class * extends @c.D com.x.Base
            -keep class a.D { void m(java.lang.String, int); }
            """.trimIndent()
        assertEquals(emptyList<String>(), ConsumerRuleGuard.disallowedOptions(rules))
    }

    @Test
    fun `excluded, missing and corrupt JARs contribute nothing and do not fail`() {
        val excluded = jar("excluded.jar", "META-INF/proguard/a.pro" to "-keep class a.A")
        val corrupt = tmp.root.resolve("corrupt.jar").apply { writeText("not a zip") }
        val missing = tmp.root.resolve("missing.jar")
        val directory = tmp.newFolder("classes.jar")
        val kept = jar("kept.jar", "META-INF/proguard/b.pro" to "-keep class b.B")

        val result =
            ConsumerProguardRules.extract(listOf(excluded, corrupt, missing, directory, kept), out) { it == excluded }

        assertEquals(listOf("-keep class b.B"), result.files.map { it.readText() })
        assertEquals(listOf(corrupt), result.skipped.map { it.jar })
    }

    @Test
    fun `service providers are kept, conditionally on a service the application ships`() {
        val api =
            jar(
                "api.jar",
                "com/example/Codec.class" to "",
                "META-INF/services/com.example.Codec" to
                    "# codecs\ncom.example.impl.Zip\r\n\n  com.example.impl.Gz # trailing\n",
            )
        val driver =
            jar(
                "driver.jar",
                "META-INF/services/java.sql.Driver" to "﻿org.h2.Driver\norg.h2.Driver\n",
                "META-INF/services/javax.annotation.processing.Processor" to "com.example.Processor\n",
                "META-INF/services/sub/ignored" to "x.Y\n",
            )

        val rules = ConsumerProguardRules.extract(listOf(api, driver), out).files.single().readText()

        assertEquals(
            """
            # Generated by Nucleus from the META-INF/services files of the input JARs.
            -keepnames class com.example.Codec
            -if class com.example.Codec
            -keep class com.example.impl.Zip { public <init>(); public static ** provider(); }
            -if class com.example.Codec
            -keep class com.example.impl.Gz { public <init>(); public static ** provider(); }
            -keep class org.h2.Driver { public <init>(); public static ** provider(); }

            """.trimIndent(),
            rules,
        )
    }

    @Test
    fun `an excluded JAR loses its rule files but keeps its service providers`() {
        val driver =
            jar(
                "driver.jar",
                "META-INF/proguard/driver.pro" to "-keep class org.d.Internal",
                "META-INF/services/java.sql.Driver" to "org.d.Driver\n",
            )

        val result = ConsumerProguardRules.extract(listOf(driver), out) { true }

        val rules = result.files.single().readText()
        assertTrue(rules.contains("-keep class org.d.Driver { public <init>(); public static ** provider(); }"))
        assertFalse(rules.contains("org.d.Internal"))
    }

    @Test
    fun `service files that are not provider lists are ignored quietly, never pasted into the rules`() {
        val lib =
            jar(
                "lib.jar",
                "META-INF/services/a.Service" to "a.Impl { *; }\n-dontobfuscate\n",
                "META-INF/services/org.codehaus.groovy.runtime.ExtensionModule" to "moduleName=ext\nmoduleVersion=1\n",
                "META-INF/services/b.Service" to "b.Impl\n",
            )

        val result = ConsumerProguardRules.extract(listOf(lib), out)

        assertTrue(result.skipped.isEmpty())
        val rules = result.files.single().readText()
        assertTrue(rules.contains("-keep class b.Impl"))
        assertFalse(rules.contains("dontobfuscate"))
        assertFalse(rules.contains("a.Impl"))
        assertFalse(rules.contains("moduleName"))
    }

    @Test
    fun `exclusions match group module and project paths with wildcards`() {
        val exclusions = listOf("com.squareup.*:*", "*:gson", ":shared", " org.example:lib ")
        assertTrue(ConsumerProguardRules.isExcluded("com.squareup.okhttp3:okhttp", exclusions))
        assertTrue(ConsumerProguardRules.isExcluded("com.google.code.gson:gson", exclusions))
        assertTrue(ConsumerProguardRules.isExcluded(":shared", exclusions))
        assertTrue(ConsumerProguardRules.isExcluded("org.example:lib", exclusions))
        assertFalse(ConsumerProguardRules.isExcluded("org.example:lib2", exclusions))
        assertFalse(ConsumerProguardRules.isExcluded(":shared:core", exclusions))
        assertFalse(ConsumerProguardRules.isExcluded("com.google.code.gson:gson-extras", exclusions))
        assertFalse(ConsumerProguardRules.isExcluded("orgXexample:lib", exclusions))
        assertFalse(ConsumerProguardRules.isExcluded(null, listOf("*")))
        assertFalse(ConsumerProguardRules.isExcluded("a:b", emptyList()))
    }

    @Test
    fun `a Kotlin Multiplatform library matches by its declared module too`() {
        val declared = listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core", "io.ktor:ktor-client-core", "x:y")
        assertEquals(
            listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core"),
            ConsumerProguardRules.matchingExclusions("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", declared),
        )
        assertEquals(
            listOf("io.ktor:ktor-client-core"),
            ConsumerProguardRules.matchingExclusions("io.ktor:ktor-client-core-desktop", declared),
        )
        assertTrue(ConsumerProguardRules.isExcluded("a:lib-jvm", listOf("a:lib-jvm")))
        assertFalse(ConsumerProguardRules.isExcluded("a:lib-jvmx", listOf("a:lib")))
        assertFalse(ConsumerProguardRules.isExcluded("a:other-jvm", listOf("a:lib")))
    }
}
