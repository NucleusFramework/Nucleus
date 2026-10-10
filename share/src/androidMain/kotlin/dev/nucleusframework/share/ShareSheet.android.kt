package dev.nucleusframework.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal actual val isPlatformShareSupported: Boolean
    get() = true

internal actual suspend fun platformShare(request: ShareRequest): Unit = shareFrom(null, request)

/**
 * [ShareSheet.share], started from [context]. An activity keeps the chooser in its
 * task; any other context starts it as a new task.
 *
 * @throws ShareException when the request is invalid or the chooser could not be started
 */
public suspend fun ShareSheet.share(
    request: ShareRequest,
    context: Context,
) {
    request.validate()
    shareFrom(context, request)
}

private suspend fun shareFrom(
    explicit: Context?,
    request: ShareRequest,
) {
    val context =
        explicit
            ?: AndroidShareContext.currentActivity
            ?: AndroidShareContext.application
            ?: throw ShareException(ShareError.NoWindow, "No context: NucleusShareProvider has not run")
    val streams = withContext(Dispatchers.IO) { stageStreams(context, request) }
    withContext(Dispatchers.Main) {
        try {
            context.startActivity(chooserIntent(context, request, streams))
        } catch (e: ActivityNotFoundException) {
            throw ShareException(ShareError.NoHandler, e.message, e)
        }
    }
}

private class SharedStream(
    val uri: Uri,
    val mimeType: String?,
)

private fun chooserIntent(
    context: Context,
    request: ShareRequest,
    streams: List<SharedStream>,
): Intent {
    val text = request.sharedText()
    val intent =
        Intent(if (streams.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = primaryMimeType(streams.map { it.mimeType }, text != null)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            request.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            // The Sharesheet's preview title (API 29+).
            request.title?.let { putExtra(Intent.EXTRA_TITLE, it) }
            when (streams.size) {
                0 -> Unit
                1 -> putExtra(Intent.EXTRA_STREAM, streams.single().uri)
                else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams.map { it.uri }))
            }
            if (streams.isNotEmpty()) {
                // The chooser forwards the grant only for URIs listed in the clip data.
                clipData =
                    ClipData.newUri(context.contentResolver, "shared files", streams.first().uri).also { clip ->
                        streams.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
                    }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    return Intent.createChooser(intent, request.title).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Every file item as a URI the receiving app can open: owned `content://` URIs as is, local files staged. */
private fun stageStreams(
    context: Context,
    request: ShareRequest,
): List<SharedStream> {
    val files = request.items.filter { it is ShareItem.File || it is ShareItem.FileUri }
    if (files.isEmpty()) return emptyList()
    val root = stagingRoot(context)
    purgeStaleStaging(root)
    return files.map { item ->
        when (item) {
            is ShareItem.File -> stage(context, root, File(item.path), item.mimeType)
            is ShareItem.FileUri -> {
                val uri = Uri.parse(item.uri)
                when (uri.scheme?.lowercase()) {
                    "content" -> SharedStream(uri, item.mimeType ?: context.contentResolver.getType(uri))
                    "file", null -> {
                        val path = uri.path ?: throw ShareException(ShareError.InvalidItem, item.uri)
                        stage(context, root, File(path), item.mimeType)
                    }
                    else -> throw ShareException(ShareError.UnsupportedItem, "Not a file URI: ${item.uri}")
                }
            }
            else -> error("unreachable")
        }
    }
}

/**
 * Copies [source] under the staging root. Serving the original path instead would need
 * the provider to remember which paths it may serve — a registry that dies with the
 * process, while the receiving app may read the URI much later.
 */
private fun stage(
    context: Context,
    root: File,
    source: File,
    mimeType: String?,
): SharedStream {
    if (!source.isFile) throw ShareException(ShareError.Io, "Not a readable file: $source")
    // One directory per file keeps equal names apart; the name is the one receivers show.
    val id = UUID.randomUUID().toString()
    val target = File(File(root, id), source.name)
    try {
        source.copyTo(target, overwrite = true)
    } catch (e: IOException) {
        throw ShareException(ShareError.Io, "Cannot stage $source: ${e.message}", e)
    }
    val uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(shareAuthority(context))
            .appendPath(id)
            .appendPath(target.name)
            .build()
    return SharedStream(uri, mimeType ?: guessMimeType(source.name))
}

/** Staged files older than a day: long enough for any receiver to have read them. */
private fun purgeStaleStaging(root: File) {
    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
    root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
}
