package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * One document — one tab. The workspace owns *where* a tab is, never whether it exists:
 * that list is the app's, here [DocumentSet].
 */
data class Document<out T>(
    val id: String,
    val title: String,
    val subtitle: String,
    val draft: String,
    /** Probe-specific payload (the satellites it asks for, a book's chapters…). */
    val extra: T,
)

/** The documents declared against a tab workspace, in declaration order. */
class DocumentSet<T>(
    seed: List<Document<T>>,
    private val make: (index: Int) -> Document<T>,
) {
    val documents: SnapshotStateList<Document<T>> = mutableStateListOf<Document<T>>().apply { addAll(seed) }

    private var opened = 0

    fun document(id: String): Document<T>? = documents.firstOrNull { it.id == id }

    /** Adds a document; its `Tab` declaration puts it in the window focused last. */
    fun open(): Document<T> {
        opened++
        return make(opened).also { documents += it }
    }

    /** Drops [id] once its tab is gone from the workspace, or it would be declared again. */
    fun forget(id: String) {
        documents.removeAll { it.id == id }
    }
}
