package dev.nucleusframework.application.filekit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Process-level E2E for FileKit auto-initialization in `nucleusApplication`: every scenario is
 * a fresh JVM running [FileKitE2EApp][dev.nucleusframework.application.filekit.main] — FileKit is
 * a process-wide singleton, and the `absent` scenario needs a classpath without it.
 *
 * The children run with `nucleus.app.id = "My App"` (a space, passed through untouched) and with
 * `APPDATA` / `HOME` / `XDG_DATA_HOME` pointed at a scratch directory, so FileKit never touches the
 * real user profile.
 *
 * Run: `./gradlew :nucleus-application:fileKitE2E`
 */
fun main() {
    val fullClasspath = System.getProperty("fileKitE2E.classpath")
    val noFileKitClasspath = System.getProperty("fileKitE2E.classpathWithoutFileKit")
    val scratch = Files.createTempDirectory("filekit-e2e").toFile().canonicalFile
    val dataHome = File(scratch, "data").apply { mkdirs() }
    val customDir = File(scratch, "custom")
    val expectedAppId = "My App"
    val expectedDefaultDir = expectedFilesDir(dataHome, expectedAppId).path

    val failures = mutableListOf<String>()

    fun scenario(
        name: String,
        classpath: String,
        vararg expected: Pair<String, String>,
    ) {
        val output = runChild(name, classpath, dataHome, customDir)
        val missing = expected.filter { (key, value) -> "$key=$value" !in output.lines }
        val ok = output.exitCode == 0 && "booted=true" in output.lines && missing.isEmpty()
        println("[${if (ok) "PASS" else "FAIL"}] $name")
        if (!ok) {
            failures += name
            println("  exit=${output.exitCode} missing=${missing.map { "${it.first}=${it.second}" }}")
            output.lines.forEach { println("  | $it") }
        }
    }

    scenario("absent", noFileKitClasspath, "fileKitOnClasspath" to "false")
    scenario("uninitialized", fullClasspath, "appId" to expectedAppId, "filesDir" to expectedDefaultDir)
    scenario("preInitAppId", fullClasspath, "appId" to "user-chosen-id")
    scenario(
        "preInitDirs",
        fullClasspath,
        "appId" to "<unset>",
        "filesDir" to File(customDir, "files").canonicalPath,
    )
    scenario(
        "initInContent",
        fullClasspath,
        "appId" to expectedAppId,
        "appId" to "content-id",
    )

    scratch.deleteRecursively()
    println(if (failures.isEmpty()) "RESULT=PASS" else "RESULT=FAIL $failures")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private class ChildOutput(
    val exitCode: Int,
    val lines: List<String>,
)

private fun runChild(
    scenario: String,
    classpath: String,
    dataHome: File,
    customDir: File,
): ChildOutput {
    val java =
        ProcessHandle
            .current()
            .info()
            .command()
            .get()
    val process =
        ProcessBuilder(
            java,
            "-cp",
            classpath,
            "-Dnucleus.app.id=My App",
            "-DfileKitE2E.customDir=${customDir.path}",
            "dev.nucleusframework.application.filekit.FileKitE2EAppKt",
            scenario,
        ).redirectErrorStream(true)
            .apply {
                environment()["APPDATA"] = dataHome.path
                environment()["HOME"] = dataHome.path
                environment()["XDG_DATA_HOME"] = dataHome.path
            }.start()
    val lines = process.inputStream.bufferedReader().readLines()
    if (!process.waitFor(2, TimeUnit.MINUTES)) process.destroyForcibly()
    return ChildOutput(process.exitValue(), lines)
}

/** Where FileKit's JVM `filesDir` lands for [appId] with the redirected environment. */
private fun expectedFilesDir(
    dataHome: File,
    appId: String,
): File {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> File(dataHome, "Library/Application Support/$appId")
        else -> File(dataHome, appId) // Windows: %APPDATA%\appId; Linux: $XDG_DATA_HOME/appId
    }.canonicalFile
}
