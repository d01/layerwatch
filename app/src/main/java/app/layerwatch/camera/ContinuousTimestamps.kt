package app.layerwatch.camera

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Keeps the printer's video timeline continuous across RTP timestamp discontinuities.
 *
 * The printer's RTSP server shares one stream between viewers, and whenever any client
 * starts playing it re-bases that stream's RTP timestamps for everyone: they step back by
 * ~1130 s. Media3 reads that as a jump of ~13 hours *forward*
 * (32-bit wraparound), holds the next frame until the clock gets there, and keeps queueing
 * the stream behind it until the heap runs out. Another viewer, an alert snapshot or a
 * reconnect is enough to trigger it.
 *
 * Instead of reconnecting (which would re-base the stream for the other viewers in turn),
 * each discontinuity is stitched out: the first frame after it is placed one frame after
 * the last one before it, and the same offset applies from then on.
 */
class TimestampStitcher(
    private val maxBackwardUs: Long = 1_000_000,
    private val maxForwardUs: Long = 2_000_000,
    private val frameUs: Long = 33_333,
) {
    /** Added to raw sample times to get the times the player sees. */
    var offsetUs = 0L
        private set
    private var lastRawUs = C.TIME_UNSET
    private var lastOutUs = C.TIME_UNSET
    private var lastAheadOutUs = C.TIME_UNSET
    /** Where a buffered position past an unread discontinuity was first seen, raw and mapped. */
    private var anchorRawUs = C.TIME_UNSET
    private var anchorOutUs = C.TIME_UNSET
    var discontinuities = 0
        private set
    /** Player-facing time of the first sample after the latest discontinuity. */
    var lastStitchOutUs = C.TIME_UNSET
        private set

    /** The player-facing time for the next sample read; [commit] = false for peeks. */
    fun map(rawUs: Long, commit: Boolean): Long {
        var offset = offsetUs
        val stitched = lastRawUs != C.TIME_UNSET && isDiscontinuity(rawUs - lastRawUs)
        if (stitched) offset = lastRawUs + offsetUs + frameUs - rawUs
        if (commit) {
            if (stitched) {
                discontinuities++
                lastStitchOutUs = rawUs + offset
                anchorRawUs = C.TIME_UNSET
            }
            offsetUs = offset
            lastRawUs = rawUs
            lastOutUs = rawUs + offset
        }
        return rawUs + offset
    }

    /**
     * A buffered or load position from the source. Once it runs past a discontinuity that
     * hasn't been read yet, it continues from where it was when the jump first showed up, so
     * the buffer keeps looking as full as it is. (Reporting it empty instead makes the player
     * pause to rebuffer, the decoder then stops reading, and the jump is never reached.)
     */
    fun mapAhead(rawUs: Long): Long {
        if (rawUs == C.TIME_END_OF_SOURCE || rawUs == C.TIME_UNSET || lastRawUs == C.TIME_UNSET) return toOut(rawUs)
        if (rawUs - lastRawUs in -maxBackwardUs..MAX_BUFFER_AHEAD_US) {
            anchorRawUs = C.TIME_UNSET
            return (rawUs + offsetUs).also { lastAheadOutUs = it }
        }
        if (anchorRawUs == C.TIME_UNSET || rawUs - anchorRawUs !in -maxBackwardUs..MAX_BUFFER_AHEAD_US) {
            anchorRawUs = rawUs
            anchorOutUs = maxOf(lastAheadOutUs, lastOutUs) + frameUs
        }
        return anchorOutUs + (rawUs - anchorRawUs)
    }

    fun toRaw(outUs: Long): Long = if (outUs == C.TIME_UNSET || outUs == C.TIME_END_OF_SOURCE) outUs else outUs - offsetUs

    fun toOut(rawUs: Long): Long = if (rawUs == C.TIME_UNSET || rawUs == C.TIME_END_OF_SOURCE) rawUs else rawUs + offsetUs

    private fun isDiscontinuity(deltaUs: Long) = deltaUs < -maxBackwardUs || deltaUs > maxForwardUs

    private companion object {
        /** Far more than RTSP ever buffers ahead; beyond it, a position is post-discontinuity. */
        const val MAX_BUFFER_AHEAD_US = 30_000_000L
    }
}

@OptIn(UnstableApi::class)
class ContinuousTimestampSource(source: MediaSource) : WrappingMediaSource(source) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        ContinuousTimestampPeriod(mediaSource.createPeriod(id, allocator, startPositionUs))

    override fun releasePeriod(mediaPeriod: MediaPeriod) =
        mediaSource.releasePeriod((mediaPeriod as ContinuousTimestampPeriod).inner)
}

@OptIn(UnstableApi::class)
private class ContinuousTimestampPeriod(val inner: MediaPeriod) : MediaPeriod {
    private val stitcher = TimestampStitcher()
    private var streams: Array<StitchedStream?> = emptyArray()
    /** Discontinuities whose pre-jump samples have been flushed by [discardBuffer]. */
    private var flushed = 0

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        inner.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(this@ContinuousTimestampPeriod)
            override fun onContinueLoadingRequested(source: MediaPeriod) =
                callback.onContinueLoadingRequested(this@ContinuousTimestampPeriod)
        }, stitcher.toRaw(positionUs))
    }

    override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()
    override fun getTrackGroups(): TrackGroupArray = inner.getTrackGroups()

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val innerStreams = Array(streams.size) { (streams[it] as StitchedStream?)?.inner }
        val result = inner.selectTracks(selections, mayRetainStreamFlags, innerStreams, streamResetFlags, stitcher.toRaw(positionUs))
        this.streams = Array(streams.size) { i ->
            val s = innerStreams[i]
            when {
                s == null -> null
                (streams[i] as StitchedStream?)?.inner === s -> streams[i] as StitchedStream
                else -> StitchedStream(s)
            }
        }
        for (i in streams.indices) streams[i] = this.streams[i]
        return stitcher.toOut(result)
    }

    /**
     * After a backward jump the queue still starts with played samples whose raw times are
     * later than everything after the jump. SampleQueue discards by time and stops at the
     * first later sample, so it would never free anything again: the stream piles up in
     * 64 KB segments until the heap runs out. Once playback is past the stitch, every
     * pre-jump sample has been played, so drop everything already read, once.
     */
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        if (flushed != stitcher.discontinuities && positionUs >= stitcher.lastStitchOutUs) {
            flushed = stitcher.discontinuities
            inner.discardBuffer(Long.MAX_VALUE, /* toKeyframe = */ false)
        }
        inner.discardBuffer(stitcher.toRaw(positionUs), toKeyframe)
    }
    override fun readDiscontinuity(): Long = stitcher.toOut(inner.readDiscontinuity())
    override fun seekToUs(positionUs: Long): Long = stitcher.toOut(inner.seekToUs(stitcher.toRaw(positionUs)))
    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        stitcher.toOut(inner.getAdjustedSeekPositionUs(stitcher.toRaw(positionUs), seekParameters))
    override fun getBufferedPositionUs(): Long = stitcher.mapAhead(inner.bufferedPositionUs)
    override fun getNextLoadPositionUs(): Long = stitcher.mapAhead(inner.nextLoadPositionUs)
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(
        loadingInfo.buildUpon().setPlaybackPositionUs(stitcher.toRaw(loadingInfo.playbackPositionUs)).build(),
    )
    override fun isLoading(): Boolean = inner.isLoading
    override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(stitcher.toRaw(positionUs))

    private inner class StitchedStream(val inner: SampleStream) : SampleStream {
        override fun isReady() = inner.isReady
        override fun maybeThrowError() = inner.maybeThrowError()
        override fun skipData(positionUs: Long) = inner.skipData(stitcher.toRaw(positionUs))
        override fun getFlags() = inner.flags

        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            val result = inner.readData(formatHolder, buffer, readFlags)
            if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) {
                val before = stitcher.discontinuities
                val raw = buffer.timeUs
                buffer.timeUs = stitcher.map(raw, commit = readFlags and SampleStream.FLAG_PEEK == 0)
                if (stitcher.discontinuities != before) {
                    Log.i(TAG, "stitched a timestamp discontinuity at raw ${raw / 1000} ms; offset now ${stitcher.offsetUs / 1000} ms")
                }
            }
            return result
        }
    }

    private companion object {
        const val TAG = "layerwatch.camera"
    }
}
