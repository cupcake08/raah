package app.raah.route

import app.raah.geo.Geo
import app.raah.geo.LatLon
import app.raah.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** What kind of place, from OSM's key/value (amenity=cafe → CAFE), for the result's icon. */
enum class PlaceKind { PLACE, CAFE, FOOD, SHOP, FUEL, HEALTH, TRANSIT, STAY, PARK, EDUCATION, WORK, AREA, STREET, PIN }

data class Place(
    val name: String,
    /** Street and area, most specific first. */
    val detail: String,
    val pos: LatLon,
    val kind: PlaceKind = PlaceKind.PLACE,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("detail", detail)
        .put("lat", pos.lat).put("lon", pos.lon).put("kind", kind.name)

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.getString("name"), o.optString("detail"), LatLon(o.getDouble("lat"), o.getDouble("lon")),
            runCatching { PlaceKind.valueOf(o.optString("kind")) }.getOrDefault(PlaceKind.PLACE),
        )
    }
}

/** Place search on Komoot's public Photon instance (OpenStreetMap data). Fast enough to search as you type. */
class Photon(private val baseUrl: String = "https://photon.komoot.io") {

    fun search(query: String, near: LatLon?, limit: Int = 10): List<Place> {
        coordinates(query)?.let { return listOf(it) }
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // A wider bias area than the default zoom 16: a rider searches across town, not just the street.
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&zoom=12&location_bias_scale=0.4" } ?: ""
        val lang = Locale.getDefault().language.takeIf { it in LANGS }?.let { "&lang=$it" } ?: ""
        val found = parse(Http.get("$baseUrl/api/?q=$q&limit=${limit * 2}$bias$lang").decodeToString())
        return rank(dedupe(found), near).take(limit)
    }

    companion object {
        private val LANGS = setOf("en", "de", "fr")
        private val COORDS = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

        /** "12.9719, 77.6412" (what Google Maps copies for a dropped pin) as a place of its own. */
        fun coordinates(text: String): Place? {
            val m = COORDS.find(text) ?: return null
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return Place("Dropped pin", "%.5f, %.5f".format(Locale.US, lat, lon), LatLon(lat, lon), PlaceKind.PIN)
        }

        /** A rider wants the place in town, not a namesake 500 km away: rideable ones first, nearest first;
         *  the rest keep Photon's order after them. The bias alone lets famous far matches win. */
        fun rank(places: List<Place>, near: LatLon?, rideableM: Double = 60_000.0): List<Place> {
            if (near == null) return places
            val (close, far) = places.partition { Geo.distance(near, it.pos) <= rideableM }
            return close.sortedBy { Geo.distance(near, it.pos) } + far
        }

        /** OSM often maps one place twice (a node and its building, or both sides of a road): same name, metres
         *  apart. Keep the first. */
        fun dedupe(places: List<Place>, withinM: Double = 150.0): List<Place> {
            val out = ArrayList<Place>()
            for (p in places) {
                if (out.none { it.name.equals(p.name, ignoreCase = true) && Geo.distance(it.pos, p.pos) < withinM }) out += p
            }
            return out
        }

        fun parse(json: String): List<Place> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            return (0 until features.length()).mapNotNull { i ->
                val f = features.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                val street = listOfNotNull(p.opt("housenumber")?.toString(), p.optString("street").ifEmpty { null })
                    .joinToString(" ")
                val name = p.optString("name").ifEmpty { street.ifEmpty { return@mapNotNull null } }
                val detail = listOf(street, p.optString("locality"), p.optString("district"), p.optString("city"),
                    p.optString("state").takeIf { p.optString("city").isEmpty() } ?: "")
                    .filter { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }.distinct().joinToString(", ")
                Place(name, detail, LatLon(c.getDouble(1), c.getDouble(0)), kind(p.optString("osm_key"), p.optString("osm_value")))
            }
        }

        fun kind(key: String, value: String): PlaceKind = when {
            value in setOf("cafe", "coffee", "tea") -> PlaceKind.CAFE
            value in setOf("restaurant", "fast_food", "food_court", "bar", "pub", "ice_cream", "bakery") -> PlaceKind.FOOD
            value in setOf("fuel", "charging_station") -> PlaceKind.FUEL
            value in setOf("hospital", "clinic", "doctors", "pharmacy", "dentist") -> PlaceKind.HEALTH
            value in setOf("bus_station", "bus_stop", "station", "halt", "subway_entrance", "aerodrome", "platform") ||
                key == "railway" || key == "public_transport" -> PlaceKind.TRANSIT
            key == "tourism" && value in setOf("hotel", "hostel", "guest_house", "motel", "apartment") -> PlaceKind.STAY
            value in setOf("park", "garden", "playground", "nature_reserve", "pitch", "stadium") -> PlaceKind.PARK
            value in setOf("school", "college", "university", "kindergarten", "library") -> PlaceKind.EDUCATION
            key == "office" || value in setOf("office", "coworking_space", "commercial", "industrial") -> PlaceKind.WORK
            key == "shop" || value in setOf("marketplace", "mall") -> PlaceKind.SHOP
            key == "highway" -> PlaceKind.STREET
            key == "place" || key == "boundary" || key == "landuse" -> PlaceKind.AREA
            else -> PlaceKind.PLACE
        }
    }
}

/** Saved places (Home, Work) and the last few destinations, so most rides start with one tap. */
class PlaceBook(private val prefs: android.content.SharedPreferences) {
    var home: Place? = load("home")
        private set
    var work: Place? = load("work")
        private set
    var recent: List<Place> = loadList()
        private set

    fun setHome(p: Place?) { home = p; save("home", p) }
    fun setWork(p: Place?) { work = p; save("work", p) }

    fun remember(p: Place) {
        recent = (listOf(p) + recent.filterNot { it.name == p.name && Geo.distance(it.pos, p.pos) < 100 }).take(RECENT_MAX)
        prefs.edit().putString("recent", JSONArray(recent.map { it.toJson() }).toString()).apply()
    }

    fun forget(p: Place) {
        recent = recent.filterNot { it == p }
        prefs.edit().putString("recent", JSONArray(recent.map { it.toJson() }).toString()).apply()
    }

    private fun save(key: String, p: Place?) = prefs.edit().putString(key, p?.toJson()?.toString()).apply()

    private fun load(key: String) = prefs.getString(key, null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() }

    private fun loadList(): List<Place> = prefs.getString("recent", null)?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) } } }.getOrNull()
    } ?: emptyList()

    private companion object { const val RECENT_MAX = 8 }
}
