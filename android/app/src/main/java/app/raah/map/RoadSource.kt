package app.raah.map

import app.raah.geo.LatLon
import app.raah.geo.LocalFrame
import app.raah.net.Http
import app.raah.proto.MapProtocol
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A road polyline: map class plus interleaved lat, lon. */
class Road(val cls: Int, val latLon: DoubleArray)

/** Loads one z14 vector tile (OpenMapTiles schema), or null if unavailable. */
fun interface TileFetcher {
    fun fetch(z: Int, x: Int, y: Int): ByteArray?
}

/** OpenFreeMap: free, keyless OpenStreetMap vector tiles. Tiles are cached on disk, so rides work offline once seen. */
class OpenFreeMapTiles(private val cacheDir: File) : TileFetcher {
    private var template: String? = null

    override fun fetch(z: Int, x: Int, y: Int): ByteArray? {
        val url = urlTemplate() ?: return null
        val version = url.substringAfter("/planet/").substringBefore('/')
        val f = File(cacheDir, "tiles/$version/${z}_${x}_$y.pbf")
        if (f.isFile) return f.readBytes()
        val bytes = runCatching { Http.get(url.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")) }
            .getOrNull() ?: return null
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return bytes
    }

    /** The tile URL names the current planet build, so it comes from the TileJSON (remembered for a day). */
    private fun urlTemplate(): String? {
        template?.let { return it }
        val f = File(cacheDir, "tiles/tilejson_url.txt")
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) {
            return f.readText().also { template = it }
        }
        val url = runCatching {
            JSONObject(Http.get("https://tiles.openfreemap.org/planet").decodeToString())
                .getJSONArray("tiles").getString(0)
        }.getOrNull() ?: return if (f.isFile) f.readText().also { template = it } else null
        f.parentFile?.mkdirs()
        f.writeText(url)
        template = url
        return url
    }
}

/** Roads around a point, decoded from z14 tiles and kept in a small in-memory cache. */
class RoadSource(private val fetcher: TileFetcher) {
    private val cache = object : LinkedHashMap<Long, List<Road>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Road>>) = size > 12
    }

    fun roadsAround(center: LatLon, radiusM: Double): List<Road> {
        val frame = LocalFrame(center)
        val sw = frame.toLatLon(-radiusM, -radiusM)
        val ne = frame.toLatLon(radiusM, radiusM)
        val (x0, y1) = tileOf(sw)
        val (x1, y0) = tileOf(ne)
        val out = ArrayList<Road>()
        for (x in x0..x1) for (y in y0..y1) out += tile(x, y)
        return out
    }

    @Synchronized
    private fun tile(x: Int, y: Int): List<Road> {
        val key = x.toLong() shl 32 or y.toLong()
        cache[key]?.let { return it }
        val bytes = fetcher.fetch(Z, x, y) ?: return emptyList()   // not cached: retried on the next window
        val layer = Mvt.lines(bytes, "transportation", setOf("class")) ?: return emptyList<Road>().also { cache[key] = it }
        val n = (1 shl Z).toDouble()
        val roads = layer.lines.mapNotNull { line ->
            val cls = classOf(line.props["class"]) ?: return@mapNotNull null
            val ll = DoubleArray(line.xy.size)
            for (i in 0 until line.xy.size / 2) {
                val tx = x + line.xy[2 * i].toDouble() / layer.extent
                val ty = y + line.xy[2 * i + 1].toDouble() / layer.extent
                ll[2 * i] = Math.toDegrees(atan(sinh(PI * (1 - 2 * ty / n))))
                ll[2 * i + 1] = tx / n * 360 - 180
            }
            Road(cls, ll)
        }
        cache[key] = roads
        return roads
    }

    companion object {
        const val Z = 14

        fun tileOf(p: LatLon): Pair<Int, Int> {
            val n = 1 shl Z
            val x = floor((p.lon + 180) / 360 * n).toInt()
            val lat = Math.toRadians(p.lat)
            val y = floor((1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * n).toInt()
            return x to y
        }

        /** OpenMapTiles transportation classes → device road classes. Paths, rail and ferries are left out. */
        fun classOf(c: String?): Int? = when (c) {
            "motorway", "trunk", "primary" -> MapProtocol.CLASS_MAJOR
            "secondary", "tertiary" -> MapProtocol.CLASS_MID
            "minor" -> MapProtocol.CLASS_MINOR
            "service", "track" -> MapProtocol.CLASS_SERVICE
            else -> null
        }
    }
}
