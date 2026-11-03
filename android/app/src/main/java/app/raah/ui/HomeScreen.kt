package app.raah.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raah.ble.RaahLink
import app.raah.geo.Geo
import app.raah.proto.NavProtocol
import app.raah.ride.RideEngine
import app.raah.route.Place

enum class Saved { HOME, WORK }

@Composable
fun HomeScreen(
    ui: RideEngine.Ui,
    link: RaahLink.State,
    status: NavProtocol.Status?,
    imperial: Boolean,
    locationOn: Boolean,
    mirrorAllowed: Boolean,
    onSearch: () -> Unit,
    onPlace: (Place) -> Unit,
    onSetSaved: (Saved) -> Unit,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onLocationSettings: () -> Unit,
    onGrantMirror: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("RAAH", style = MaterialTheme.typography.headlineMedium, letterSpacing = 5.sp)
                Text(".", style = MaterialTheme.typography.headlineMedium, color = Raah.Go)
            }
            IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Raah.Text2) }
        }

        DeviceCard(link, status, onConnect)

        // The one thing most visits are for, so it's the biggest target on the screen.
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(Raah.Surface)
                .border(1.dp, Raah.Line, RoundedCornerShape(20.dp)).clickable(onClick = onSearch).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(26.dp), tint = Raah.Go)
            Spacer(Modifier.width(14.dp))
            Text("Where to?", style = MaterialTheme.typography.titleLarge, color = Raah.Text2)
        }

        if (!locationOn) {
            Notice(Icons.Rounded.LocationOff, Raah.Alert, "Location is off",
                "Raah needs it to find routes and follow the ride.", "Turn on", onLocationSettings)
        }

        if (ui.mirroring && ui.nav != null) MirrorCard(ui.nav, imperial)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SavedTile(Modifier.weight(1f), Icons.Rounded.Home, "Home", ui.home, { onPlace(it) }) { onSetSaved(Saved.HOME) }
            SavedTile(Modifier.weight(1f), Icons.Rounded.Work, "Work", ui.work, { onPlace(it) }) { onSetSaved(Saved.WORK) }
        }

        if (ui.recent.isNotEmpty()) {
            SectionLabel("Recent")
            Slab(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                ui.recent.forEach { p ->
                    PlaceRow(Icons.Rounded.History, p.name, p.detail,
                        trailing = ui.lastFix?.let { fmtDistance(Geo.distance(it, p.pos), imperial) }) { onPlace(p) }
                }
            }
        }

        if (!mirrorAllowed) {
            Notice(Icons.Rounded.Map, Raah.Link, "Use Google Maps too",
                "Raah can show Google Maps' turns on the device while you navigate there.", "Allow", onGrantMirror)
        }

        Spacer(Modifier.height(8.dp))
        Text("Routing © Valhalla / FOSSGIS · Search © Photon · Maps © OpenStreetMap contributors, OpenFreeMap",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = Raah.Text3)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DeviceCard(link: RaahLink.State, status: NavProtocol.Status?, onConnect: () -> Unit) {
    val (icon, tint, title, detail) = when (link) {
        is RaahLink.State.Ready -> Quad(Icons.Rounded.BluetoothConnected, Raah.Link, link.name, "Connected")
        is RaahLink.State.Connecting -> Quad(Icons.Rounded.BluetoothSearching, Raah.Link, link.name, "Connecting…")
        RaahLink.State.Scanning -> Quad(Icons.Rounded.BluetoothSearching, Raah.Link, "Looking for Raah", "Turn it on and keep it close")
        RaahLink.State.Off -> Quad(Icons.Rounded.Bluetooth, Raah.Text3, "Not connected", "Tap to find your Raah")
    }
    Slab(onClick = if (link == RaahLink.State.Off) onConnect else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(icon, tint, bg = if (link is RaahLink.State.Ready) Raah.Link.copy(alpha = 0.14f) else Raah.SurfaceHi, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Raah.Text2)
            }
            val pct = status?.batteryPct
            if (link is RaahLink.State.Ready && pct != null) {
                val low = pct <= 15 && !status.charging
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Raah.SurfaceHi).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (status.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, null,
                        Modifier.size(16.dp), tint = if (low) Raah.Alert else Raah.Text2)
                    Spacer(Modifier.width(4.dp))
                    Text("$pct%", style = MaterialTheme.typography.labelLarge, color = if (low) Raah.Alert else Raah.Text)
                }
            }
        }
    }
}

private data class Quad(val icon: ImageVector, val tint: Color, val title: String, val detail: String)

@Composable
private fun SavedTile(modifier: Modifier, icon: ImageVector, label: String, place: Place?, onGo: (Place) -> Unit, onSet: () -> Unit) {
    Column(
        modifier.clip(CardShape).background(Raah.Surface).clickable { if (place != null) onGo(place) else onSet() }.padding(16.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (place != null) Raah.Go else Raah.Text3)
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(place?.name ?: "Set $label".lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall, color = if (place != null) Raah.Text2 else Raah.Text3,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MirrorCard(nav: NavProtocol.NavState, imperial: Boolean) {
    Slab {
        Text("FROM GOOGLE MAPS", style = MaterialTheme.typography.labelMedium, color = Raah.Link)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(nav.maneuver.icon(), null, Modifier.size(44.dp), tint = Raah.Go)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(nav.distToTurnM?.let { fmtDistance(it.toDouble(), imperial) } ?: "", style = MaterialTheme.typography.headlineMedium)
                Text(nav.primary, style = MaterialTheme.typography.bodyMedium, color = Raah.Text2, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Notice(icon: ImageVector, tint: Color, title: String, body: String, action: String, onAction: () -> Unit) {
    Slab {
        Row(verticalAlignment = Alignment.Top) {
            RoundIcon(icon, tint, bg = tint.copy(alpha = 0.14f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Raah.Text2)
                Spacer(Modifier.height(10.dp))
                Text(action, Modifier.clip(RoundedCornerShape(10.dp)).background(tint).clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = Raah.OnSignal)
            }
        }
    }
}
