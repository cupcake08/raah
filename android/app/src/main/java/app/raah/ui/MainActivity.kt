package app.raah.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.location.LocationManagerCompat
import app.raah.BuildConfig
import app.raah.RaahApp
import app.raah.ble.RaahLink
import app.raah.ride.RideEngine
import app.raah.ride.RideService
import app.raah.route.SharedPlace
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What's on screen, besides the ride and the route preview, which follow the engine's state. */
private sealed interface Page {
    data object Home : Page
    data class Search(val pick: Saved? = null, val query: String = "") : Page
    data object Settings : Page
}

class MainActivity : ComponentActivity() {

    private val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) RideService.start(this)
    }

    private val page = mutableStateOf<Page>(Page.Home)

    /** The phone's Location switch and the notification-access grant, re-read whenever the user comes back. */
    private val locationOn = mutableStateOf(true)
    private val mirrorAllowed = mutableStateOf(false)
    /** Battery limits lifted, so the phone doesn't stop a ride that runs in a pocket. */
    private val pocketReady = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Let the mark finish drawing itself, then grow and fade it into the app.
        val splash = installSplashScreen()
        val shownAt = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() - shownAt < SPLASH_MS }
        splash.setOnExitAnimationListener { s ->
            s.iconView.animate().scaleX(1.3f).scaleY(1.3f).alpha(0f).setDuration(220).start()
            s.view.animate().alpha(0f).setStartDelay(60).setDuration(240).withEndAction { s.remove() }.start()
        }
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.BLACK), SystemBarStyle.dark(android.graphics.Color.BLACK))
        super.onCreate(savedInstanceState)
        val engine = RaahApp.engine(this)
        request.launch(permissions)
        handleIntent(intent)
        setContent {
            RaahTheme {
                Surface(Modifier.fillMaxSize(), color = Raah.Bg) { App(engine) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        locationOn.value = getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
        mirrorAllowed.value = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        pocketReady.value = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
    }

    /**
     * A launcher shortcut (ride home, ride to work, search), or a place shared from another app (Google Maps'
     * Share, a geo: link): plan it, or search for its name or address.
     */
    private fun handleIntent(intent: Intent?) {
        val engine = RaahApp.engine(this)
        if (intent?.action == Shortcuts.ACTION) {
            when (intent.getStringExtra(Shortcuts.EXTRA_TO)) {
                Shortcuts.HOME -> engine.ui.value.home?.let(engine::navigateTo)
                Shortcuts.WORK -> engine.ui.value.work?.let(engine::navigateTo)
                else -> page.value = Page.Search()
            }
            return
        }
        when (val s = SharedPlace.from(intent ?: return) ?: return) {
            is SharedPlace.At -> engine.plan(s.place)
            is SharedPlace.Query -> page.value = Page.Search(query = s.text)
            is SharedPlace.Link -> lifecycleScope.launch {
                when (val r = withContext(Dispatchers.IO) { runCatching { SharedPlace.resolve(s) }.getOrNull() }) {
                    is SharedPlace.At -> engine.plan(r.place)
                    is SharedPlace.Query -> engine.locate(r.text)?.let(engine::plan) ?: run { page.value = Page.Search(query = r.text) }
                    else -> page.value = Page.Search(query = s.name.orEmpty())
                }
            }
        }
    }

    @Composable
    private fun App(engine: RideEngine) {
        val ui by engine.ui.collectAsState()
        val link by engine.link.state.collectAsState()
        val status by engine.link.status.collectAsState()
        val settings by engine.settings.flow.collectAsState()
        val current = page.value
        val preview = ui.preview
        LaunchedEffect(ui.home, ui.work) { Shortcuts.publish(this@MainActivity, ui.home, ui.work) }

        val screen = when {
            ui.navigating -> "ride"
            preview != null -> "preview"
            else -> when (current) { Page.Home -> "home"; is Page.Search -> "search"; Page.Settings -> "settings" }
        }
        BackHandler(screen == "preview") { engine.cancelPlan() }
        BackHandler(screen == "search" || screen == "settings") { page.value = Page.Home }

        AnimatedContent(screen, Modifier.fillMaxSize().safeDrawingPadding(), label = "screen",
            transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
            when (s) {
                "ride" -> RideScreen(ui, settings, link) { engine.stopNavigation() }
                "preview" -> preview?.let { p ->
                    PreviewScreen(
                        p, ui, settings, connected = link is RaahLink.State.Ready,
                        onVehicle = { v -> engine.settings.update { it.copy(vehicle = v) }; engine.plan(p.place) },
                        onRetry = { engine.plan(p.place) },
                        onSave = { which -> if (which == Saved.HOME) engine.setHome(p.place) else engine.setWork(p.place) },
                        onStart = { page.value = Page.Home; engine.start() },
                        onBack = { engine.cancelPlan() },
                    )
                }
                "search" -> {
                    val search = current as? Page.Search ?: Page.Search()
                    SearchScreen(
                        engine, ui, settings.imperial,
                        title = when (search.pick) { Saved.HOME -> "Set Home"; Saved.WORK -> "Set Work"; null -> "Where to?" },
                        initialQuery = search.query, showSaved = search.pick == null,
                        onPick = { place ->
                            when (search.pick) {
                                Saved.HOME -> { engine.setHome(place); page.value = Page.Home }
                                Saved.WORK -> { engine.setWork(place); page.value = Page.Home }
                                null -> engine.plan(place)
                            }
                        },
                        onBack = { page.value = Page.Home },
                    )
                }
                "settings" -> SettingsScreen(
                    settings, ui, link, status, mirrorAllowed.value, BuildConfig.VERSION_NAME,
                    onChange = { engine.settings.update(it) },
                    onClearSaved = { if (it == Saved.HOME) engine.setHome(null) else engine.setWork(null) },
                    onConnect = { RideService.start(this@MainActivity) },
                    onDisconnect = { RideService.stop(this@MainActivity) },
                    onGrantMirror = ::openNotificationAccess,
                    onBack = { page.value = Page.Home },
                )
                else -> HomeScreen(
                    ui, link, status, settings.imperial, locationOn.value, mirrorAllowed.value, pocketReady.value,
                    onSearch = { page.value = Page.Search() },
                    onPlace = { engine.plan(it) },
                    onForget = { engine.forgetRecent(it) },
                    onSetSaved = { page.value = Page.Search(pick = it) },
                    onSettings = { page.value = Page.Settings },
                    onConnect = { RideService.start(this@MainActivity) },
                    onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onGrantMirror = ::openNotificationAccess,
                    onAllowBackground = ::askToRunInBackground,
                )
            }
        }
    }

    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    /** Android's own yes/no for lifting battery limits; the settings list where the dialog doesn't exist. */
    @SuppressLint("BatteryLife")
    private fun askToRunInBackground() {
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private companion object {
        /** Long enough for the splash icon (res/drawable/splash_icon.xml) to finish drawing: it starts before onCreate. */
        const val SPLASH_MS = 700L
    }
}
