package dev.nucleusframework.application.filekit

import androidx.compose.runtime.LaunchedEffect
import dev.nucleusframework.application.nucleusApplication
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.exceptions.FileKitNotInitializedException
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File

/**
 * Child process of [main] in `FileKitE2EMain.kt`: boots a real [nucleusApplication] for one
 * scenario (`args[0]`), prints what FileKit resolved as `KEY=value` lines, then exits.
 *
 * `absent` runs on a classpath without FileKit, so it must never reach [FileKitProbe]: that
 * object is the only place referencing FileKit.
 */
fun main(args: Array<String>) {
    val scenario = args.single()
    println("scenario=$scenario")
    when (scenario) {
        "preInitAppId" -> FileKitProbe.initAppId("user-chosen-id")
        "preInitDirs" -> FileKitProbe.initDirs(File(System.getProperty("fileKitE2E.customDir")))
    }

    nucleusApplication(enableSingleInstance = false, exitProcessOnExit = true) {
        LaunchedEffect(Unit) {
            if (scenario == "absent") {
                val onClasspath =
                    Thread
                        .currentThread()
                        .contextClassLoader
                        .getResource("io/github/vinceglb/filekit/FileKit.class") != null
                println("fileKitOnClasspath=$onClasspath")
            } else {
                FileKitProbe.report()
                if (scenario == "initInContent") {
                    FileKitProbe.initAppId("content-id")
                    println("afterContentInit:")
                    FileKitProbe.report()
                }
            }
            println("booted=true")
            exitApplication()
        }
    }
}

private object FileKitProbe {
    fun initAppId(appId: String) = FileKit.init(appId = appId)

    fun initDirs(root: File) = FileKit.init(filesDir = File(root, "files"), cacheDir = File(root, "cache"))

    fun report() {
        println("appId=${orUnset { FileKit.appId }}")
        println("filesDir=${orUnset { File(FileKit.filesDir.path).canonicalPath }}")
    }

    private inline fun orUnset(value: () -> String): String =
        try {
            value()
        } catch (_: FileKitNotInitializedException) {
            "<unset>"
        }
}
