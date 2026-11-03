package app.raah.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Flat metres east (x) / north (y) of an anchor. Accurate to well under a metre within a few km. */
class LocalFrame(val anchor: LatLon) {
    private val kx = M_PER_DEG * cos(Math.toRadians(anchor.lat))

    fun x(p: LatLon) = (p.lon - anchor.lon) * kx
    fun y(p: LatLon) = (p.lat - anchor.lat) * M_PER_DEG
    fun toLatLon(x: Double, y: Double) = LatLon(anchor.lat + y / M_PER_DEG, anchor.lon + x / kx)

    companion object {
        const val M_PER_DEG = 111_320.0
    }
}

object Geo {
    private const val R = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from a to b, degrees clockwise from north. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Smallest signed difference a - b in degrees, in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    /** Google encoded polyline with 6 decimal places (Valhalla's shape format). */
    fun decodePolyline6(s: String): List<LatLon> {
        val out = ArrayList<LatLon>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = s[i++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < s.length) {
            lat += next()
            lon += next()
            out += LatLon(lat / 1e6, lon / 1e6)
        }
        return out
    }

    /** Douglas-Peucker on interleaved x, y. Returns the kept indices. */
    fun simplify(xy: DoubleArray, tol: Double): IntArray {
        val n = xy.size / 2
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var dmax = 0.0
            var idx = -1
            for (k in a + 1 until b) {
                val d = segDistance(xy[2 * k], xy[2 * k + 1], xy[2 * a], xy[2 * a + 1], xy[2 * b], xy[2 * b + 1])
                if (d > dmax) { dmax = d; idx = k }
            }
            if (idx >= 0 && dmax > tol) {
                keep[idx] = true
                stack.addLast(a to idx)
                stack.addLast(idx to b)
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    /** Distance from p to segment ab, all in the same flat frame. */
    fun segDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val t = segParam(px, py, ax, ay, bx, by)
        return hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
    }

    /** Position of p's projection along ab, clamped to [0, 1]. */
    fun segParam(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 0) return 0.0
        return (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    }

    fun headingDelta(a: Double, b: Double) = abs(angleDiff(a, b))

    const val DEG = 180 / PI
}
