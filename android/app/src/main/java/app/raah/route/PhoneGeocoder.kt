package app.raah.route

import android.content.Context
import android.location.Address
import android.location.Geocoder
import app.raah.geo.LatLon
import java.util.Locale

/**
 * The phone's own geocoder. On phones with Google Play services it asks Google, so it knows the shops, malls
 * and addresses OpenStreetMap is missing in much of India. Free and keyless, but it matches whole names
 * rather than as you type, so its answers join Photon's instead of replacing them.
 */
class PhoneGeocoder(context: Context) {
    private val geocoder = Geocoder(context, Locale.getDefault())

    /** Blocking: call from Dispatchers.IO. Empty on phones without a geocoder. */
    @Suppress("DEPRECATION") // the listener form is Android 13+ only, and this already runs off the main thread
    fun search(query: String, near: LatLon?, limit: Int = 5): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        // Around the rider first; an address shared from elsewhere still turns up when nothing nearby matches.
        val around = near?.let { geocoder.getFromLocationName(query, limit, it.lat - BOX, it.lon - BOX, it.lat + BOX, it.lon + BOX) }
        val found = around?.takeIf { it.isNotEmpty() } ?: geocoder.getFromLocationName(query, limit)
        return found.orEmpty().mapNotNull { place(it, query) }
    }

    private companion object {
        /** Half the side of the box searched around the rider, in degrees: about 55 km. */
        const val BOX = 0.5

        fun place(a: Address, query: String): Place? {
            if (!a.hasLatitude() || !a.hasLongitude()) return null
            val line = a.getAddressLine(0).orEmpty()
            // Android gives a shop or landmark's address but often not its name: the feature is the road it's on
            // ("NH-8" for Ambience Mall), or a house number. A name that shares no word with what was asked for is
            // one of those, and then the place is called what it was asked for.
            val asked = words(query)
            fun relevant(n: String?) = n?.takeIf { words(it).any { w -> asked.any { w.startsWith(it) } } }
            val feature = relevant(a.featureName?.takeIf { it != a.countryName && it != a.postalCode })
            val name = feature ?: relevant(a.thoroughfare) ?: titleCase(query).ifBlank { return null }
            val detail = line.split(",").map { part -> part.trim().removeSuffix(a.postalCode.orEmpty()).trim() }
                .filter { it.isNotEmpty() && !it.equals(name, ignoreCase = true) && it != a.countryName }
                .joinToString(", ")
            return Place(name, detail, LatLon(a.latitude, a.longitude), if (name == a.thoroughfare) PlaceKind.STREET else PlaceKind.PLACE)
        }

        fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 }.toSet()

        fun titleCase(s: String) = s.trim().split(Regex("""\s+""")).joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }
}
