package app.raah.ride

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import app.raah.ble.RaahLink
import app.raah.geo.LatLon
import app.raah.geo.Geo
import app.raah.map.OpenFreeMapTiles
import app.raah.map.Road
import app.raah.map.RoadSource
import app.raah.proto.MapProtocol
import app.raah.proto.NavProtocol
import app.raah.route.PhoneGeocoder
import app.raah.route.Photon
import app.raah.route.Place
import app.raah.route.PlaceBook
import app.raah.route.SharedPlace
import app.raah.route.Route
import app.raah.route.Valhalla
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** Everything a ride needs, shared by the service (lifetime) and the activity (controls). */
class RideEngine(private val context: Context) {

    data class Ui(
        val navigating: Boolean = false,
        val destination: Place? = null,
        val route: Route? = null,
        val nav: NavProtocol.NavState? = null,
        val mirroring: Boolean = false,
        /** Google Maps' arrow for the mirrored turn, as the device draws it: an alpha mask, ICON_PX square. */
        val mirrorIcon: ByteArray? = null,
        val error: String? = null,
        val lastFix: LatLon? = null,
        /** A destination picked but not started yet: the route to look over before riding. */
        val preview: Preview? = null,
        val home: Place? = null,
        val work: Place? = null,
        val recent: List<Place> = emptyList(),
    )

    data class Preview(val place: Place, val route: Route? = null, val error: String? = null,
                       /** Streets around the route, drawn faintly behind it; empty for long routes or while loading. */
                       val roads: List<Road> = emptyList()) {
        val planning get() = route == null && error == null
    }

    val settings = SettingsStore(context)
    val link = RaahLink(context)
    private val places = PlaceBook(context.getSharedPreferences("raah_places", Context.MODE_PRIVATE))
    private val _ui = MutableStateFlow(Ui(home = places.home, work = places.work, recent = places.recent))
    val ui: StateFlow<Ui> = _ui

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val valhalla = Valhalla()
    private val photon = Photon()
    private val geocoder = PhoneGeocoder(context)
    private val roads = RoadSource(OpenFreeMapTiles(context.cacheDir))
    private val sentIcons = HashSet<Long>()

    private val sink = object : DeviceSink {
        override val maxWrite get() = link.maxWrite
        override fun state(bytes: ByteArray) {
            if (link.ready) link.sendUrgent(NavProtocol.STATE, bytes, "state")
        }
        override fun pose(bytes: ByteArray) {
            if (link.ready) link.sendUrgent(NavProtocol.MAP, bytes, "pose")
        }
        override fun map(chunks: List<ByteArray>) {
            if (link.ready) link.sendBulk(NavProtocol.MAP, chunks)
        }
    }
    private val maps = MapFeed(roads, sink) { work -> scope.launch(Dispatchers.IO) { runCatching(work).onFailure { Log.w(TAG, "map", it) } } }
    private var navigator: Navigator? = null
    private var lastFix: Fix? = null
    /** Where the phone last was, however long ago: enough to aim a search before the first fix comes in. */
    private var lastKnownPos: LatLon? = null
    private var rerouteJob: Job? = null
    private var arriveJob: Job? = null
    private var planJob: Job? = null
    /** Start the planned route as soon as it arrives (the debug ride simulator's GO). */
    private var autoStart = false

    init {
        link.onReady = {
            sentIcons.clear()
            maps.reset()
            sendClock()
            sendSettings()
            settings.update { it.copy(deviceAddress = (link.state.value as? RaahLink.State.Ready)?.address ?: it.deviceAddress) }
        }
        scope.launch { link.status.collect { s -> s?.let { maps.onDeviceMapId(it.mapId) } } }
        scope.launch {
            settings.flow.map { listOf(it.imperial, it.use24h, it.backlight, it.brightness) }.distinctUntilChanged()
                .collectLatest { if (link.ready) sendSettings() }
        }
    }

    // ---- lifecycle (driven by RideService) ----

    fun connect() = link.start(settings.value.deviceAddress)

    fun disconnect() {
        stopNavigation()
        link.stop()
    }

    @SuppressLint("MissingPermission")
    fun startLocation() {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        LocationManagerCompat.removeUpdates(lm, locationListener)
        // Fused (Android 12+) blends GPS with Wi-Fi and cell positions, so the phone knows where it is indoors
        // too, where GPS alone never locks; older phones get the network position until GPS does.
        val providers = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER))
            listOf(LocationManager.FUSED_PROVIDER) else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val request = LocationRequestCompat.Builder(1000L).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
        for (p in providers.filter { it in lm.allProviders }) {
            LocationManagerCompat.requestLocationUpdates(lm, p, request, ContextCompat.getMainExecutor(context), locationListener)
        }
        val known = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return
        lastKnownPos = LatLon(known.latitude, known.longitude)
        if (SystemClock.elapsedRealtimeNanos() - known.elapsedRealtimeNanos < LAST_KNOWN_FRESH_NS) onLocation(known)
    }

    fun stopLocation() {
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.removeUpdates(it, locationListener) }
    }

    private val locationListener = LocationListenerCompat { onLocation(it) }

    /** Every GPS fix comes through here (the debug ride simulator calls it directly too). */
    internal fun onLocation(l: Location) {
        val fix = Fix(
            pos = LatLon(l.latitude, l.longitude),
            accuracyM = if (l.hasAccuracy()) l.accuracy else 30f,
            speedMps = if (l.hasSpeed()) l.speed else null,
            bearingDeg = if (l.hasBearing()) l.bearing else null,
            timeMs = System.currentTimeMillis(),
        )
        // A Wi-Fi or cell position, hundreds of metres out, helps a search but mustn't drag a ride off a GPS track.
        lastFix?.let { prev ->
            if (fix.accuracyM > COARSE_M && prev.accuracyM <= COARSE_M && fix.timeMs - prev.timeMs < 10_000) return
        }
        val first = lastFix == null
        lastFix = fix
        _ui.value = _ui.value.copy(lastFix = fix.pos)
        if (first) _ui.value.preview?.takeIf { it.route == null }?.let { plan(it.place) }
        val nav = navigator
        if (nav == null) {
            if (!_ui.value.mirroring) sink.state(NavProtocol.encodeState(NavProtocol.NavState(speedKmh = fix.speedMps?.let { it * 3.6 })))
            return
        }
        nav.onFix(fix)
        _ui.value = _ui.value.copy(nav = nav.lastState)
        if (nav.needsReroute) reroute(fix)
        watchArrival((nav.lastState?.flags ?: 0) and NavProtocol.F_ARRIVED != 0)
    }

    /** Ends the ride a while after arriving, so GPS and the link go quiet without a tap on the phone. */
    private fun watchArrival(arrived: Boolean) {
        if (!arrived) { arriveJob?.cancel(); arriveJob = null; return }
        if (arriveJob?.isActive == true) return
        arriveJob = scope.launch { delay(ARRIVED_END_MS); Log.i(TAG, "arrived, ending the ride"); stopNavigation() }
    }

    // ---- navigation ----

    /**
     * Asks Google (the phone's geocoder) and OpenStreetMap (Photon) together and merges their answers. A pasted
     * Google Maps link or coordinates come back as that one place. Throws when neither answers; the search
     * screen shows that inline.
     */
    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        var text = query
        when (val shared = SharedPlace.parse(query, null)) {
            is SharedPlace.At -> return@withContext listOf(shared.place)
            is SharedPlace.Link -> when (val r = SharedPlace.resolve(shared)) {
                is SharedPlace.At -> return@withContext listOf(r.place)
                is SharedPlace.Query -> text = r.text
                else -> return@withContext emptyList()
            }
            else -> {}
        }
        val near = lastFix?.pos ?: lastKnownPos
        coroutineScope {
            val google = async { runCatching { geocoder.search(text, near) } }
            val osm = async { runCatching { photon.search(text, near) } }
            val g = google.await()
            val o = osm.await()
            if (g.isFailure && o.isFailure) throw o.exceptionOrNull()!!
            Photon.merge(g.getOrDefault(emptyList()), o.getOrDefault(emptyList()), near)
        }
    }

    /**
     * Where a place Google Maps shared by name and address (its links mostly carry no coordinates now) is,
     * from Google's geocoder. Null for a bare name, which could be anywhere: that one goes to the search screen.
     */
    suspend fun locate(text: String): Place? = withContext(Dispatchers.IO) {
        val (name, address) = SharedPlace.nameAndAddress(text)
        if (address.isEmpty()) return@withContext null
        runCatching { geocoder.search(text, lastFix?.pos ?: lastKnownPos, limit = 1) }.getOrNull()?.firstOrNull()
            ?.copy(name = name, detail = address)
    }

    /** Routes to [place] for a look before riding; [start] begins the ride. Waits for a GPS fix if there is none. */
    fun plan(place: Place) {
        planJob?.cancel()
        _ui.value = _ui.value.copy(preview = Preview(place), error = null)
        val from = lastFix ?: return
        planJob = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { valhalla.route(from.pos, place.pos, settings.value.vehicle, from.bearingDeg?.toDouble()) }
            }
            if (_ui.value.preview?.place != place) return@launch
            r.onSuccess { route ->
                _ui.value = _ui.value.copy(preview = Preview(place, route))
                if (autoStart) { start(); return@launch }
                val around = withContext(Dispatchers.IO) { runCatching { roadsAround(route) }.getOrDefault(emptyList()) }
                _ui.value.preview?.takeIf { it.route === route }?.let { _ui.value = _ui.value.copy(preview = it.copy(roads = around)) }
            }.onFailure {
                Log.w(TAG, "route failed", it)
                _ui.value = _ui.value.copy(preview = Preview(place, error = routeError(it)))
            }
        }
    }

    /** The streets a route preview shows for context: the bigger the area, the fewer small streets. */
    private fun roadsAround(route: Route): List<Road> {
        val lats = route.shape.map { it.lat }
        val lons = route.shape.map { it.lon }
        val center = LatLon((lats.min() + lats.max()) / 2, (lons.min() + lons.max()) / 2)
        val radius = route.shape.maxOf { Geo.distance(center, it) }
        if (radius > PREVIEW_ROADS_MAX_M) return emptyList()
        val smallest = if (radius > 2_000) MapProtocol.CLASS_MID else MapProtocol.CLASS_MINOR
        return roads.roadsAround(center, radius + 200).filter { it.cls >= smallest }
    }

    fun cancelPlan() {
        planJob?.cancel()
        autoStart = false
        _ui.value = _ui.value.copy(preview = null)
    }

    /** Starts riding the previewed route. */
    fun start() {
        autoStart = false
        val p = _ui.value.preview ?: return
        val route = p.route ?: return
        arriveJob?.cancel()
        places.remember(p.place)
        navigator = Navigator(route, p.place.name, sink, maps)
        maps.reset()
        _ui.value = _ui.value.copy(navigating = true, destination = p.place, route = route, preview = null, recent = places.recent)
        RideService.start(context)
        lastFix?.let { f -> navigator?.onFix(f); _ui.value = _ui.value.copy(nav = navigator?.lastState) }
    }

    /** Plans and starts in one go, without a look at the route. */
    fun navigateTo(place: Place) {
        autoStart = true
        plan(place)
    }

    fun setHome(p: Place?) { places.setHome(p); _ui.value = _ui.value.copy(home = p) }
    fun setWork(p: Place?) { places.setWork(p); _ui.value = _ui.value.copy(work = p) }
    fun forgetRecent(p: Place) { places.forget(p); _ui.value = _ui.value.copy(recent = places.recent) }

    private fun routeError(e: Throwable) = when {
        e is java.io.IOException && e !is app.raah.net.Http.Error -> "Can't reach the route server. Check your connection."
        (e as? app.raah.net.Http.Error)?.code == 400 -> "No road route to this place."
        else -> "Couldn't find a route (${e.message?.take(60)})"
    }

    fun stopNavigation() {
        rerouteJob?.cancel()
        arriveJob?.cancel()
        navigator = null
        maps.reset()
        _ui.value = _ui.value.copy(navigating = false, route = null, nav = null, destination = null)
        if (link.ready) {
            link.sendUrgent(NavProtocol.MAP, MapProtocol.clear(), "pose")
            sink.state(NavProtocol.encodeState(NavProtocol.NavState()))
        }
    }

    private fun reroute(fix: Fix) {
        val nav = navigator ?: return
        val dest = _ui.value.destination ?: return
        nav.beginReroute()
        rerouteJob = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { valhalla.route(fix.pos, dest.pos, settings.value.vehicle, fix.bearingDeg?.toDouble()) }
            }
            r.onSuccess { nav.replaceRoute(it); _ui.value = _ui.value.copy(route = it) }
                .onFailure { Log.w(TAG, "reroute failed", it); nav.replaceRoute(nav.route) }
        }
    }

    // ---- Google Maps mirroring ----

    fun onMirror(state: NavProtocol.NavState?, icon: ByteArray?) {
        if (navigator != null) return       // our own route wins
        _ui.value = _ui.value.copy(mirroring = state != null, nav = state, mirrorIcon = icon)
        if (!link.ready) return
        if (state == null) {
            sink.state(NavProtocol.encodeState(NavProtocol.NavState()))
            return
        }
        if (icon != null && state.iconHash != 0L && sentIcons.add(state.iconHash)) {
            link.sendBulk(NavProtocol.ICON, NavProtocol.iconChunks(state.iconHash, icon, link.maxWrite))
        }
        sink.state(NavProtocol.encodeState(state.copy(speedKmh = lastFix?.speedMps?.let { it * 3.6 })))
    }

    // ---- device settings ----

    private fun sendClock() {
        val now = System.currentTimeMillis()
        link.sendCtrl(NavProtocol.encodeTime(now / 1000, TimeZone.getDefault().getOffset(now) / 60_000))
    }

    private fun sendSettings() {
        val s = settings.value
        link.sendCtrl(NavProtocol.encodeSettings(s.imperial, s.use24h, s.backlight, s.brightness))
    }

    companion object {
        private const val TAG = "RideEngine"
        private const val ARRIVED_END_MS = 60_000L
        private const val PREVIEW_ROADS_MAX_M = 6_000.0
        /** Fixes vaguer than this are Wi-Fi or cell positions rather than GPS. */
        private const val COARSE_M = 50f
        /** A last-known position younger than this is good enough to plan a route from. */
        private const val LAST_KNOWN_FRESH_NS = 10 * 60 * 1_000_000_000L
    }
}
