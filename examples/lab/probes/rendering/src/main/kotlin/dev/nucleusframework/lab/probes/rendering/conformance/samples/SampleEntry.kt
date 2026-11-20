package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.probes.rendering.conformance.Sample
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

/** A [Sample] with the composable that renders it. */
@Immutable
class SampleEntry(
    val sample: Sample,
    val content: @Composable () -> Unit,
)

/** Builds one category's entries: `samples(Text) { sample("id", "Title", "expect") { … } }`. */
class SampleListBuilder(
    private val category: SampleCategory,
) {
    private val entries = mutableListOf<SampleEntry>()

    fun sample(
        id: String,
        title: String,
        expect: String,
        content: @Composable () -> Unit,
    ) {
        entries += SampleEntry(Sample("${category.name.lowercase()}.$id", category, title, expect), content)
    }

    fun build(): List<SampleEntry> = entries.toList()
}

fun samples(
    category: SampleCategory,
    block: SampleListBuilder.() -> Unit,
): List<SampleEntry> = SampleListBuilder(category).apply(block).build()
