package dev.nucleusframework.updater.testing

/** A request [UpdateFeedServer] answered, as recorded in [UpdateFeedServer.requests]. */
public class FeedRequest(
    /** The HTTP method, `GET` or `HEAD`. */
    public val method: String,
    /** The requested file name, relative to the feed root (`latest.yml`, `MyApp-2.0.0.exe`). */
    public val path: String,
    /** The `Range` header, if the client sent one. */
    public val range: String?,
    /** The status the server answered with. */
    public val status: Int,
    /** The body bytes actually sent. */
    public val bytesSent: Long,
) {
    override fun toString(): String = "$method /$path${range?.let { " [$it]" }.orEmpty()} → $status ($bytesSent bytes)"
}
