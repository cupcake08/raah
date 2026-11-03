package app.raah.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.raah.ble.RaahLink
import app.raah.proto.NavProtocol
import app.raah.proto.NavProtocol.BacklightMode
import app.raah.ride.RideEngine
import app.raah.ride.Settings
import app.raah.route.Place
import app.raah.route.Vehicle
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: Settings,
    ui: RideEngine.Ui,
    link: RaahLink.State,
    status: NavProtocol.Status?,
    mirrorAllowed: Boolean,
    version: String,
    onChange: ((Settings) -> Settings) -> Unit,
    onClearSaved: (Saved) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onGrantMirror: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("Device")
            Slab {
                val (title, detail) = when (link) {
                    is RaahLink.State.Ready -> link.name to listOfNotNull("Connected",
                        status?.batteryPct?.let { "battery $it%" + if (status.charging) ", charging" else "" }).joinToString(" · ")
                    is RaahLink.State.Connecting -> link.name to "Connecting…"
                    RaahLink.State.Scanning -> "Looking for Raah" to "Turn it on and keep it close"
                    RaahLink.State.Off -> "Not connected" to "The phone isn't looking for the device"
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Raah.Text2)
                Spacer(Modifier.height(12.dp))
                if (link == RaahLink.State.Off) BigButton("Connect", color = Raah.Link, onClick = onConnect)
                else BigButton("Disconnect", color = Raah.Text2, outlined = true, onClick = onDisconnect)
            }

            SectionLabel("Device screen")
            Slab {
                Text("Screen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(BacklightMode.ALWAYS to "Always on", BacklightMode.SMART_DIM to "Smart dim",
                    BacklightMode.SMART_OFF to "Smart off"), settings.backlight) { m -> onChange { it.copy(backlight = m) } }
                Text(when (settings.backlight) {
                    BacklightMode.ALWAYS -> "Full brightness the whole ride. Uses the most battery."
                    BacklightMode.SMART_DIM -> "Dims to 20% between turns; full brightness as a turn comes near or when you press the button."
                    BacklightMode.SMART_OFF -> "Dark between turns; lights up as a turn comes near or when you press the button. Longest battery life."
                }, Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall, color = Raah.Text2)

                Spacer(Modifier.height(18.dp))
                var b by remember(settings.brightness) { mutableFloatStateOf(settings.brightness.toFloat()) }
                Row {
                    Text("Brightness", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("${b.roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = Raah.Text2)
                }
                Slider(b, { b = it }, valueRange = 10f..100f,
                    onValueChangeFinished = { onChange { it.copy(brightness = b.roundToInt()) } },
                    colors = SliderDefaults.colors(thumbColor = Raah.Go, activeTrackColor = Raah.Go, inactiveTrackColor = Raah.SurfaceHi))

                Spacer(Modifier.height(10.dp))
                Text("Units", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(false to "Kilometres", true to "Miles"), settings.imperial) { v -> onChange { it.copy(imperial = v) } }
                Spacer(Modifier.height(14.dp))
                Text("Clock", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(false to "12-hour", true to "24-hour"), settings.use24h) { v -> onChange { it.copy(use24h = v) } }
            }

            SectionLabel("Riding")
            Slab {
                Text("Route for", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(Vehicle.SCOOTER to "Scooter", Vehicle.BICYCLE to "Bicycle"), settings.vehicle,
                    icons = mapOf(Vehicle.SCOOTER to Icons.Rounded.TwoWheeler, Vehicle.BICYCLE to Icons.Rounded.PedalBike)) { v ->
                    onChange { it.copy(vehicle = v) }
                }
                Text("Bicycle routes prefer cycle lanes and quiet streets; scooter routes avoid motorways.",
                    Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall, color = Raah.Text2)
            }

            SectionLabel("Saved places")
            Slab(padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                SavedRow(Icons.Rounded.Home, "Home", ui.home) { onClearSaved(Saved.HOME) }
                SavedRow(Icons.Rounded.Work, "Work", ui.work) { onClearSaved(Saved.WORK) }
            }

            SectionLabel("Google Maps")
            Slab {
                Text(if (mirrorAllowed) "Mirroring is on" else "Mirroring is off", style = MaterialTheme.typography.titleMedium)
                Text(if (mirrorAllowed) "When you navigate in Google Maps and Raah isn't routing, its turns show on the device."
                    else "Raah can show Google Maps' turns on the device. It reads Google Maps' navigation notification.",
                    style = MaterialTheme.typography.bodySmall, color = Raah.Text2)
                if (!mirrorAllowed) {
                    Spacer(Modifier.height(12.dp))
                    BigButton("Allow notification access", color = Raah.Link, onClick = onGrantMirror)
                }
            }

            SectionLabel("About")
            Text("Raah $version\nRouting © Valhalla / FOSSGIS · Search © Photon (Komoot) · Maps © OpenStreetMap contributors, " +
                "OpenFreeMap · Barlow typeface, SIL Open Font License",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = Raah.Text3)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SavedRow(icon: ImageVector, label: String, place: Place?, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(icon, if (place != null) Raah.Go else Raah.Text3)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(place?.name ?: "Not set. Pick a place and tap Save as $label.", style = MaterialTheme.typography.bodySmall,
                color = Raah.Text2)
        }
        if (place != null) IconButton(onClear) { Icon(Icons.Rounded.Close, "Clear $label", tint = Raah.Text3) }
    }
}
