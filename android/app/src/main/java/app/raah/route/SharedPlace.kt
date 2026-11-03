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
        private val PLACE_NAME = Regex("""/place/([^/@?]+)""")
        private val LABEL = Regex("""\(([^)]+)\)""")

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
            val name = subject?.trim()?.takeIf { it.isNotEmpty() } ?: lines.firstOrNull { !URL.containsMatchIn(it) }
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
            val m = PIN.find(url) ?: PARAM.find(url) ?: AT.find(url) ?: return null
            val name = PLACE_NAME.find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: nameHint
            return pin(m, name)
        }

        fun isShortLink(url: String) = url.contains("maps.app.goo.gl") || url.contains("goo.gl/maps")

        /** Follows a short link's redirects to the full URL. Blocking: call from Dispatchers.IO. */
        fun resolve(link: Link): Place? {
            var url = link.url
            repeat(5) {
                mapsUrl(url, link.name)?.let { return it }
                url = Http.redirect(url) ?: return null
            }
            return mapsUrl(url, link.name)
        }

        private fun pin(m: MatchResult, name: String?): Place {
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            return Place(name?.takeIf { it.isNotBlank() } ?: "Dropped pin", "%.5f, %.5f".format(java.util.Locale.US, lat, lon),
                LatLon(lat, lon), if (name.isNullOrBlank()) PlaceKind.PIN else PlaceKind.PLACE)
        }
    }
}
