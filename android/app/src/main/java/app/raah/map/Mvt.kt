package app.raah.map

/**
 * Just enough of the Mapbox Vector Tile format (protobuf) to pull line features
 * out of one layer. Spec: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 */
object Mvt {
    /** One LineString part in tile coordinates (interleaved x, y; 0..extent). */
    class Line(val props: Map<String, String>, val xy: IntArray)

    class Layer(val extent: Int, val lines: List<Line>)

    fun lines(tile: ByteArray, layerName: String, keys: Set<String>): Layer? {
        val r = Pb(tile, 0, tile.size)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 3 && wire == 2) {
                val layer = r.sub()
                if (layerName(layer.copy()) == layerName) return readLayer(layer, keys)
            } else {
                r.skip(wire)
            }
        }
        return null
    }

    private fun layerName(r: Pb): String? {
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 1 && wire == 2) return r.string() else r.skip(wire)
        }
        return null
    }

    private fun readLayer(r: Pb, wanted: Set<String>): Layer {
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val features = ArrayList<Pb>()
        var extent = 4096
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 2 && wire == 2 -> features += r.sub()
                field == 3 && wire == 2 -> keys += r.string()
                field == 4 && wire == 2 -> values += value(r.sub())
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        val lines = ArrayList<Line>()
        for (f in features) {
            var type = 0
            var tags: IntArray? = null
            var geom: IntArray? = null
            while (f.more()) {
                val (field, wire) = f.tag()
                when {
                    field == 2 && wire == 2 -> tags = f.packed()
                    field == 3 && wire == 0 -> type = f.varint().toInt()
                    field == 4 && wire == 2 -> geom = f.packed()
                    else -> f.skip(wire)
                }
            }
            if (type != 2 || geom == null) continue
            val props = HashMap<String, String>()
            tags?.let { t ->
                for (i in 0 until t.size - 1 step 2) {
                    val k = keys.getOrNull(t[i]) ?: continue
                    if (k in wanted) props[k] = values.getOrNull(t[i + 1]) ?: continue
                }
            }
            decodeLines(geom) { lines += Line(props, it) }
        }
        return Layer(extent, lines)
    }

    /** MoveTo / LineTo command stream with zigzag deltas. Each MoveTo starts a new part. */
    private fun decodeLines(g: IntArray, emit: (IntArray) -> Unit) {
        var x = 0
        var y = 0
        var i = 0
        var part = IntArrayList()
        while (i < g.size) {
            val cmd = g[i] and 7
            val count = g[i] ushr 3
            i++
            when (cmd) {
                1, 2 -> {
                    if (cmd == 1) {
                        if (part.size >= 4) emit(part.toArray())
                        part = IntArrayList()
                    }
                    repeat(count) {
                        if (i + 1 >= g.size) return@repeat
                        x += zigzag(g[i]); y += zigzag(g[i + 1]); i += 2
                        part.add(x); part.add(y)
                    }
                }
                else -> Unit   // ClosePath has no parameters; lines don't use it
            }
        }
        if (part.size >= 4) emit(part.toArray())
    }

    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    private fun value(r: Pb): String {
        var out = ""
        while (r.more()) {
            val (field, wire) = r.tag()
            out = when {
                field == 1 && wire == 2 -> r.string()
                field == 2 && wire == 5 -> Float.fromBits(r.fixed32()).toString()
                field == 3 && wire == 1 -> Double.fromBits(r.fixed64()).toString()
                field in 4..5 && wire == 0 -> r.varint().toString()
                field == 6 && wire == 0 -> r.varint().let { (it ushr 1) xor -(it and 1) }.toString()
                field == 7 && wire == 0 -> (r.varint() != 0L).toString()
                else -> { r.skip(wire); out }
            }
        }
        return out
    }

    private class IntArrayList {
        private var a = IntArray(32)
        var size = 0
            private set
        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        fun toArray() = a.copyOf(size)
    }

    /** Minimal protobuf reader over a byte range. */
    private class Pb(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun copy() = Pb(b, pos, end)

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val c = b[pos++].toInt()
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> = varint().toInt().let { (it ushr 3) to (it and 7) }

        fun sub(): Pb {
            val len = varint().toInt()
            val r = Pb(b, pos, pos + len)
            pos += len
            return r
        }

        fun string(): String {
            val len = varint().toInt()
            val s = String(b, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun packed(): IntArray {
            val r = sub()
            val out = IntArrayList()
            while (r.more()) out.add(r.varint().toInt())
            return out.toArray()
        }

        fun fixed32(): Int {
            var v = 0
            for (k in 0 until 4) v = v or ((b[pos + k].toInt() and 0xFF) shl (8 * k))
            pos += 4
            return v
        }

        fun fixed64(): Long {
            var v = 0L
            for (k in 0 until 8) v = v or ((b[pos + k].toLong() and 0xFF) shl (8 * k))
            pos += 8
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = varint().toInt(); pos += n }   // not `pos += varint()`: that reads pos first
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }
    }
}
