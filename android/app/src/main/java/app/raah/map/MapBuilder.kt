package app.raah.map

import app.raah.geo.Geo
import app.raah.geo.LatLon
import app.raah.geo.LocalFrame
import app.raah.proto.MapProtocol
import app.raah.proto.MapProtocol.Line
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A map sent (or being sent) to the device: geometry in metres around [anchor]. */
class MapWindow(val anchor: LatLon, val radiusM: Double, val payload: MapProtocol.Payload) {
    val frame = LocalFrame(anchor)
    val id get() = payload.id
}

/** Cuts roads and the route to a disc around an anchor and packs them into the device's budget. */
object MapBuilder {
    private const val ROAD_TOL_M = 1.5
    private const val ROUTE_TOL_M = 1.0
    private const val TRAIL_M = 150.0

    /**
     * @param route full route shape, or empty when not navigating
     * @param routeFrom index of the route segment the rider is on; the trail behind it is kept short
     */
    fun build(id: Int, anchor: LatLon, radiusM: Double, roads: List<Road>, route: List<LatLon>, routeFrom: Int): MapWindow {
        val frame = LocalFrame(anchor)
        val routeXy = routeXy(frame, radiusM, route, routeFrom)
        var r = radiusM
        var dropService = false
        while (true) {
            val packed = packRoads(frame, r, roads, dropService)
            if (packed != null) {
                val (pts, lines) = packed
                return MapWindow(anchor, r, MapProtocol.Payload(id, pts, lines, routeXy))
            }
            if (!dropService) dropService = true else r *= 0.8
        }
    }

    private fun packRoads(frame: LocalFrame, r: Double, roads: List<Road>, dropService: Boolean): Pair<ShortArray, List<Line>>? {
        val pts = ArrayList<Short>()
        val lines = ArrayList<Line>()
        // Minor classes first; the device draws class by class anyway.
        for (road in roads.sortedBy { it.cls }) {
            if (dropService && road.cls == MapProtocol.CLASS_SERVICE) continue
            for (run in clip(frame, r, road.latLon)) {
                val keep = Geo.simplify(run, ROAD_TOL_M)
                var start = 0
                while (start < keep.size - 1) {
                    val end = minOf(keep.size, start + MapProtocol.MAX_LINE_PTS)
                    if (lines.size == MapProtocol.MAX_LINES || pts.size / 2 + (end - start) > MapProtocol.MAX_PTS) return null
                    lines += Line(road.cls, end - start, pts.size / 2)
                    for (k in start until end) {
                        pts += short(run[2 * keep[k]])
                        pts += short(run[2 * keep[k] + 1])
                    }
                    start = end - 1     // chunks share their joining point
                }
            }
        }
        return pts.toShortArray() to lines
    }

    /** Parts of a polyline with at least one end of each segment inside the disc, as interleaved x, y metres. */
    private fun clip(frame: LocalFrame, r: Double, ll: DoubleArray): List<DoubleArray> {
        val n = ll.size / 2
        val xy = DoubleArray(ll.size)
        val inside = BooleanArray(n)
        for (i in 0 until n) {
            val p = LatLon(ll[2 * i], ll[2 * i + 1])
            xy[2 * i] = frame.x(p)
            xy[2 * i + 1] = frame.y(p)
            inside[i] = hypot(xy[2 * i], xy[2 * i + 1]) <= r
        }
        val runs = ArrayList<DoubleArray>()
        var i = 0
        while (i < n - 1) {
            if (!inside[i] && !inside[i + 1]) { i++; continue }
            val start = i
            while (i < n - 1 && (inside[i] || inside[i + 1])) i++
            runs += xy.copyOfRange(2 * start, 2 * i + 2)
        }
        return runs
    }

    private fun routeXy(frame: LocalFrame, r: Double, route: List<LatLon>, from: Int): ShortArray {
        if (route.size < 2) return ShortArray(0)
        var start = from.coerceIn(0, route.size - 2)
        var back = 0.0
        while (start > 0 && back < TRAIL_M) {
            back += Geo.distance(route[start - 1], route[start])
            start--
        }
        var end = from.coerceIn(0, route.size - 2) + 1
        while (end < route.size - 1 && hypot(frame.x(route[end]), frame.y(route[end])) <= r) end++
        val xy = DoubleArray(2 * (end - start + 1))
        for (k in start..end) {
            xy[2 * (k - start)] = frame.x(route[k])
            xy[2 * (k - start) + 1] = frame.y(route[k])
        }
        var tol = ROUTE_TOL_M
        var keep = Geo.simplify(xy, tol)
        while (keep.size > MapProtocol.MAX_ROUTE) {
            tol *= 2
            keep = Geo.simplify(xy, tol)
        }
        return ShortArray(keep.size * 2) { short(xy[2 * keep[it / 2] + it % 2]) }
    }

    private fun short(v: Double): Short = v.roundToInt().coerceIn(-32767, 32767).toShort()
}
