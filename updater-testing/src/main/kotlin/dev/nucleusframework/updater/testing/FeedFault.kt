package dev.nucleusframework.updater.testing

import kotlin.time.Duration

/**
 * A misbehaviour [UpdateFeedServer] injects into the responses it serves, to see how an app's
 * update flow copes with the failures real release hosts, proxies and networks produce.
 *
 * Several faults may apply to one request; [Status] wins over everything else, and the others
 * combine (a [Delay] then a [Throttle]d, [Truncate]d body, for instance).
 */
public sealed class FeedFault {
    /** Answers with HTTP [code] and an empty body instead of serving the file. */
    public class Status(
        public val code: Int,
    ) : FeedFault() {
        override fun toString(): String = "Status($code)"
    }

    /** Waits [duration] before answering: a slow host, or one that times out. */
    public class Delay(
        public val duration: Duration,
    ) : FeedFault() {
        override fun toString(): String = "Delay($duration)"
    }

    /** Sends the body at no more than [bytesPerSecond]: a slow link, to watch download progress. */
    public class Throttle(
        public val bytesPerSecond: Long,
    ) : FeedFault() {
        init {
            require(bytesPerSecond > 0) { "bytesPerSecond must be positive, got $bytesPerSecond" }
        }

        override fun toString(): String = "Throttle($bytesPerSecond B/s)"
    }

    /**
     * Announces the whole body, sends only its first [afterBytes] bytes and drops the connection:
     * a transfer cut part-way.
     */
    public class Truncate(
        public val afterBytes: Long,
    ) : FeedFault() {
        override fun toString(): String = "Truncate(after $afterBytes bytes)"
    }

    /**
     * Flips the byte at [offset] of the body (relative to the start of the file, whatever range is
     * requested): a corrupted transfer or a tampered artifact, which the SHA-512 check must catch.
     */
    public class Corrupt(
        public val offset: Long = 0,
    ) : FeedFault() {
        override fun toString(): String = "Corrupt(at $offset)"
    }

    /** Ignores `Range` and serves the whole file with HTTP 200: a host without range support. */
    public data object IgnoreRange : FeedFault()
}
