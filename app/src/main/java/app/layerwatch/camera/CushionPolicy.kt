package app.layerwatch.camera

/**
 * How much video to hold before playing, adapted to the network actually observed.
 *
 * Starts at [minMs]. Each freeze (the buffer ran dry mid-play) grows the cushion by
 * [stepUpMs] up to [maxMs]; each [calmMs] without one shrinks it by [stepDownMs]. So a
 * clean path gets ~1 s latency and a bad one (weak printer WiFi + cellular VPN) trades
 * latency for smoothness automatically.
 *
 * [playbackSpeed] keeps latency near the cushion: if more than the cushion has piled up
 * (e.g. after a burst), play slightly fast until it drains. Hysteresis avoids flapping.
 */
class CushionPolicy(
    private val minMs: Long = 1_000,
    private val maxMs: Long = 3_000,
    private val stepUpMs: Long = 500,
    private val stepDownMs: Long = 250,
    private val calmMs: Long = 180_000,
    private val catchUpAboveMs: Long = 800,
    private val catchUpUntilMs: Long = 200,
    private val catchUpSpeed: Float = 1.08f,
) {
    var cushionMs: Long = minMs
        private set
    private var lastChangeAt = 0L
    private var catchingUp = false

    fun onStart(nowMs: Long) {
        lastChangeAt = nowMs
        catchingUp = false
    }

    fun onFreeze(nowMs: Long) {
        cushionMs = (cushionMs + stepUpMs).coerceAtMost(maxMs)
        lastChangeAt = nowMs
    }

    /** Call periodically; returns the playback speed to use. */
    fun onTick(nowMs: Long, bufferedAheadMs: Long): Float {
        if (nowMs - lastChangeAt >= calmMs && cushionMs > minMs) {
            cushionMs = (cushionMs - stepDownMs).coerceAtLeast(minMs)
            lastChangeAt = nowMs
        }
        catchingUp = when {
            bufferedAheadMs > cushionMs + catchUpAboveMs -> true
            bufferedAheadMs <= cushionMs + catchUpUntilMs -> false
            else -> catchingUp
        }
        return if (catchingUp) catchUpSpeed else 1f
    }
}
