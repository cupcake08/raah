package app.raah.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.raah.RaahApp
import app.raah.geo.Geo
import app.raah.geo.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rides a route at a desk by standing in for the GPS (debug builds only). With Location on, fixes go
 * through a mock GPS provider, so the app must be the mock location app first:
 *   adb shell appops set app.raah android:mock_location allow
 * With Location off they are handed to the app directly.
 *
 *   adb shell am broadcast -n app.raah/.debug.DemoRide -a app.raah.debug.FIX  --ed lat 12.9716 --ed lon 77.6412
 *   adb shell am broadcast -n app.raah/.debug.DemoRide -a app.raah.debug.GO   --es query "Indiranagar metro"
 *   adb shell am broadcast -n app.raah/.debug.DemoRide -a app.raah.debug.RIDE --ef kmh 25 [--ef detour_at 400]
 *   adb shell am broadcast -n app.raah/.debug.DemoRide -a app.raah.debug.STOP
 *
 * FIX holds a position, GO searches and starts navigating to the first result, RIDE follows the
 * current route from the nearest point (optionally leaving it sideways after detour_at metres, then
 * riding the route the app reroutes onto), STOP hands the GPS back.
 */
class DemoRide : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            "app.raah.debug.FIX" -> Sim.hold(app, LatLon(intent.getDoubleExtra("lat", 0.0), intent.getDoubleExtra("lon", 0.0)))
            "app.raah.debug.GO" -> Sim.go(app, intent.getStringExtra("query") ?: return)
            "app.raah.debug.RIDE" -> Sim.ride(app, intent.getFloatExtra("kmh", 25f).toDouble(),
                intent.getFloatExtra("detour_at", -1f).toDouble())
            "app.raah.debug.STOP" -> Sim.stop(app)
        }
    }
}

private object Sim {
    private const val TAG = "DemoRide"
    private const val GPS = LocationManager.GPS_PROVIDER
    private const val DETOUR_M = 80.0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var at: LatLon? = null

    fun hold(ctx: Context, p: LatLon) {
        job?.cancel()
        at = p
        job = scope.launch { while (isActive) { push(ctx, p, 0.0, 0.0); delay(1000) } }
        Log.i(TAG, "holding at $p")
    }

    fun go(ctx: Context, query: String) {
        val engine = RaahApp.engine(ctx)
        scope.launch {
            val place = runCatching { engine.search(query).firstOrNull() }.onFailure { Log.w(TAG, "search failed", it) }.getOrNull()
            if (place == null) { Log.w(TAG, "nothing found for '$query'"); return@launch }
            Log.i(TAG, "navigating to ${place.name} (${place.detail})")
            engine.navigateTo(place)
        }
    }

    fun ride(ctx: Context, kmh: Double, detourAt: Double) {
        val engine = RaahApp.engine(ctx)
        if ((engine.ui.value.route?.shape?.size ?: 0) < 2) { Log.w(TAG, "no route to ride"); return }
        job?.cancel()
        job = scope.launch {
            var detour = detourAt
            while (true) {
                val shape = engine.ui.value.route?.shape ?: return@launch
                if (follow(ctx, shape, kmh / 3.6, detour)) break
                // Off the route: stand there until the engine has a new one, then ride that one.
                detour = -1.0
                val p = at!!
                Log.i(TAG, "off the route, waiting for the reroute")
                withTimeoutOrNull(30_000) {
                    while (engine.ui.value.route?.shape === shape) { push(ctx, p, 0.0, 0.0); delay(1000) }
                } ?: run { Log.w(TAG, "no reroute came"); break }
                Log.i(TAG, "rerouted")
            }
            hold(ctx, at!!)
        }
    }

    /**
     * Rides [shape] from the point nearest the rider at [v] m/s, one fix a second. With [detourAt]
     * set, drifts off to the right after that many metres (8 m more each second) and returns false
     * once [DETOUR_M] off; true at the end of the route.
     */
    private suspend fun follow(ctx: Context, shape: List<LatLon>, v: Double, detourAt: Double): Boolean {
        val cum = DoubleArray(shape.size)
        for (i in 1 until shape.size) cum[i] = cum[i - 1] + Geo.distance(shape[i - 1], shape[i])
        val start = at?.let { p -> shape.indices.minBy { Geo.distance(shape[it], p) } } ?: 0
        var s = cum[start]
        var i = start
        var off = 0.0
        Log.i(TAG, "riding ${"%.0f".format(cum.last() - s)} m at ${"%.0f".format(v * 3.6)} km/h")
        while (true) {
            while (i < shape.size - 2 && cum[i + 1] < s) i++
            val seg = cum[i + 1] - cum[i]
            val t = if (seg > 0) ((s - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
            val a = shape[i]; val b = shape[i + 1]
            var p = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
            val brg = Geo.bearing(a, b)
            if (detourAt >= 0 && s - cum[start] > detourAt) {
                if (off >= DETOUR_M) return false
                off += 8.0
                val r = Math.toRadians(brg + 90)
                p = LatLon(p.lat + off * Math.cos(r) / 111_320.0,
                    p.lon + off * Math.sin(r) / (111_320.0 * Math.cos(Math.toRadians(p.lat))))
            }
            at = p
            push(ctx, p, if (s >= cum.last()) 0.0 else v, brg)
            if (s >= cum.last()) { Log.i(TAG, "end of route"); return true }
            delay(1000)
            s = minOf(cum.last(), s + v)
        }
    }

    fun stop(ctx: Context) {
        job?.cancel()
        runCatching { lm(ctx).removeTestProvider(GPS) }
        added = false
        Log.i(TAG, "stopped")
    }

    private fun lm(ctx: Context) = ctx.getSystemService(LocationManager::class.java)

    /** Through the mock GPS provider when Location is on, else straight into the app (Location off). */
    private fun push(ctx: Context, p: LatLon, mps: Double, bearing: Double) {
        val lm = lm(ctx)
        val loc = Location(GPS).apply {
            latitude = p.lat; longitude = p.lon
            accuracy = 4f
            speed = mps.toFloat()
            if (mps > 0) this.bearing = ((bearing % 360 + 360) % 360).toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        if (!lm.isLocationEnabled) {
            RaahApp.engine(ctx).onLocation(loc)
            return
        }
        try {
            if (!added) addProvider(lm)
            lm.setTestProviderLocation(GPS, loc)
        } catch (e: SecurityException) {
            Log.e(TAG, "not the mock location app: adb shell appops set app.raah android:mock_location allow")
            job?.cancel()
        }
    }

    private var added = false

    private fun addProvider(lm: LocationManager) {
        runCatching { lm.removeTestProvider(GPS) }
        if (Build.VERSION.SDK_INT >= 31) {
            lm.addTestProvider(GPS, false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(GPS, false, false, false, false, true, true, true, 1, 1)
        }
        lm.setTestProviderEnabled(GPS, true)
        added = true
    }
}
