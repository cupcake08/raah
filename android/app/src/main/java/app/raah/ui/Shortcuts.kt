package app.raah.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.raah.R
import app.raah.route.Place

/** Long-press Raah's icon to ride home, ride to work or search, without going through the home screen. */
object Shortcuts {
    const val ACTION = "app.raah.SHORTCUT"
    const val EXTRA_TO = "to"
    const val HOME = "home"
    const val WORK = "work"
    const val SEARCH = "search"

    /** Home and Work appear once they're set. */
    fun publish(context: Context, home: Place?, work: Place?) {
        val list = listOfNotNull(
            home?.let { shortcut(context, HOME, "Ride home", "Ride home: ${it.name}", R.drawable.ic_shortcut_home) },
            work?.let { shortcut(context, WORK, "Ride to work", "Ride to work: ${it.name}", R.drawable.ic_shortcut_work) },
            shortcut(context, SEARCH, "Where to?", "Search a place", R.drawable.ic_shortcut_search),
        ).mapIndexed { rank, b -> b.setRank(rank).build() }
        ShortcutManagerCompat.setDynamicShortcuts(context, list)
    }

    private fun shortcut(context: Context, id: String, short: String, long: String, icon: Int) =
        ShortcutInfoCompat.Builder(context, id).setShortLabel(short).setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA_TO, id))

    /** Google Maps' own search, for places Raah can't find; from there, Share › Raah comes back with the place. */
    fun openGoogleMaps(context: Context, query: String) {
        val uri = Uri.parse(if (query.isBlank()) "https://www.google.com/maps" else "https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        runCatching { context.startActivity(intent) }.recoverCatching { context.startActivity(intent.setPackage(null)) }
    }
}
