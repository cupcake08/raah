package app.raah.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddRoad
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.UTurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.raah.geo.LatLon
import app.raah.map.Road
import app.raah.proto.MapProtocol
import app.raah.proto.Maneuver
import app.raah.route.PlaceKind
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

val CardShape = RoundedCornerShape(20.dp)

/** The one container: a near-black slab, like the device's floating card. */
@Composable
fun Slab(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp),
         content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(Raah.Surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.ROOT), modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = Raah.Text3)
}

/** Icon in a round chip, for list rows. */
@Composable
fun RoundIcon(icon: ImageVector, tint: Color = Raah.Text2, bg: Color = Raah.SurfaceHi, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.55f), tint = tint)
    }
}

/** A list row: icon, two lines, and something on the right. Tall enough to hit with a glove. */
@Composable
fun PlaceRow(icon: ImageVector, title: String, detail: String?, trailing: String? = null, tint: Color = Raah.Text2,
             onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Raah.Text2, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = Raah.Text3)
        }
    }
}

/** Two to four choices in a pill; the selected one lights up green. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier,
                  icons: Map<T, ImageVector> = emptyMap(), onSelect: (T) -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Raah.SurfaceHi).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (on) Raah.Go else Color.Transparent).clickable { onSelect(value) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                icons[value]?.let {
                    Icon(it, null, Modifier.size(18.dp), tint = if (on) Raah.OnSignal else Raah.Text2)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Raah.OnSignal else Raah.Text2)
            }
        }
    }
}

/** Big flat button, 56 dp: the main action on a screen. */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = Raah.Go, ink: Color = Raah.OnSignal,
              icon: ImageVector? = null, enabled: Boolean = true, outlined: Boolean = false, onClick: () -> Unit) {
    val bg = when { outlined -> Color.Transparent; enabled -> color; else -> Raah.SurfaceHi }
    val fg = when { outlined -> color; enabled -> ink; else -> Raah.Text3 }
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .let { if (outlined) it.border(1.5.dp, color.copy(alpha = 0.6f), RoundedCornerShape(16.dp)) else it }
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp), tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.CAFE -> Icons.Rounded.LocalCafe
    PlaceKind.FOOD -> Icons.Rounded.Restaurant
    PlaceKind.SHOP -> Icons.Rounded.ShoppingBag
    PlaceKind.FUEL -> Icons.Rounded.LocalGasStation
    PlaceKind.HEALTH -> Icons.Rounded.LocalHospital
    PlaceKind.TRANSIT -> Icons.Rounded.DirectionsBus
    PlaceKind.STAY -> Icons.Rounded.Hotel
    PlaceKind.PARK -> Icons.Rounded.Park
    PlaceKind.EDUCATION -> Icons.Rounded.School
    PlaceKind.WORK -> Icons.Rounded.Business
    PlaceKind.AREA -> Icons.Rounded.LocationCity
    PlaceKind.STREET -> Icons.Rounded.AddRoad
    PlaceKind.PIN -> Icons.Rounded.PushPin
    PlaceKind.PLACE -> Icons.Rounded.Place
}

fun Maneuver.icon(): ImageVector = when (this) {
    Maneuver.STRAIGHT -> Icons.Rounded.Straight
    Maneuver.SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    Maneuver.SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    Maneuver.LEFT -> Icons.Rounded.TurnLeft
    Maneuver.RIGHT -> Icons.Rounded.TurnRight
    Maneuver.SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
    Maneuver.SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
    Maneuver.UTURN_LEFT -> Icons.Rounded.UTurnLeft
    Maneuver.UTURN_RIGHT -> Icons.Rounded.UTurnRight
    Maneuver.FORK_LEFT -> Icons.Rounded.ForkLeft
    Maneuver.FORK_RIGHT -> Icons.Rounded.ForkRight
    Maneuver.MERGE -> Icons.Rounded.Merge
    // Left-hand traffic (India, UK): roundabouts go clockwise.
    Maneuver.ROUNDABOUT -> Icons.Rounded.RoundaboutLeft
    Maneuver.ARRIVE -> Icons.Rounded.SportsScore
    Maneuver.UNKNOWN -> Icons.Rounded.Navigation
}

/**
 * The route as a line drawing over faint streets: no map tiles, just the shape of the ride, start to flag.
 * Enough to tell "across town" from "round the corner", and to spot a route that doubles back.
 */
@Composable
fun RouteSketch(shape: List<LatLon>, modifier: Modifier = Modifier, roads: List<Road> = emptyList(), color: Color = Raah.Go) {
    Canvas(modifier.clipToBounds()) {
        if (shape.size < 2) return@Canvas
        val k = cos(Math.toRadians(shape[0].lat))
        val minX = shape.minOf { it.lon } * k; val maxX = shape.maxOf { it.lon } * k
        val minY = -shape.maxOf { it.lat }; val maxY = -shape.minOf { it.lat }
        val pad = 22.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / max(maxX - minX, 1e-9), (size.height - 2 * pad) / max(maxY - minY, 1e-9))
        val ox = (size.width - (maxX - minX) * scale) / 2
        val oy = (size.height - (maxY - minY) * scale) / 2
        fun x(lon: Double) = ((lon * k - minX) * scale + ox).toFloat()
        fun y(lat: Double) = ((-lat - minY) * scale + oy).toFloat()

        for (r in roads) {
            val ll = r.latLon
            val p = Path().apply { moveTo(x(ll[1]), y(ll[0])); for (i in 2 until ll.size step 2) lineTo(x(ll[i + 1]), y(ll[i])) }
            val major = r.cls >= MapProtocol.CLASS_MID
            drawPath(p, if (major) Raah.Line else Raah.SurfaceHi,
                style = Stroke((if (major) 2.5f else 1.5f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val path = Path().apply { moveTo(x(shape[0].lon), y(shape[0].lat)); for (p in shape.drop(1)) lineTo(x(p.lon), y(p.lat)) }
        drawPath(path, Raah.Bg, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val a = Offset(x(shape.first().lon), y(shape.first().lat))
        val b = Offset(x(shape.last().lon), y(shape.last().lat))
        drawCircle(Raah.Bg, 10.dp.toPx(), a); drawCircle(Raah.Link, 7.dp.toPx(), a); drawCircle(Raah.Bg, 3.dp.toPx(), a)
        drawCircle(Raah.Bg, 11.dp.toPx(), b); drawCircle(Raah.Text, 8.dp.toPx(), b); drawCircle(Raah.Bg, 4.dp.toPx(), b)
    }
}

/** Distances the way a rider reads them: "350 m", "2.4 km", "12 km"; or feet and miles. */
fun fmtDistance(m: Double, imperial: Boolean): String = if (imperial) {
    val mi = m / 1609.344
    when {
        mi < 0.1 -> "${((m * 3.28084) / 50).toInt() * 50} ft"
        mi < 10 -> "%.1f mi".format(Locale.US, mi)
        else -> "${mi.toInt()} mi"
    }
} else when {
    m < 1000 -> "${(m / 10).toInt() * 10} m"
    m < 10_000 -> "%.1f km".format(Locale.US, m / 1000)
    else -> "${(m / 1000).toInt()} km"
}

fun fmtDuration(s: Double): String {
    val min = (s / 60).toInt().coerceAtLeast(1)
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

fun fmtClock(minOfDay: Int, use24h: Boolean): String {
    val h = (minOfDay / 60) % 24
    val m = minOfDay % 60
    return if (use24h) "%02d:%02d".format(h, m) else "%d:%02d %s".format((h + 11) % 12 + 1, m, if (h < 12) "am" else "pm")
}
