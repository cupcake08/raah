package app.raah

import app.raah.map.RoadSource
import app.raah.map.TileFetcher
import app.raah.route.Route
import app.raah.route.Valhalla

/** Real data captured once: a Valhalla motor_scooter route and OpenFreeMap z14 tiles in Indiranagar, Bengaluru. */
object Fixtures {
    fun bytes(name: String): ByteArray? = javaClass.classLoader!!.getResourceAsStream(name)?.use { it.readBytes() }

    val route: Route by lazy { Valhalla.parse(bytes("valhalla_indiranagar.json")!!.decodeToString()) }

    val tiles = TileFetcher { z, x, y -> bytes("tile_${z}_${x}_$y.pbf") }

    fun roads() = RoadSource(tiles)
}
