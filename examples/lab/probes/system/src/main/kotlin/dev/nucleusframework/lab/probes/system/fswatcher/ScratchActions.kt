package dev.nucleusframework.lab.probes.system.fswatcher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * The stimuli: plain `java.nio.file` operations on the scratch root. Toggles (rename ⇄) go
 * back and forth so any action can be repeated; a missing precondition is an error the
 * probe reports, never a silent no-op.
 */
class ScratchActions(
    private val root: Path,
) {
    private val file = root.resolve("probe.txt")
    private val renamed = root.resolve("probe-renamed.txt")
    private val nested = root.resolve("nested")
    private val moved = root.resolve("nested-moved")
    private val burst = root.resolve("burst")

    @OptIn(ExperimentalPathApi::class)
    fun perform(action: FsAction) {
        when (action) {
            FsAction.CreateFile -> {
                check(!file.exists()) { "probe.txt already exists: delete it first" }
                file.writeText("created ${System.currentTimeMillis()}\n")
            }
            FsAction.ModifyFile -> {
                val target = existing(file, renamed) ?: error("no probe file: create it first")
                Files.writeString(target, "appended ${System.currentTimeMillis()}\n", StandardOpenOption.APPEND)
            }
            FsAction.RenameFile ->
                when {
                    file.exists() -> Files.move(file, renamed)
                    renamed.exists() -> Files.move(renamed, file)
                    else -> error("no probe file to rename: create it first")
                }
            FsAction.DeleteFile -> Files.delete(existing(file, renamed) ?: error("no probe file to delete"))
            FsAction.CreateNested -> {
                check(!nested.exists() && !moved.exists()) { "the nested tree already exists: delete it first" }
                nested
                    .resolve("deep")
                    .createDirectories()
                    .resolve("leaf.txt")
                    .writeText("leaf\n")
            }
            FsAction.RenameDir ->
                when {
                    nested.exists() -> Files.move(nested, moved)
                    moved.exists() -> Files.move(moved, nested)
                    else -> error("no nested tree to rename: create it first")
                }
            FsAction.DeleteTree -> (existing(nested, moved) ?: error("no nested tree to delete")).deleteRecursively()
            FsAction.Burst -> {
                burst.createDirectories()
                repeat(BURST) { i -> burst.resolve("file-%02d.txt".format(i)).writeText("$i\n") }
            }
            FsAction.Clean -> root.listDirectoryEntries().forEach { it.deleteRecursively() }
        }
    }

    private fun existing(vararg candidates: Path): Path? = candidates.firstOrNull { it.exists() }

    private companion object {
        const val BURST = 50
    }
}
