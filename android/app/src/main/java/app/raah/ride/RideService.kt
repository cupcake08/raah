package app.raah.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.raah.R
import app.raah.RaahApp
import app.raah.ble.RaahLink
import app.raah.ui.MainActivity
import app.raah.ui.fmtClock
import app.raah.ui.fmtDistance
import app.raah.ui.fmtDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps GPS and the BLE link alive with the screen off and other apps in front, as a foreground service.
 * Its notification shows the next turn, on the lock screen too, so a glance at the phone matches the device.
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false
    /** What the notification says now, so starting again doesn't put "Starting…" back over it. */
    private var shown = Shown("Raah", "Starting…")

    /** GPS fixes wake the phone every second anyway; this keeps a reroute or a map fetch from stalling between them. */
    private val wake by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "raah:ride")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = RaahApp.engine(this)
        if (intent?.action == ACTION_STOP) {
            engine.disconnect()
            engine.stopLocation()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        engine.connect()
        if (watching) return START_STICKY
        watching = true
        engine.startLocation()
        scope.launch {
            combine(engine.link.state, engine.ui, engine.settings.flow) { link, ui, s -> describe(link, ui, s) }
                .distinctUntilChanged()
                .collect { d ->
                    shown = d
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(d))
                }
        }
        scope.launch {
            engine.ui.map { it.navigating }.distinctUntilChanged().collect { riding ->
                if (riding) wake.acquire(WAKE_MAX_MS) else if (wake.isHeld) wake.release()
            }
        }
        return START_STICKY
    }

    /**
     * Location and connected-device while the app is open. When Android restarts the service after killing it,
     * the app is in the background and Android refuses the location type, so the link alone carries on.
     */
    private fun goForeground(): Boolean {
        val n = notification(shown)
        if (Build.VERSION.SDK_INT < 29) return runCatching { startForeground(NOTIFICATION_ID, n) }.isSuccess
        val types = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        return types.any { t ->
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, t) }
                .onFailure { Log.w(TAG, "foreground service type $t refused", it) }.isSuccess
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (wake.isHeld) wake.release()
        super.onDestroy()
    }

    /**
     * The turn goes on its own channel, silent but important enough for the lock screen, which hides
     * low-importance notifications. The link's state stays out of the way.
     */
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TURN, "Turn-by-turn", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LINK, "Connection", NotificationManager.IMPORTANCE_LOW))
    }

    private data class Shown(val title: String, val text: String, val riding: Boolean = false)

    /** Title and text: the turn and the trip while riding, otherwise what the link is doing. */
    private fun describe(link: RaahLink.State, ui: RideEngine.Ui, s: Settings): Shown {
        val nav = ui.nav
        return when {
            ui.navigating && nav != null -> {
                val turn = listOfNotNull(nav.distToTurnM?.let { fmtDistance(it.toDouble(), s.imperial) }, nav.primary.ifEmpty { null })
                val trip = listOfNotNull(
                    ui.destination?.name?.let { "To $it" },
                    nav.timeLeftS?.let { "${fmtDuration(it.toDouble())} left" },
                    nav.etaMinOfDay?.let { "arrive ${fmtClock(it, s.use24h)}" },
                )
                Shown(turn.joinToString(" · ").ifEmpty { "Raah" }, trip.joinToString(" · "), riding = true)
            }
            ui.navigating -> Shown("Raah", "To ${ui.destination?.name ?: "destination"}", riding = true)
            ui.mirroring -> Shown("Raah", "Mirroring Google Maps")
            else -> Shown("Raah", when (link) {
                is RaahLink.State.Ready -> "Connected to ${link.name}"
                is RaahLink.State.Connecting -> "Connecting…"
                RaahLink.State.Scanning -> "Looking for Raah"
                RaahLink.State.Off -> "Off"
            })
        }
    }

    private fun notification(d: Shown): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, if (d.riding) CHANNEL_TURN else CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_raah)
            .setColor(ContextCompat.getColor(this, R.color.raah_go))
            .setContentTitle(d.title)
            .setContentText(d.text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "RideService"
        private const val CHANNEL_TURN = "turn"
        private const val CHANNEL_LINK = "ride"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.raah.STOP"
        /** A safety net: the lock is dropped as soon as the ride ends. */
        private const val WAKE_MAX_MS = 6 * 60 * 60 * 1000L

        /** From a visible activity; Android refuses a foreground service started from the background. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RideService::class.java)) }
                .onFailure { Log.w(TAG, "can't start the ride service now", it) }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
