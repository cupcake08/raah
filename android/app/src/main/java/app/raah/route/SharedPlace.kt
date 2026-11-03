package app.raah.route

import android.content.Intent
import app.raah.geo.LatLon
import app.raah.net.Http
import java.net.URLDecoder

/**
 * A place handed to Raah by another app: Google Maps' Share (a name and a maps.app.goo.gl link), a
 * geo: link, or plain text with coordinates or an address.
 */
sealed interface SharedPlace {
    data class At(val place: Place) : SharedPlace
    data class Query(val text: String) : SharedPlace
    /** A short link that only says where it is once followed; see [resolve]. */
    data class Link(val url: String, val name: String?) : SharedPlace

    companion object {
        private val URL = Regex("""https?://\S+""")
        private val COORDS = Regex("""(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})""")
        private val PIN = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
        private val AT = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
        private val PARAM = Regex("""[?&](?:q|query|ll|destination|daddr)=(-?\d+\.\d+),(-?\d+\.\d+)""")
        /** maps/search/16.064556,+108.231402: a searched-for spot. */
        private val SEARCH = Regex("""/maps/search/(-?\d+\.\d+),(?:\+|%20)?(-?\d+\.\d+)""")
        private val PLACE_NAME = Regex("""/place/([^/@?]+)""")
        private val SEARCH_TEXT = Regex("""/maps/search/([^/@?]+)""")
        private val LABEL = Regex("""\(([^)]+)\)""")
        /** A directions link: each stop's coordinates as !1d<lon>!2d<lat>, the destination last. */
        private val DIR = Regex("""/maps/dir/""")
        private val STOP = Regex("""!1d(-?\d+\.\d+)!2d(-?\d+\.\d+)""")
        private val DIR_TO = Regex("""/maps/dir/(?:[^/]*/)*?([^/@?]+)/(?:@|data=|\?|$)""")
        /** Google Maps' shared route: "From My location to Ambience Mall via NH 48." */
        private val FROM_TO = Regex("""^From .+? to (.+?)(?: via .+)?\.?$""", RegexOption.IGNORE_CASE)
        /** Not a name: "Ground Floor", "Shop No. 12", "298", "6/2". */
        private val UNIT = Regex("""^(?:(?:ground|first|second|third|top|upper|lower|basement|\d+(?:st|nd|rd|th))\s+floor|(?:shop|unit|flat|plot|office|suite|house|no\.?)\b.*|\d[\w/-]*)$""", RegexOption.IGNORE_CASE)

        fun from(intent: Intent): SharedPlace? = when (intent.action) {
            Intent.ACTION_SEND -> parse(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
            Intent.ACTION_VIEW -> parse(intent.dataString, null)
            else -> null
        }

        fun parse(text: String?, subject: String?): SharedPlace? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (t.startsWith("geo:")) return geo(t)
            val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val name = lines.firstNotNullOfOrNull { FROM_TO.find(it)?.groupValues?.get(1) }
                ?: subject?.trim()?.takeIf { it.isNotEmpty() } ?: lines.firstOrNull { !URL.containsMatchIn(it) }
            URL.find(t)?.value?.let { url ->
                mapsUrl(url, name)?.let { return At(it) }
                if (isShortLink(url)) return Link(url, name)
            }
            COORDS.find(t)?.let { return At(pin(it, name)) }
            return name?.let { Query(it) }
        }

        /** geo:12.97,77.64?q=12.97,77.64(Third Wave) or geo:0,0?q=Third+Wave+Coffee */
        private fun geo(uri: String): SharedPlace? {
            val q = uri.substringAfter("?q=", "").let { URLDecoder.decode(it, "UTF-8") }
            val label = LABEL.find(q)?.groupValues?.get(1)
            COORDS.find(q)?.let { return At(pin(it, label)) }
            if (q.isNotBlank()) return Query(q.substringBefore('(').trim())
            COORDS.find(uri.removePrefix("geo:"))?.takeIf { it.groupValues[1].toDouble() != 0.0 }?.let { return At(pin(it, label)) }
            return null
        }

        /** A full Google Maps URL: the place pin (!3d…!4d…) beats the map centre (@lat,lon). */
        fun mapsUrl(url: String, nameHint: String?): Place? {
            if (DIR.containsMatchIn(url)) {
                val to = DIR_TO.find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                val stop = STOP.findAll(url).lastOrNull() ?: return null
                val (lon, lat) = stop.destructured
                val (name, address) = nameAndAddress(nameHint ?: to.orEmpty())
                return Place(name.ifEmpty { "Dropped pin" }, address.ifEmpty { "%.5f, %.5f".format(java.util.Locale.US, lat.toDouble(), lon.toDouble()) },
                    LatLon(lat.toDouble(), lon.toDouble()))
            }
            val m = PIN.find(url) ?: PARAM.find(url) ?: SEARCH.find(url) ?: AT.find(url) ?: return null
            // A dropped pin's "name" is its coordinates in degrees and minutes (22°25'28.4"N 114°12'48.7"E).
            val name = PLACE_NAME.find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }?.takeUnless { '°' in it }
            return pin(m, name ?: nameHint)
        }

        fun isShortLink(url: String) = url.contains("maps.app.goo.gl") || url.contains("goo.gl/maps")

        /**
         * Follows a short link's redirects to the full URL: the place, or, when that URL names a place or address
         * without its coordinates, a search for it. Blocking: call from Dispatchers.IO.
         */
        fun resolve(link: Link): SharedPlace? {
            var url = link.url
            repeat(5) {
                mapsUrl(url, link.name)?.let { return At(it) }
                url = Http.redirect(url) ?: return named(url, link.name)
            }
            return mapsUrl(url, link.name)?.let { At(it) } ?: named(url, link.name)
        }

        /** "Ground Floor, Starbucks - Sector 49, Vatika Business Park, …" → the name, and the address around it. */
        fun nameAndAddress(text: String): Pair<String, String> {
            val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val i = parts.indexOfFirst { !UNIT.matches(it) }.coerceAtLeast(0)
            return (parts.getOrNull(i) ?: "") to (parts.take(i) + parts.drop(i + 1)).joinToString(", ")
        }

        /** maps/place/Rev.+E.J.+Motau+st,+Temba/data=… has an address and no coordinates. */
        fun named(url: String, nameHint: String?): Query? =
            ((PLACE_NAME.find(url) ?: SEARCH_TEXT.find(url))?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: nameHint)
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { Query(it) }

        private fun pin(m: MatchResult, name: String?): Place {
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            return Place(name?.takeIf { it.isNotBlank() } ?: "Dropped pin", "%.5f, %.5f".format(java.util.Locale.US, lat, lon),
                LatLon(lat, lon), if (name.isNullOrBlank()) PlaceKind.PIN else PlaceKind.PLACE)
        }
    }
}
