@file:OptIn(ExperimentalWasmJsInterop::class)
// The `js*` helpers' parameters are read by their `js()` bodies, which detekt cannot see.
@file:Suppress("UnusedParameter")

package dev.nucleusframework.share

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.ExperimentalWasmJsInterop

internal actual val isPlatformShareSupported: Boolean
    get() = jsHasWebShare()

/**
 * The Web Share API, shared by the `js` and `wasmJs` targets.
 *
 * Browsers only expose it in a secure context (HTTPS or `localhost`), and only honour it
 * during a user gesture: call [ShareSheet.share] from a click handler, without awaiting
 * anything slow first. File URIs are fetched into `File`s before the sheet opens.
 *
 * Unlike the other platforms, the browser answers once the sheet is gone; a dismissal is
 * no error.
 */
internal actual suspend fun platformShare(request: ShareRequest) {
    if (!jsHasWebShare()) {
        throw ShareException(ShareError.Unsupported, "This browser has no Web Share API (navigator.share)")
    }
    val payload = request.toWebSharePayload()
    suspendCancellableCoroutine { continuation ->
        jsShare(
            title = payload.title.orEmpty(),
            text = payload.text.orEmpty(),
            url = payload.url.orEmpty(),
            // URIs and MIME types carry no newline: joined, they cross the interop boundary
            // as plain strings on both targets.
            fileUris = payload.files.joinToString("\n") { it.uri },
            fileTypes = payload.files.joinToString("\n") { it.mimeType.orEmpty() },
            onDone = { if (continuation.isActive) continuation.resume(Unit) },
            onError = { name, message ->
                if (continuation.isActive) {
                    when (val error = shareErrorOf(name)) {
                        null -> continuation.resume(Unit)
                        else -> continuation.resumeWithException(ShareException(error, "$name: $message"))
                    }
                }
            },
        )
    }
}

/** `null` for a dismissed sheet (`AbortError`), which is not a failure. */
private fun shareErrorOf(name: String): ShareError? =
    when (name) {
        "AbortError" -> null
        // Called outside a user gesture, or blocked by a permissions policy.
        "NotAllowedError" -> ShareError.Platform
        "InvalidStateError" -> ShareError.AlreadyOpen
        "TypeError", "DataError" -> ShareError.InvalidItem
        UNSUPPORTED_FILES -> ShareError.UnsupportedItem
        FETCH_FAILED -> ShareError.Io
        else -> ShareError.Platform
    }

// Error names of our own, reported by `jsShare` before the browser is asked.
private const val UNSUPPORTED_FILES = "NucleusUnsupportedFiles"
private const val FETCH_FAILED = "NucleusFetchFailed"

// The browser is reached through `js()` bodies rather than external declarations, so the same
// source compiles for Kotlin/JS and Kotlin/Wasm; only strings and lambdas cross the boundary.

private fun jsHasWebShare(): Boolean = js("typeof navigator !== 'undefined' && typeof navigator.share === 'function'")

@Suppress("LongParameterList")
private fun jsShare(
    title: String,
    text: String,
    url: String,
    fileUris: String,
    fileTypes: String,
    onDone: () -> Unit,
    onError: (String, String) -> Unit,
): Unit =
    js(
        """{
        var uris = fileUris ? fileUris.split('\n') : [];
        var types = fileTypes.split('\n');
        var fileName = function (uri, type, index) {
            if (/^https?:/i.test(uri)) {
                var last = new URL(uri).pathname.split('/').pop();
                if (last) return decodeURIComponent(last);
            }
            var subtype = (type.split('/')[1] || 'bin').split(/[+;]/)[0];
            var extension = { plain: 'txt', jpeg: 'jpg', 'svg': 'svg' }[subtype] || subtype;
            return 'shared-' + (index + 1) + '.' + extension;
        };
        Promise.all(uris.map(function (uri, index) {
            return fetch(uri).then(function (response) {
                if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + uri);
                return response.blob();
            }).then(function (blob) {
                var type = types[index] || blob.type || 'application/octet-stream';
                return new File([blob], fileName(uri, type, index), { type: type });
            });
        })).then(function (files) {
            var data = {};
            if (title) data.title = title;
            if (text) data.text = text;
            if (url) data.url = url;
            if (files.length) data.files = files;
            if (files.length && navigator.canShare && !navigator.canShare(data)) {
                onError('NucleusUnsupportedFiles', 'this browser cannot share these files');
                return;
            }
            navigator.share(data).then(function () { onDone(); }, function (e) {
                onError(e && e.name ? e.name : 'Error', e && e.message ? e.message : String(e));
            });
        }, function (e) {
            onError('NucleusFetchFailed', e && e.message ? e.message : String(e));
        });
    }""",
    )
