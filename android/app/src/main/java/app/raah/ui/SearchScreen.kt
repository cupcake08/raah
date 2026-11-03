package app.raah.ui

import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GpsNotFixed
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.raah.geo.Geo
import app.raah.ride.RideEngine
import app.raah.route.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Search as you type: a pause of a quarter second sends the query, and a newer keystroke cancels the
 * older request, so the list always matches the text. Empty, it offers saved and recent places instead.
 */
@Composable
fun SearchScreen(
    engine: RideEngine,
    ui: RideEngine.Ui,
    imperial: Boolean,
    title: String,
    initialQuery: String,
    showSaved: Boolean,
    onPick: (Place) -> Unit,
    onBack: () -> Unit,
) {
    // Keyed, so a place shared in while the search is open replaces what was typed.
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    /** null until a query has been answered; the previous answer stays up while the next one loads. */
    var places by remember { mutableStateOf<List<Place>?>(null) }
    var loading by remember { mutableStateOf(false) }
    /** Set when the last search failed: true when the phone is offline. */
    var failed by remember { mutableStateOf<Boolean?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, attempt) {
        val q = query.trim()
        if (q.length < 2) { places = null; failed = null; loading = false; return@LaunchedEffect }
        delay(250)
        loading = true
        try {
            places = engine.search(q)
            failed = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e is java.io.IOException && e !is app.raah.net.Http.Error
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                .background(Raah.Surface).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search a place, address or link", style = MaterialTheme.typography.bodyLarge, color = Raah.Text3)
                BasicTextField(
                    query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Raah.Text),
                    cursorBrush = SolidColor(Raah.Go),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        places?.singleOrNull()?.let(onPick) ?: keyboard?.hide()
                    }),
                )
            }
            if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = Raah.Text2) }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 24.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Raah.Go, trackColor = Raah.Surface)
        }
        if (ui.lastFix == null) {
            val context = LocalContext.current
            // Without Wi-Fi positioning (Google Location Accuracy), indoors there's no position at all: GPS needs the sky.
            val wifiOff = remember { context.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == false }
            Row(Modifier.fillMaxWidth().clickable(enabled = wifiOff) { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GpsNotFixed, null, Modifier.size(16.dp), tint = Raah.Near)
                Spacer(Modifier.width(6.dp))
                Text(if (wifiOff) "No location indoors. Tap to turn on Google Location Accuracy, so results start near you."
                    else "No location fix yet, so results aren't sorted by distance",
                    style = MaterialTheme.typography.bodySmall, color = Raah.Text2)
            }
        }

        val distance = { p: Place -> ui.lastFix?.let { fmtDistance(Geo.distance(it, p.pos), imperial) } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            val found = places
            val offline = failed
            when {
                offline != null && query.trim().length >= 2 -> item {
                    Empty(Icons.Rounded.WifiOff, if (offline) "No connection" else "Search isn't answering",
                        "Raah searches Google and OpenStreetMap online.", "Try again") { attempt++ }
                }
                found != null -> {
                    if (found.isEmpty() && !loading) item { Empty(Icons.Rounded.SearchOff, "Nothing found for “${query.trim()}”",
                        "Try the street or area name, or check the spelling.") }
                    items(found) { p -> PlaceRow(p.kind.icon(), p.name, p.detail, distance(p), Raah.Text2) { onPick(p) } }
                    if (!loading || found.isNotEmpty()) item { GoogleMapsRow(query.trim()) }
                }
                else -> {
                    if (showSaved) {
                        listOfNotNull(ui.home?.let { Triple(Icons.Rounded.Home, "Home", it) },
                            ui.work?.let { Triple(Icons.Rounded.Work, "Work", it) }).forEach { (icon, label, p) ->
                            item { PlaceRow(icon, label, p.name, distance(p), Raah.Go) { onPick(p) } }
                        }
                    }
                    if (ui.recent.isNotEmpty()) {
                        item { SectionLabel("Recent", Modifier.padding(start = 8.dp)) }
                        items(ui.recent) { p -> PlaceRow(Icons.Rounded.History, p.name, p.detail, distance(p)) { onPick(p) } }
                    }
                    item { GoogleMapsRow("") }
                    item {
                        Text("Tip: paste a Google Maps link or coordinates like 12.9719, 77.6412 in the box. In Google Maps' " +
                            "share sheet, long-press Raah and choose Pin to keep it first.",
                            Modifier.padding(horizontal = 12.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall,
                            color = Raah.Text3)
                    }
                }
            }
        }
    }
}

/** For places Raah can't find: Google Maps' own search, and Share › Raah brings the place back. */
@Composable
private fun GoogleMapsRow(query: String) {
    val context = LocalContext.current
    PlaceRow(Icons.Rounded.Map, if (query.isEmpty()) "Find it in Google Maps" else "Search Google Maps for “$query”",
        "Then tap Share › Raah to ride there", tint = Raah.Link) { Shortcuts.openGoogleMaps(context, query) }
}

@Composable
private fun Empty(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = Raah.Text3)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Raah.Text2, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            BigButton(action, Modifier.width(180.dp), outlined = true, onClick = onAction)
        }
    }
}
