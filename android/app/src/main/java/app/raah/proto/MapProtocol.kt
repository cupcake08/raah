package app.raah.proto

import kotlin.math.roundToInt

/** MAP characteristic messages. Keep in sync with ui/map_proto.h. */
object MapProtocol {
    const val MAX_PTS = 3072
    const val MAX_LINES = 1024
    const val MAX_ROUTE = 512
    const val MAX_LINE_PTS = 255

    /** Road classes, matching map_class_t in ui/ui_map.h. */
    const val CLASS_SERVICE = 0
    const val CLASS_MINOR = 1
    const val CLASS_MID = 2
    const val CLASS_MAJOR = 3

    private const val BEGIN = 0x10
    private const val PTS = 0x11
    private const val LINES = 0x12
    private const val ROUTE = 0x13
    private const val COMMIT = 0x14
    private const val CLEAR = 0x15
    private const val POSE = 0x20

    data class Line(val cls: Int, val n: Int, val first: Int)

    /** Geometry in whole metres east/north of the map's anchor; points are interleaved x, y. */
    class Payload(val id: Int, val pts: ShortArray, val lines: List<Line>, val route: ShortArray) {
        val ptCount get() = pts.size / 2
        val routeCount get() = route.size / 2

        init {
            require(id in 1..0xFFFF)
            require(ptCount <= MAX_PTS && lines.size <= MAX_LINES && routeCount <= MAX_ROUTE)
            require(lines.all { it.cls in 0..3 && it.n in 2..MAX_LINE_PTS && it.first + it.n <= ptCount })
        }
    }

    /** BEGIN, the chunks, then COMMIT, each fitting in one ATT write of [maxWrite] bytes. */
    fun encode(map: Payload, maxWrite: Int): List<ByteArray> {
        val per = (maxWrite - 5) / 4
        require(per > 0)
        val out = mutableListOf<ByteArray>()
        out += Le().apply { u8(BEGIN); u16(map.id); u16(map.ptCount); u16(map.lines.size); u16(map.routeCount) }.bytes()
        fun points(type: Int, xy: ShortArray) {
            val n = xy.size / 2
            for (first in 0 until n step per) {
                out += Le().apply {
                    u8(type); u16(map.id); u16(first)
                    for (i in first until minOf(n, first + per)) { i16(xy[2 * i].toInt()); i16(xy[2 * i + 1].toInt()) }
                }.bytes()
            }
        }
        points(PTS, map.pts)
        for (first in map.lines.indices step per) {
            out += Le().apply {
                u8(LINES); u16(map.id); u16(first)
                for (l in map.lines.subList(first, minOf(map.lines.size, first + per))) { u8(l.cls); u8(l.n); u16(l.first) }
            }.bytes()
        }
        points(ROUTE, map.route)
        out += Le().apply { u8(COMMIT); u16(map.id) }.bytes()
        return out
    }

    fun pose(mapId: Int, xM: Double, yM: Double, headingDeg: Double, mPerPx: Double): ByteArray = Le().apply {
        u8(POSE)
        u16(mapId)
        i32((xM * 10).roundToInt())
        i32((yM * 10).roundToInt())
        u16(((headingDeg % 360 + 360) % 360 * 10).roundToInt() % 3600)
        u16((mPerPx * 100).roundToInt().coerceIn(1, 0xFFFF))
    }.bytes()

    fun clear(): ByteArray = byteArrayOf(CLEAR.toByte())
}
