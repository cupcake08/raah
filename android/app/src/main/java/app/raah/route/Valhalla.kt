package app.raah.route

import app.raah.geo.Geo
import app.raah.geo.LatLon
import app.raah.net.Http
import app.raah.proto.Maneuver
import org.json.JSONArray
import org.json.JSONObject

data class Step(
    val maneuver: Maneuver,
    val exitNum: Int,
    val instruction: String,
    /** Street the maneuver turns onto (or the destination name for ARRIVE). */
    val street: String,
    /** Index into [Route.shape] where the maneuver happens. */
    val beginIdx: Int,
    val lengthM: Double,
    val timeS: Double,
)

data class Route(val shape: List<LatLon>, val steps: List<Step>, val lengthM: Double, val timeS: Double)

enum class Vehicle(val costing: String) { SCOOTER("motor_scooter"), BICYCLE("bicycle") }

/** Routing on the public FOSSGIS Valhalla server: free, keyless, OpenStreetMap data. */
class Valhalla(private val baseUrl: String = "https://valhalla1.openstreetmap.de") {

    fun route(from: LatLon, to: LatLon, vehicle: Vehicle, headingDeg: Double? = null): Route {
        val start = JSONObject().put("lat", from.lat).put("lon", from.lon)
        if (headingDeg != null) start.put("heading", headingDeg.toInt()).put("heading_tolerance", 60)
        val req = JSONObject()
            .put("locations", JSONArray().put(start).put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", vehicle.costing)
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "en-US"))
        return parse(Http.postJson("$baseUrl/route", req.toString()).decodeToString())
    }

    companion object {
        fun parse(json: String): Route {
            val trip = JSONObject(json).getJSONObject("trip")
            val shape = ArrayList<LatLon>()
            val steps = ArrayList<Step>()
            val legs = trip.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val leg = legs.getJSONObject(l)
                val offset = shape.size
                shape += Geo.decodePolyline6(leg.getString("shape"))
                val ms = leg.getJSONArray("maneuvers")
                for (i in 0 until ms.length()) {
                    val m = ms.getJSONObject(i)
                    val type = m.getInt("type")
                    steps += Step(
                        maneuver = maneuverFor(type),
                        exitNum = if (type == 26) m.optInt("roundabout_exit_count", 0) else 0,
                        instruction = m.optString("instruction"),
                        street = names(m.optJSONArray("street_names")) ?: names(m.optJSONArray("begin_street_names")) ?: "",
                        beginIdx = offset + m.getInt("begin_shape_index"),
                        lengthM = m.optDouble("length", 0.0) * 1000,
                        timeS = m.optDouble("time", 0.0),
                    )
                }
            }
            val summary = trip.getJSONObject("summary")
            return Route(shape, steps, summary.getDouble("length") * 1000, summary.getDouble("time"))
        }

        private fun names(a: JSONArray?): String? =
            a?.takeIf { it.length() > 0 }?.let { arr -> (0 until arr.length()).joinToString(" / ") { arr.getString(it) } }

        /** Valhalla maneuver type → device icon. https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/ */
        fun maneuverFor(type: Int): Maneuver = when (type) {
            4, 5, 6 -> Maneuver.ARRIVE
            9, 18, 20 -> Maneuver.SLIGHT_RIGHT      // slight right, ramp right, exit right
            10 -> Maneuver.RIGHT
            11 -> Maneuver.SHARP_RIGHT
            12 -> Maneuver.UTURN_RIGHT
            13 -> Maneuver.UTURN_LEFT
            14 -> Maneuver.SHARP_LEFT
            15 -> Maneuver.LEFT
            16, 19, 21 -> Maneuver.SLIGHT_LEFT       // slight left, ramp left, exit left
            23 -> Maneuver.FORK_RIGHT                // stay right
            24 -> Maneuver.FORK_LEFT                 // stay left
            25, 37, 38 -> Maneuver.MERGE
            26 -> Maneuver.ROUNDABOUT
            else -> Maneuver.STRAIGHT                // start, continue, becomes, stay straight, roundabout exit, ferry
        }
    }
}
