package io.ensomi.android.play

/*
 * What a frame asks the runtime, as seldom as the answer can change: each call goes through JNA with its allocations,
 * and they were most of a frame's work on a phone. These only decide when to ask; what is drawn is the same as asking
 * every frame.
 */

/**
 * When a frame must ask the session for its visible objects: their states change only with an input or an event, and
 * what enters at the far end is known ahead, so a list holds for [REUSE_MS] of render time. Its range reaches
 * [REUSE_MS] further than a frame needs; what lies past the far end or behind the line is not drawn.
 */
class VisibleWindow {
    private var stale = true
    private var askedAtMs = 0.0
    private var scrollTimeMs = 0.0

    /** An input or an event changed what the session would answer, or the game started again. */
    fun invalidate() {
        stale = true
    }

    /** Whether a frame drawn at [renderMs] needs a new list; render time only goes back when the game restarts. */
    fun mustAsk(renderMs: Double, scrollTimeMs: Double): Boolean =
        stale || scrollTimeMs != this.scrollTimeMs || renderMs < askedAtMs || renderMs > askedAtMs + REUSE_MS

    fun lowerMs(renderMs: Double): Double = renderMs - PlayController.VISIBLE_BEFORE_MS

    fun upperMs(renderMs: Double, scrollTimeMs: Double): Double = renderMs + scrollTimeMs + PlayController.VISIBLE_AFTER_MS + REUSE_MS

    fun asked(renderMs: Double, scrollTimeMs: Double) {
        stale = false
        askedAtMs = renderMs
        this.scrollTimeMs = scrollTimeMs
    }

    companion object {
        const val REUSE_MS = 100.0
    }
}

/**
 * Which lanes' lights a frame must ask for: a dark lane stays dark until its next input, so it costs no call. A lit one
 * is asked every frame, as it fades.
 */
class LaneAsking(lanes: Int = PlayfieldGeometry.LANES) {
    private val lit = BooleanArray(lanes)
    private val touched = BooleanArray(lanes)

    /** The lane had an input since it was last asked for. */
    fun touched(lane: Int) {
        touched[lane] = true
    }

    fun mustAsk(lane: Int): Boolean = lit[lane] || touched[lane]

    fun answered(lane: Int, isLit: Boolean) {
        lit[lane] = isLit
        touched[lane] = false
    }

    fun reset() {
        lit.fill(false)
        touched.fill(false)
    }
}

/** The runtime's `noteYPosition`, as [NoteLayout] and [TapLine] take it. */
fun interface NoteY {
    fun at(objectMs: Double, renderMs: Double, scrollTimeMs: Double, receptorY: Double): Double
}

/**
 * Reusable hold coordinates in lane-local y (0 at the far end, `fallLength` at the receptor line), matching
 * `ensomi_gameplay::layout::Geometry::Hold` without allocating per note per frame.
 */
class HoldPlacement {
    var bodyTopY: Double = 0.0
    var bodyBottomY: Double = 0.0
    var headY: Double = 0.0
    var hasTail: Boolean = false
    var tailY: Double = 0.0
}

/**
 * Where taps and holds are drawn, without a runtime call per note per frame. Two answers from [noteY] fix the linear
 * time-to-y mapping whenever scroll time or fall length changes, and holds follow `ensomi_gameplay::layout::geometry`
 * directly in Kotlin with zero per-note allocations. Judgement remains in `GameplaySession`; `RuntimeCheck` holds this
 * layout to `noteGeometry`.
 */
class NoteLayout(private val noteY: NoteY) {
    private var scrollTimeMs = Double.NaN
    private var fallLength = Double.NaN
    private var lineY = 0.0
    private var perMs = 0.0

    private fun calibrate(scrollTimeMs: Double, fallLength: Double) {
        if (scrollTimeMs == this.scrollTimeMs && fallLength == this.fallLength) return
        this.scrollTimeMs = scrollTimeMs
        this.fallLength = fallLength
        val span = maxOf(scrollTimeMs, 1.0)
        lineY = noteY.at(0.0, 0.0, scrollTimeMs, fallLength)
        perMs = (noteY.at(span, 0.0, scrollTimeMs, fallLength) - lineY) / span
    }

    /** A tap (or raw timestamp) at [timeMs], drawn at [renderMs], on lanes [fallLength] long. */
    fun y(timeMs: Double, renderMs: Double, scrollTimeMs: Double, fallLength: Double): Double {
        calibrate(scrollTimeMs, fallLength)
        return lineY + (timeMs - renderMs) * perMs
    }

    /**
     * Fills [out] with hold coordinates matching `ensomi_gameplay::layout::geometry`.
     * When [holding] is true (`VisibleState.HOLDING`), head and tail y are clamped to `min(rawY, receptorY)`.
     * When [endTimeMs] is null (`VisibleState.OPEN_ENDED` or an open-ended hold being held), [HoldPlacement.hasTail]
     * is false and the body extends from the far end (`0.0`) to `headY`, at least [MINIMUM_HOLD_BODY_HEIGHT] tall.
     */
    fun hold(
        startTimeMs: Double,
        endTimeMs: Double?,
        holding: Boolean,
        renderMs: Double,
        scrollTimeMs: Double,
        fallLength: Double,
        out: HoldPlacement,
        shortenMs: Double = 0.0,
    ) {
        calibrate(scrollTimeMs, fallLength)
        val rawHead = lineY + (startTimeMs - renderMs) * perMs
        val headY = if (holding) minOf(rawHead, lineY) else rawHead
        val rawTopY: Double
        val rawBottomY: Double
        if (endTimeMs != null) {
            val visualEndMs = if (shortenMs > 0.0) maxOf(startTimeMs, endTimeMs - shortenMs) else endTimeMs
            val rawTail = lineY + (visualEndMs - renderMs) * perMs
            val tailY = if (holding) minOf(rawTail, lineY) else rawTail
            out.hasTail = true
            out.tailY = tailY
            rawTopY = minOf(headY, tailY)
            rawBottomY = maxOf(headY, tailY)
        } else {
            out.hasTail = false
            out.tailY = 0.0
            rawTopY = minOf(0.0, headY)
            rawBottomY = maxOf(0.0, headY)
        }
        val bodyHeight = maxOf(rawBottomY - rawTopY, MINIMUM_HOLD_BODY_HEIGHT)
        out.bodyTopY = rawBottomY - bodyHeight
        out.bodyBottomY = rawBottomY
        out.headY = headY
    }

    companion object {
        /** Matches `ensomi_gameplay::layout::MINIMUM_HOLD_BODY_HEIGHT`. */
        const val MINIMUM_HOLD_BODY_HEIGHT = 12.0
    }
}

/**
 * Where a tap is drawn, without a call per note per frame. Thin wrapper over [NoteLayout] kept for existing checks.
 */
class TapLine(noteY: NoteY) {
    private val layout = NoteLayout(noteY)

    /** A tap at [timeMs], drawn at [renderMs], on lanes [fallLength] long. */
    fun y(timeMs: Double, renderMs: Double, scrollTimeMs: Double, fallLength: Double): Double =
        layout.y(timeMs, renderMs, scrollTimeMs, fallLength)
}
