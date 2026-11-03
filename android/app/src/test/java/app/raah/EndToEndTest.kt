package app.raah

import app.raah.geo.Geo
import app.raah.geo.LatLon
import app.raah.proto.MapProtocol
import app.raah.proto.NavProtocol
import app.raah.ride.DeviceSink
import app.raah.ride.Fix
import app.raah.ride.MapFeed
import app.raah.ride.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Rides the fixture route at 22 km/h with one GPS fix per second and records
 * every BLE write the app would make. `raah_sim <out> replay <capture>` feeds the
 * capture through the firmware's decoders and renders the snapshots, so this
 * covers the whole path: route → tiles → map packing → wire format → device UI.
 */
class EndToEndTest {
    private class Capture(file: File) : DeviceSink {
        val out = DataOutputStream(FileOutputStream(file).buffered())
        var feed: MapFeed? = null
        var stateBytes = 0
        var poseBytes = 0
        val maps = ArrayList<IntArray>()       // id, pts, lines, route pts, bytes, writes
        override val maxWrite = 244

        fun record(kind: Int, b: ByteArray) {
            out.write(kind)
            out.write(b.size and 0xFF)
            out.write(b.size shr 8)
            out.write(b)
        }

        override fun state(bytes: ByteArray) { stateBytes += bytes.size; record(1, bytes) }
        override fun pose(bytes: ByteArray) { poseBytes += bytes.size; record(5, bytes) }
        override fun map(chunks: List<ByteArray>) {
            chunks.forEach { record(5, it) }
            val begin = chunks.first()
            fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            val id = u16(begin, 1)
            maps += intArrayOf(id, u16(begin, 3), u16(begin, 5), u16(begin, 7), chunks.sumOf { it.size }, chunks.size)
            feed?.onDeviceMapId(id)     // the device confirms over STATUS
        }
        fun snap(name: String) = record(0xFF, name.toByteArray())
    }

    @Test
    fun rideTheRoute() {
        val file = File(System.getProperty("raah.capture") ?: "build/raah_capture.bin")
        file.parentFile?.mkdirs()
        val cap = Capture(file)
        val route = Fixtures.route
        val feed = MapFeed(Fixtures.roads(), cap) { it() }
        cap.feed = feed
        val zone = ZoneId.of("Asia/Kolkata")
        val nav = Navigator(route, "Sri Krishna Temple Road", cap, feed, zone)

        val cum = DoubleArray(route.shape.size)
        for (i in 1 until cum.size) cum[i] = cum[i - 1] + Geo.distance(route.shape[i - 1], route.shape[i])
        fun at(d: Double): Pair<LatLon, Double> {
            val i = (cum.indexOfFirst { it > d }.takeIf { it > 0 } ?: cum.lastIndex).coerceAtLeast(1)
            val a = route.shape[i - 1]
            val b = route.shape[i]
            val t = ((d - cum[i - 1]) / (cum[i] - cum[i - 1])).coerceIn(0.0, 1.0)
            return LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)) to Geo.bearing(a, b)
        }
        val turn = { k: Int -> cum[route.steps[k].beginIdx] }
        val shots = sortedMapOf(
            900.0 to "e2e_1_cruise",
            turn(3) - 150 to "e2e_2_near",
            turn(4) - 24 to "e2e_3_turn_now",
            cum.last() to "e2e_4_arrived",
        )
        val t0 = ZonedDateTime.of(2026, 11, 2, 10, 30, 0, 0, zone).toInstant().toEpochMilli()
        val speed = 6.0
        var d = 0.0
        var s = 0
        while (true) {
            val (pos, brg) = at(d)
            nav.onFix(Fix(pos, 5f, speed.toFloat(), brg.toFloat(), t0 + s * 1000L))
            shots.headMap(d + 1e-6).keys.toList().forEach { cap.snap(shots.remove(it)!!) }
            if (d >= cum.last()) break
            d = minOf(d + speed, cum.last())
            s++
        }
        cap.out.close()

        val st = nav.lastState!!
        assertTrue(st.flags and NavProtocol.F_ARRIVED != 0)
        assertTrue(shots.isEmpty())
        assertTrue(cap.maps.isNotEmpty())
        cap.maps.forEach { m -> assertTrue(m[1] <= MapProtocol.MAX_PTS && m[2] <= MapProtocol.MAX_LINES) }

        val report = buildString {
            appendLine("ride: ${route.lengthM.toInt()} m in $s s, ${cap.maps.size} map(s)")
            cap.maps.forEach { (id, pts, lines, rpts, bytes, writes) ->
                appendLine("map $id: $pts pts, $lines roads, $rpts route pts -> $bytes bytes in $writes writes")
            }
            appendLine("STATE: ${cap.stateBytes} bytes (${cap.stateBytes / s} B/s), POSE: ${cap.poseBytes} bytes (${cap.poseBytes / s} B/s)")
        }
        File(file.path.removeSuffix(".bin") + "_stats.txt").writeText(report)
        println(report)
        assertEquals(1, 1)
    }

    private operator fun IntArray.component6() = this[5]
}
