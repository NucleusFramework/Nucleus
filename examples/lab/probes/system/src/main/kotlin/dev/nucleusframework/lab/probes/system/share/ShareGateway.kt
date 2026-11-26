package dev.nucleusframework.lab.probes.system.share

import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.share
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.share.ShareAnchor
import dev.nucleusframework.share.ShareRequest
import dev.nucleusframework.share.ShareSheet
import dev.nucleusframework.share.shareRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.nio.file.Path
import kotlin.io.path.writeText

/** Port over `share`, with the desktop parent resolved by `nucleus-application`. */
interface ShareGateway {
    val scratchDir: Path

    fun availability(): Availability

    fun request(payload: SharePayload): ShareRequest

    /** @throws dev.nucleusframework.share.ShareException as the platform reports it */
    suspend fun share(
        request: ShareRequest,
        parent: ShareParentChoice,
        window: NucleusWindow?,
        anchor: ShareAnchor?,
    )
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusShareGateway : ShareGateway {
    override val scratchDir: Path by lazy { LabPaths.scratch("share") }

    // A broken libnucleus_share throws a LinkageError from here: it must make sharing unavailable,
    // not take the Lab down (it once did, from this very call).
    override fun availability(): Availability = Availability.catching { ShareSheet.isSupported }

    override fun request(payload: SharePayload): ShareRequest =
        when (payload) {
            SharePayload.Text ->
                shareRequest {
                    title = "Share text"
                    text("Shared from Nucleus Lab")
                }
            SharePayload.Link ->
                shareRequest {
                    title = "Share link"
                    subject = "Nucleus"
                    url(REPO)
                }
            SharePayload.File ->
                shareRequest {
                    title = "Share file"
                    file(sample("nucleus-lab-share.txt", "Hello from Nucleus Lab"))
                }
            SharePayload.UnicodeFile ->
                shareRequest {
                    title = "Share file"
                    file(sample("Lab partage é ü 文件.txt", "Unicode file name"))
                }
            SharePayload.Mixed ->
                shareRequest {
                    title = "Share mixed payload"
                    subject = "Nucleus"
                    text("Two files and a link")
                    url(REPO)
                    file(sample("first.txt", "First"))
                    file(sample("second.txt", "Second"))
                }
            SharePayload.Blank -> shareRequest { text(" ") }
            SharePayload.Empty -> shareRequest { title = "Nothing to share" }
            SharePayload.BadUrl -> shareRequest { url("not a url at all") }
            SharePayload.ContentUri -> shareRequest { fileUri("content://dev.nucleusframework.lab/none.txt") }
            SharePayload.MissingFile -> shareRequest { file(scratchDir.resolve("does-not-exist.txt").toString()) }
        }

    override suspend fun share(
        request: ShareRequest,
        parent: ShareParentChoice,
        window: NucleusWindow?,
        anchor: ShareAnchor?,
    ) {
        when {
            parent == ShareParentChoice.LabWindow && window != null -> window.share(request, anchor)
            else -> ShareSheet.share(request)
        }
    }

    private fun sample(
        name: String,
        content: String,
    ): String = scratchDir.resolve(name).apply { writeText(content) }.toString()

    private companion object {
        const val REPO = "https://github.com/NucleusFramework/Nucleus"
    }
}
