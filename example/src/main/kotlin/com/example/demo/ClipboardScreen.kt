package com.example.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.clipboard.AccessBehavior
import io.github.kdroidfilter.nucleus.clipboard.Clipboard
import io.github.kdroidfilter.nucleus.clipboard.ClipboardEvent
import io.github.kdroidfilter.nucleus.clipboard.ClipboardFormat
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import org.jetbrains.skia.Image as SkiaImage

private const val EVENT_LOG_MAX = 40

private sealed class LogEntry {
    abstract val line: String

    data class Text(override val line: String) : LogEntry()

    data class Image(override val line: String, val bitmap: ImageBitmap) : LogEntry()
}

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun ClipboardScreen() {
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateListOf<LogEntry>() }

    fun log(msg: String) {
        val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        events.add(0, LogEntry.Text("[$ts] $msg"))
        while (events.size > EVENT_LOG_MAX) events.removeAt(events.lastIndex)
    }

    fun logImage(
        msg: String,
        bitmap: ImageBitmap,
    ) {
        val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        events.add(0, LogEntry.Image("[$ts] $msg", bitmap))
        while (events.size > EVENT_LOG_MAX) events.removeAt(events.lastIndex)
    }

    var currentFormats by remember { mutableStateOf<Set<ClipboardFormat>>(emptySet()) }
    var currentChangeCount by remember { mutableStateOf(0L) }
    var lastText by remember { mutableStateOf<String?>(null) }
    var lastHtml by remember { mutableStateOf<String?>(null) }
    var lastRtf by remember { mutableStateOf<String?>(null) }
    var lastImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastFiles by remember { mutableStateOf<List<String>>(emptyList()) }

    var textToCopy by remember { mutableStateOf("Hello from Nucleus") }
    var htmlToCopy by remember {
        mutableStateOf("<b>Hello</b> from <i>Nucleus</i> — rich clipboard write")
    }

    // Watcher — cold Flow, stopped when the screen leaves the composition.
    LaunchedEffect(Unit) {
        Clipboard.watch().collect { event: ClipboardEvent ->
            currentFormats = event.formats
            currentChangeCount = event.changeCount
            log("change #${event.changeCount} formats=${event.formats}")
            if (ClipboardFormat.Image in event.formats) {
                val bytes = Clipboard.readImageBytes()
                val bitmap =
                    bytes?.let {
                        runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull()
                    }
                if (bitmap != null) {
                    logImage("image copied · ${bytes.size} bytes (png)", bitmap)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        currentFormats = Clipboard.availableFormats()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Clipboard", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Backend: ${Clipboard.backendName} · available: ${Clipboard.isAvailable}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WatcherCard(currentFormats, currentChangeCount)
            AccessBehaviorCard(
                onChange = { behavior ->
                    Clipboard.setAccessBehavior(behavior)
                    log("access behavior → $behavior")
                },
            )
            WriteCard(
                textToCopy = textToCopy,
                onTextChange = { textToCopy = it },
                htmlToCopy = htmlToCopy,
                onHtmlChange = { htmlToCopy = it },
                onWriteText = {
                    scope.launch {
                        val ok = Clipboard.writeText(textToCopy)
                        log(if (ok) "wrote text (${textToCopy.length} chars)" else "writeText FAILED")
                    }
                },
                onWriteHtml = {
                    scope.launch {
                        val ok = Clipboard.writeHtml(htmlToCopy, plainTextFallback = textToCopy)
                        log(if (ok) "wrote html + text fallback" else "writeHtml FAILED")
                    }
                },
                onWriteRich = {
                    scope.launch {
                        val ok =
                            Clipboard.write {
                                html = htmlToCopy
                                text = textToCopy
                                rtf = """{\rtf1\ansi Rich \b $textToCopy\b0 .}"""
                            }
                        log(if (ok) "wrote multi-format (html+rtf+text)" else "write{} FAILED")
                    }
                },
                onClear = {
                    scope.launch {
                        val ok = Clipboard.clear()
                        log(if (ok) "cleared" else "clear FAILED")
                    }
                },
            )
            ReadCard(
                lastText = lastText,
                lastHtml = lastHtml,
                lastRtf = lastRtf,
                lastImage = lastImage,
                lastFiles = lastFiles,
                onReadText = {
                    scope.launch {
                        lastText = Clipboard.readText()
                        log("read text: ${lastText?.take(80) ?: "(none)"}")
                    }
                },
                onReadHtml = {
                    scope.launch {
                        lastHtml = Clipboard.readHtml()
                        log("read html: ${lastHtml?.take(80) ?: "(none)"}")
                    }
                },
                onReadRtf = {
                    scope.launch {
                        lastRtf = Clipboard.readRtf()
                        log("read rtf: ${lastRtf?.take(80) ?: "(none)"}")
                    }
                },
                onReadImage = {
                    scope.launch {
                        val bytes = Clipboard.readImageBytes()
                        val bitmap =
                            bytes?.let {
                                runCatching {
                                    SkiaImage
                                        .makeFromEncoded(
                                            it,
                                        ).toComposeImageBitmap()
                                }.getOrNull()
                            }
                        lastImage = bitmap
                        if (bitmap != null && bytes != null) {
                            logImage("read image · ${bytes.size} bytes (png)", bitmap)
                        } else {
                            log("read image: ${bytes?.size ?: 0} bytes (png)")
                        }
                    }
                },
                onReadFiles = {
                    scope.launch {
                        val list = Clipboard.readFiles().map { it.toString() }
                        lastFiles = list
                        log("read files: ${list.size}")
                    }
                },
            )
            EventLogCard(events)
        }
    }
}

@Composable
private fun WatcherCard(
    formats: Set<ClipboardFormat>,
    changeCount: Long,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Watcher", style = MaterialTheme.typography.titleMedium)
            Text(
                "changeCount: $changeCount",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ClipboardFormat.entries.forEach { fmt ->
                    FilterChip(
                        selected = fmt in formats,
                        onClick = {},
                        label = { Text(fmt.name) },
                    )
                }
            }
            Text(
                "Copy anything anywhere (⌘C) to see events appear below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessBehaviorCard(onChange: (AccessBehavior) -> Unit) {
    val supported = Clipboard.isAccessBehaviorSupported
    var current by remember { mutableStateOf(Clipboard.accessBehavior) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Privacy — macOS 15.4+", style = MaterialTheme.typography.titleMedium)
            Text(
                if (supported) {
                    "current: ${current?.name ?: "(unknown)"}"
                } else {
                    "Not supported on this OS / runtime — reads are unrestricted."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccessBehavior.entries.forEach { behavior ->
                    FilterChip(
                        enabled = supported,
                        selected = current == behavior,
                        onClick = {
                            onChange(behavior)
                            current = Clipboard.accessBehavior
                        },
                        label = { Text(behavior.name) },
                    )
                }
            }
            Text(
                "Watcher and availableFormats() only read metadata " +
                    "(changeCount + types) — they never trigger the pasteboard-privacy prompt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WriteCard(
    textToCopy: String,
    onTextChange: (String) -> Unit,
    htmlToCopy: String,
    onHtmlChange: (String) -> Unit,
    onWriteText: () -> Unit,
    onWriteHtml: () -> Unit,
    onWriteRich: () -> Unit,
    onClear: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Write", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = textToCopy,
                onValueChange = onTextChange,
                label = { Text("Plain text") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = htmlToCopy,
                onValueChange = onHtmlChange,
                label = { Text("HTML fragment") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onWriteText) { Text("Write text") }
                Button(onClick = onWriteHtml) { Text("Write HTML + text") }
                Button(onClick = onWriteRich) { Text("Write {} multi-format") }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ReadCard(
    lastText: String?,
    lastHtml: String?,
    lastRtf: String?,
    lastImage: ImageBitmap?,
    lastFiles: List<String>,
    onReadText: () -> Unit,
    onReadHtml: () -> Unit,
    onReadRtf: () -> Unit,
    onReadImage: () -> Unit,
    onReadFiles: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Read", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReadText) { Text("Text") }
                Button(onClick = onReadHtml) { Text("HTML") }
                Button(onClick = onReadRtf) { Text("RTF") }
                Button(onClick = onReadImage) { Text("Image") }
                Button(onClick = onReadFiles) { Text("Files") }
            }
            ReadRow("Text", lastText)
            ReadRow("HTML", lastHtml)
            ReadRow("RTF", lastRtf)
            if (lastImage != null) {
                Text("Image", style = MaterialTheme.typography.labelMedium)
                Image(
                    painter = BitmapPainter(lastImage),
                    contentDescription = "Clipboard image",
                    modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 200.dp),
                )
            }
            if (lastFiles.isNotEmpty()) {
                Text("Files", style = MaterialTheme.typography.labelMedium)
                Column {
                    lastFiles.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ReadRow(
    label: String,
    value: String?,
) {
    if (value == null) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun EventLogCard(events: List<LogEntry>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Event log", style = MaterialTheme.typography.titleMedium)
            if (events.isEmpty()) {
                Text(
                    "No events yet. Copy something to populate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                events.take(EVENT_LOG_MAX).forEach { entry ->
                    when (entry) {
                        is LogEntry.Text ->
                            Text(
                                entry.line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        is LogEntry.Image ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    entry.line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Image(
                                    painter = BitmapPainter(entry.bitmap),
                                    contentDescription = "Clipboard image",
                                    modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 160.dp),
                                )
                            }
                    }
                }
            }
        }
    }
}
