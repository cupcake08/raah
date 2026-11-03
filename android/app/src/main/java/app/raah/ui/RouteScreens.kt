package app.raah.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GpsNotFixed
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.raah.ble.RaahLink
import app.raah.proto.Maneuver
import app.raah.proto.NavProtocol
import app.raah.ride.RideEngine
import app.raah.ride.Settings
import app.raah.route.Vehicle
import kotlinx.coroutines.delay
import java.util.Calendar

private val VEHICLES = listOf(Vehicle.SCOOTER to "Scooter", Vehicle.BICYCLE to "Bicycle")
private val VEHICLE_ICONS = mapOf(Vehicle.SCOOTER to Icons.Rounded.TwoWheeler, Vehicle.BICYCLE to Icons.Rounded.PedalBike)

/** Minutes since local midnight, [s] seconds from now. */
private fun clockIn(s: Double): Int {
    val c = Calendar.getInstance().apply { add(Calendar.SECOND, s.toInt()) }
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

/** A look at the route before riding: how far, how long, and the right namesake. */
@Composable
fun PreviewScreen(
    preview: RideEngine.Preview,
    ui: RideEngine.Ui,
    settings: Settings,
    connected: Boolean,
    onVehicle: (Vehicle) -> Unit,
    onRetry: () -> Unit,
    onSave: (Saved) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val place = preview.place
    val route = preview.route
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Route", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(260.dp).clip(CardShape).background(Raah.Surface), contentAlignment = Alignment.Center) {
                when {
                    route != null -> RouteSketch(route.shape, Modifier.fillMaxSize(), preview.roads)
                    preview.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(36.dp), tint = Raah.Alert)
                        Spacer(Modifier.height(8.dp))
                        Text(preview.error, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium,
                            color = Raah.Text2)
                        Spacer(Modifier.height(12.dp))
                        BigButton("Try again", Modifier.width(160.dp), outlined = true, onClick = onRetry)
                    }
                    ui.lastFix == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.GpsNotFixed, null, Modifier.size(32.dp), tint = Raah.Near)
                        Spacer(Modifier.height(8.dp))
                        Text("Waiting for GPS…", style = MaterialTheme.typography.bodyMedium, color = Raah.Text2)
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Raah.Go, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Finding a route…", style = MaterialTheme.typography.bodyMedium, color = Raah.Text2)
                    }
                }
            }

            Column {
                Text(place.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (place.detail.isNotEmpty()) {
                    Text(place.detail, style = MaterialTheme.typography.bodyMedium, color = Raah.Text2, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }

            if (route != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtDuration(route.timeS), style = MaterialTheme.typography.displayMedium, color = Raah.Go)
                    Spacer(Modifier.width(12.dp))
                    Text("${fmtDistance(route.lengthM, settings.imperial)} · arrive ${fmtClock(clockIn(route.timeS), settings.use24h)}",
                        Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyLarge, color = Raah.Text2)
                }
            }

            Segmented(VEHICLES, settings.vehicle, icons = VEHICLE_ICONS, onSelect = onVehicle)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val isHome = ui.home?.let { it.name == place.name && it.pos == place.pos } == true
                val isWork = ui.work?.let { it.name == place.name && it.pos == place.pos } == true
                SaveChip(Modifier.weight(1f), "Save as Home", isHome) { onSave(Saved.HOME) }
                SaveChip(Modifier.weight(1f), "Save as Work", isWork) { onSave(Saved.WORK) }
            }
        }
        Column(Modifier.padding(16.dp)) {
            if (!connected) {
                Text("Raah isn't connected. The ride runs on the phone and the device joins when it connects.",
                    Modifier.padding(bottom = 10.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall, color = Raah.Text3)
            }
            BigButton("Start ride", icon = Icons.Rounded.Navigation, enabled = route != null, onClick = onStart)
        }
    }
}

@Composable
private fun SaveChip(modifier: Modifier, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(CardShape).background(if (on) Raah.Go.copy(alpha = 0.14f) else Raah.Surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(if (on) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null, Modifier.size(18.dp),
            tint = if (on) Raah.Go else Raah.Text2)
        Spacer(Modifier.width(6.dp))
        Text(if (on) "Saved as " + label.removePrefix("Save as ") else label, style = MaterialTheme.typography.labelLarge,
            color = if (on) Raah.Go else Raah.Text2)
    }
}

/**
 * The ride, mirrored from the device: same arrow, same distance, same colour rules. Yellow means the turn
 * is near; the whole slab goes yellow when it's time to turn.
 */
@Composable
fun RideScreen(ui: RideEngine.Ui, settings: Settings, link: RaahLink.State, onEnd: () -> Unit) {
    val nav = ui.nav
    val arrived = nav != null && nav.flags and NavProtocol.F_ARRIVED != 0
    val rerouting = nav != null && nav.flags and NavProtocol.F_REROUTING != 0
    val d = nav?.distToTurnM
    val now = !arrived && d != null && d <= 30
    val near = !arrived && d != null && d <= 200
    val slab by animateColorAsState(if (now) Raah.Near else Raah.Surface, label = "slab")
    val ink = if (now) Raah.OnSignal else Raah.Text
    val ink2 = if (now) Color(0xFF4D3E00) else Raah.Text2
    val arrow = when { now -> Raah.OnSignal; near -> Raah.Near; else -> Raah.Go }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RIDING TO", style = MaterialTheme.typography.labelMedium, color = Raah.Text3)
                Text(ui.destination?.name ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val linked = link is RaahLink.State.Ready
            Row(Modifier.clip(CardShape).background(Raah.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (linked) Raah.Link else Raah.Text3))
                Spacer(Modifier.width(8.dp))
                Text(if (linked) "Device on" else "Device off", style = MaterialTheme.typography.labelLarge,
                    color = if (linked) Raah.Text else Raah.Text3)
            }
        }

        Column(Modifier.fillMaxWidth().weight(1f).clip(CardShape).background(slab).padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            when {
                arrived -> {
                    Icon(Icons.Rounded.SportsScore, null, Modifier.size(96.dp), tint = Raah.Go)
                    Spacer(Modifier.height(16.dp))
                    Text("You've arrived", style = MaterialTheme.typography.displayMedium)
                    Text("The ride ends by itself in a minute.", style = MaterialTheme.typography.bodyMedium, color = Raah.Text2)
                }
                nav == null || rerouting -> {
                    CircularProgressIndicator(Modifier.size(40.dp), color = Raah.Link, strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(if (rerouting) "Rerouting…" else "Starting…", style = MaterialTheme.typography.headlineMedium, color = Raah.Link)
                }
                else -> {
                    Icon(nav.maneuver.icon(), null, Modifier.size(104.dp), tint = arrow)
                    Spacer(Modifier.height(8.dp))
                    Text(d?.let { fmtDistance(it.toDouble(), settings.imperial) } ?: "", style = MaterialTheme.typography.displayLarge,
                        color = ink)
                    if (nav.primary.isNotEmpty()) {
                        Text(nav.primary, style = MaterialTheme.typography.headlineSmall, color = ink, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                    val what = nav.maneuver.label.takeIf { nav.maneuver != Maneuver.UNKNOWN }
                    if (what != null) Text(what, style = MaterialTheme.typography.bodyLarge, color = ink2)
                }
            }
        }

        if (nav != null && !arrived) {
            Row(Modifier.fillMaxWidth().clip(CardShape).background(Raah.Surface).padding(vertical = 16.dp)) {
                Stat(Modifier.weight(1f), "Time left", nav.timeLeftS?.let { fmtDuration(it.toDouble()) })
                Stat(Modifier.weight(1f), "Distance", nav.distLeftM?.let { fmtDistance(it.toDouble(), settings.imperial) })
                Stat(Modifier.weight(1f), "Arrive", nav.etaMinOfDay?.let { fmtClock(it, settings.use24h) })
            }
        }

        // Two taps, so a glove brushing the screen doesn't end the ride.
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
        BigButton(if (armed) "Tap again to end" else "End ride", icon = Icons.Rounded.Close, color = Raah.Alert,
            outlined = !armed, ink = Raah.OnSignal) { if (armed) onEnd() else armed = true }
    }
}

@Composable
private fun Stat(modifier: Modifier, label: String, value: String?) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "–", style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Raah.Text3)
    }
}
