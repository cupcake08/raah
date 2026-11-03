package app.raah.ride

import app.raah.geo.Geo
import app.raah.geo.LatLon
import app.raah.geo.LocalFrame
import app.raah.map.MapBuilder
import app.raah.map.MapWindow
import app.raah.map.RoadSource
import app.raah.proto.Maneuver
import app.raah.proto.MapProtocol
import app.raah.proto.NavProtocol
import app.raah.proto.NavProtocol.NavState
import app.raah.route.Route
import app.raah.route.RouteTracker
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A GPS fix. */
class Fix(val pos: LatLon, val accuracyM: Float, val speedMps: Float?, val bearingDeg: Float?, val timeMs: Long)

/** Where navigation output goes: the BLE link in the app, a capture file in tests. */
interface DeviceSink {
    val maxWrite: Int
    fun state(bytes: ByteArray)
    fun pose(bytes: ByteArray)
    fun map(chunks: List<ByteArray>)
}

/**
 * Keeps the device supplied with road geometry around the rider. Each map is a
 * disc centred a little ahead of the rider; a new one is sent once the rider has
 * moved far enough that the screen could run off the edge. Poses are only sent
 * in the frame of a map the device has confirmed (STATUS map id).
 */
class MapFeed(
    private val roads: RoadSource,
    private val sink: DeviceSink,
    private val runAsync: (() -> Unit) -> Unit,
) {
    private var nextId = Random.nextInt(1, 0xF000)
    @Volatile private var current: MapWindow? = null
    @Volatile private var pending: MapWindow? = null
    private var pendingSentMs = 0L
    @Volatile private var building = false
    @Volatile private var deviceMapId = 0

    fun reset() {
        current = null
        pending = null
    }

    /** STATUS from the device: the map it has received complete. */
    fun onDeviceMapId(id: Int) {
        deviceMapId = id
        val p = pending ?: return
        if (p.id == id) {
            current = p
            pending = null
        }
    }

    fun update(pos: LatLon, heading: Double, mPerPx: Double, route: List<LatLon>, routeSeg: Int, nowMs: Long) {
        val cur = current
        val p = pending
        if (p != null && nowMs - pendingSentMs > ACK_TIMEOUT_MS) {
            // Never confirmed (dropped, or the device was still busy): send it again under a new id.
            pending = null
        }
        val stale = cur == null || Geo.distance(pos, cur.anchor) > REFRESH_M
        if (stale && pending == null && !building) {
            building = true
            val anchor = ahead(pos, heading, route, routeSeg)
            val id = nextId
            nextId = if (nextId >= 0xFFFF) 1 else nextId + 1
            runAsync {
                try {
                    val w = MapBuilder.build(id, anchor, RADIUS_M, roads.roadsAround(anchor, RADIUS_M), route, routeSeg)
                    sink.map(MapProtocol.encode(w.payload, sink.maxWrite))
                    pending = w
                    pendingSentMs = nowMs
                    if (deviceMapId == id) onDeviceMapId(id)
                } finally {
                    building = false
                }
            }
        }
        val w = current ?: return
        sink.pose(MapProtocol.pose(w.id, w.frame.x(pos), w.frame.y(pos), heading, mPerPx))
    }

    /** Centre the next map ahead of the rider so most of it is road still to come. */
    private fun ahead(pos: LatLon, heading: Double, route: List<LatLon>, seg: Int): LatLon {
        if (route.size >= 2) {
            var left = LEAD_M
            var i = seg.coerceIn(0, route.size - 2) + 1
            var at = pos
            while (i < route.size) {
                val d = Geo.distance(at, route[i])
                if (d >= left) {
                    val t = left / d
                    return LatLon(at.lat + t * (route[i].lat - at.lat), at.lon + t * (route[i].lon - at.lon))
                }
                left -= d
                at = route[i++]
            }
            return at
        }
        val h = Math.toRadians(heading)
        return LocalFrame(pos).toLatLon(LEAD_M * sin(h), LEAD_M * cos(h))
    }

    companion object {
        const val RADIUS_M = 1000.0
        const val LEAD_M = 300.0
        /** At 2.6 m/px the screen shows ~500 m ahead, so refresh while 500 m of map remain ahead. */
        const val REFRESH_M = RADIUS_M - LEAD_M - 200.0
        const val ACK_TIMEOUT_MS = 6000L
    }
}

/** Turns GPS fixes into turn-by-turn state and map poses for one route. */
class Navigator(
    route: Route,
    private val destination: String,
    private val sink: DeviceSink,
    private val maps: MapFeed?,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    var route = route
        private set
    private var tracker = RouteTracker(route)
    private var heading = 0.0
    private var zoom = 1.2
    var rerouting = false
        private set
    var lastState: NavState? = null
        private set

    val needsReroute get() = tracker.offRoute && !rerouting

    fun beginReroute() {
        rerouting = true
    }

    fun replaceRoute(r: Route) {
        route = r
        tracker = RouteTracker(r)
        rerouting = false
        maps?.reset()
    }

    fun onFix(fix: Fix) {
        val p = tracker.update(fix.pos, fix.accuracyM)
        val speedKmh = fix.speedMps?.let { it * 3.6 }

        // Heading-up: follow the road when on it (steady), else the GPS course once moving.
        val onRoute = p.offRouteM < 25
        heading = when {
            onRoute -> p.routeBearing
            fix.bearingDeg != null && (fix.speedMps ?: 0f) > 1.5f -> fix.bearingDeg.toDouble()
            else -> heading
        }
        // Zoom out with speed. Closing on a turn, zoom in with it so the turn holds about TURN_AHEAD_PX
        // ahead of the rider, in view with the street it leads into, down to a close-up at the corner.
        var target = (0.9 + (speedKmh ?: 0.0) * 0.04).coerceIn(0.9, 2.6)
        target = minOf(target, (p.distToTurnM / TURN_AHEAD_PX).coerceAtLeast(0.6))
        zoom += (target - zoom) * 0.35

        val step = route.steps[p.stepIdx]
        val arrived = p.arrived
        var flags = NavProtocol.F_NAVIGATING
        if (rerouting) flags = flags or NavProtocol.F_REROUTING
        if (arrived) flags = flags or NavProtocol.F_ARRIVED
        val eta = Instant.ofEpochMilli(fix.timeMs + (p.timeLeftS * 1000).toLong()).atZone(zone)
        val state = NavState(
            flags = flags,
            maneuver = if (arrived) Maneuver.ARRIVE else step.maneuver,
            exitNum = step.exitNum,
            distToTurnM = p.distToTurnM.toLong(),
            distLeftM = p.distLeftM.toLong(),
            timeLeftS = p.timeLeftS.toLong(),
            etaMinOfDay = eta.hour * 60 + eta.minute,
            speedKmh = speedKmh,
            primary = if (arrived || step.maneuver == Maneuver.ARRIVE) destination else step.street.ifEmpty { step.instruction },
            secondary = if (arrived) "" else step.instruction,
        )
        lastState = state
        sink.state(NavProtocol.encodeState(state))
        maps?.update(if (onRoute) p.snapped else fix.pos, heading, zoom, route.shape, p.segIdx, fix.timeMs)
    }

    private companion object {
        /** Where the turn sits on the device as it nears: about 2/3 of the way up from rider to map top. */
        const val TURN_AHEAD_PX = 110.0
    }
}
