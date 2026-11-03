package app.raah.route

import app.raah.geo.Geo
import app.raah.geo.LatLon
import app.raah.geo.LocalFrame
import kotlin.math.hypot

/** Where the rider is along a route, and what comes next. */
data class Progress(
    val snapped: LatLon,
    /** Route segment the rider is on (shape index of its start). */
    val segIdx: Int,
    /** Distance from the rider to the route line. */
    val offRouteM: Double,
    /** Index into [Route.steps] of the next maneuver. */
    val stepIdx: Int,
    val distToTurnM: Double,
    val distLeftM: Double,
    val timeLeftS: Double,
    /** Direction of the route just ahead of the rider, degrees from north. */
    val routeBearing: Double,
    val arrived: Boolean,
)

/** Matches GPS fixes to a route, searching forward from the last match so it can't jump onto a parallel leg. */
class RouteTracker(val route: Route) {
    private val shape = route.shape
    private val cum = DoubleArray(shape.size).also { c ->
        for (i in 1 until shape.size) c[i] = c[i - 1] + Geo.distance(shape[i - 1], shape[i])
    }
    private val total = cum.last()
    private var seg = 0
    private var offCount = 0

    /** True once the rider has been off the route for several fixes in a row. */
    var offRoute = false
        private set

    fun update(pos: LatLon, accuracyM: Float): Progress {
        val frame = LocalFrame(pos)
        var best = Double.MAX_VALUE
        var bestSeg = seg
        var bestT = 0.0
        val lo = (seg - 3).coerceAtLeast(0)
        val hi = (seg + 80).coerceAtMost(shape.size - 2)
        for (i in lo..hi) {
            val ax = frame.x(shape[i]); val ay = frame.y(shape[i])
            val bx = frame.x(shape[i + 1]); val by = frame.y(shape[i + 1])
            val t = Geo.segParam(0.0, 0.0, ax, ay, bx, by)
            val d = hypot(ax + t * (bx - ax), ay + t * (by - ay))
            // Prefer staying put: a later segment has to be clearly closer to win.
            if (d < best - if (i > seg) 2.0 else 0.0) { best = d; bestSeg = i; bestT = t }
        }
        val limit = maxOf(35.0, accuracyM * 1.5)
        offCount = if (best > limit) offCount + 1 else 0
        offRoute = offCount >= 3
        if (best <= limit) seg = bestSeg

        val a = shape[bestSeg]
        val b = shape[bestSeg + 1]
        val snapped = LatLon(a.lat + bestT * (b.lat - a.lat), a.lon + bestT * (b.lon - a.lon))
        val along = cum[bestSeg] + bestT * (cum[bestSeg + 1] - cum[bestSeg])

        // Next maneuver: the first one whose point is still ahead. Step 0 is the departure, which is still
        // "ahead" while the rider waits at (or behind) the start of the route: then the next is step 1.
        var stepIdx = route.steps.indexOfFirst { it.beginIdx > bestSeg || (it.beginIdx == bestSeg && bestT < 0.01) }
            .let { if (it < 0) route.steps.lastIndex else it }.coerceIn(0, maxOf(0, route.steps.lastIndex))
        while (stepIdx < route.steps.lastIndex && route.steps[stepIdx].beginIdx == 0) stepIdx++
        val step = route.steps[stepIdx]
        val distToTurn = (cum[step.beginIdx.coerceIn(0, cum.lastIndex)] - along).coerceAtLeast(0.0)
        val distLeft = (total - along).coerceAtLeast(0.0)

        // Time: what's left of the current step at its own pace, plus the steps after it.
        val cur = route.steps.getOrNull(stepIdx - 1)
        val curFrac = if (cur != null && cur.lengthM > 1) (distToTurn / cur.lengthM).coerceIn(0.0, 1.0) else 0.0
        val timeLeft = (cur?.timeS ?: 0.0) * curFrac + route.steps.drop(stepIdx).sumOf { it.timeS }

        return Progress(
            snapped = snapped,
            segIdx = bestSeg,
            offRouteM = best,
            stepIdx = stepIdx,
            distToTurnM = distToTurn,
            distLeftM = distLeft,
            timeLeftS = timeLeft,
            routeBearing = bearingAhead(snapped, along, bestSeg) ?: Geo.bearing(a, b),
            arrived = distLeft < 25 || Geo.distance(pos, shape.last()) < 20,
        )
    }

    /**
     * From the rider to the route [BEARING_AHEAD_M] further on. Heading-up maps follow it, and it swings
     * into a bend as the rider reaches it, where the bearing of the segment underfoot would flip all at
     * once at the corner (and wobble along a shape of short GPS-traced segments).
     */
    private fun bearingAhead(from: LatLon, along: Double, seg: Int): Double? {
        val target = along + BEARING_AHEAD_M
        var i = seg
        while (i < shape.size - 2 && cum[i + 1] < target) i++
        val len = cum[i + 1] - cum[i]
        val t = if (len > 0) ((target - cum[i]) / len).coerceIn(0.0, 1.0) else 1.0
        val a = shape[i]; val b = shape[i + 1]
        val p = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        return if (Geo.distance(from, p) < 2.0) null else Geo.bearing(from, p)
    }

    private companion object {
        const val BEARING_AHEAD_M = 15.0
    }
}
