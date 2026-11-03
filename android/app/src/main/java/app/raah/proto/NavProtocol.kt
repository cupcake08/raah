package app.raah.proto

import java.io.ByteArrayOutputStream
import java.util.UUID

/** BLE wire format shared with the device. Keep in sync with ui/nav_model.h. */
object NavProtocol {
    const val VERSION = 2
    const val ICON_PX = 96
    const val ICON_BYTES = ICON_PX * ICON_PX
    const val PRIMARY_MAX = 64
    const val SECONDARY_MAX = 96

    private fun uuid(n: Int) = UUID.fromString("8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0e%02x".format(n))
    val SERVICE: UUID = uuid(0x00)
    val STATE: UUID = uuid(0x01)
    val ICON: UUID = uuid(0x02)
    val CTRL: UUID = uuid(0x03)
    val STATUS: UUID = uuid(0x04)
    val MAP: UUID = uuid(0x05)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val F_NAVIGATING = 1
    const val F_REROUTING = 2
    const val F_ARRIVED = 4

    private const val CTRL_TIME = 0x01
    private const val CTRL_SETTINGS = 0x02

    enum class BacklightMode { ALWAYS, SMART_DIM, SMART_OFF }

    data class NavState(
        val flags: Int = 0,
        val maneuver: Maneuver = Maneuver.UNKNOWN,
        val exitNum: Int = 0,
        val distToTurnM: Long? = null,
        val distLeftM: Long? = null,
        val timeLeftS: Long? = null,
        /** Local minutes since midnight. */
        val etaMinOfDay: Int? = null,
        val speedKmh: Double? = null,
        /** FNV-1a of a Google Maps maneuver bitmap already sent as ICON chunks, 0 for none. */
        val iconHash: Long = 0,
        val primary: String = "",
        val secondary: String = "",
    )

    data class Status(
        val version: Int,
        val batteryPct: Int?,
        val batteryMv: Int,
        val charging: Boolean,
        val iconPx: Int,
        /** Id of the last map the device received complete, 0 for none. */
        val mapId: Int,
    )

    fun encodeState(s: NavState): ByteArray {
        val out = Le()
        out.u8(VERSION)
        out.u8(s.flags)
        out.u8(s.maneuver.code)
        out.u8(s.exitNum.coerceIn(0, 255))
        out.u32(s.distToTurnM ?: U32_UNKNOWN)
        out.u32(s.distLeftM ?: U32_UNKNOWN)
        out.u32(s.timeLeftS ?: U32_UNKNOWN)
        out.u16(s.etaMinOfDay ?: U16_UNKNOWN)
        out.u16(s.speedKmh?.let { (it * 10).toInt().coerceIn(0, 0xFFFE) } ?: U16_UNKNOWN)
        out.u32(s.iconHash)
        out.str(s.primary, PRIMARY_MAX)
        out.str(s.secondary, SECONDARY_MAX)
        return out.bytes()
    }

    fun encodeTime(utcSeconds: Long, utcOffsetMin: Int): ByteArray =
        Le().apply { u8(CTRL_TIME); u32(utcSeconds); u16(utcOffsetMin and 0xFFFF) }.bytes()

    fun encodeSettings(imperial: Boolean, use24h: Boolean, mode: BacklightMode, maxBrightnessPct: Int): ByteArray =
        Le().apply {
            u8(CTRL_SETTINGS)
            u8(if (imperial) 1 else 0)
            u8(if (use24h) 1 else 0)
            u8(mode.ordinal)
            u8(maxBrightnessPct.coerceIn(10, 100))
        }.bytes()

    /** Splits an A8 icon into ICON writes: u32 hash | u16 offset | bytes. */
    fun iconChunks(hash: Long, a8: ByteArray, maxWrite: Int): List<ByteArray> {
        require(a8.size == ICON_BYTES)
        val step = maxWrite - 6
        return (0 until ICON_BYTES step step).map { off ->
            val n = minOf(step, ICON_BYTES - off)
            Le().apply { u32(hash); u16(off); raw(a8, off, n) }.bytes()
        }
    }

    fun decodeStatus(b: ByteArray): Status? {
        if (b.size < 6) return null
        fun u8(i: Int) = b[i].toInt() and 0xFF
        return Status(
            version = u8(0),
            batteryPct = u8(1).takeIf { it != 0xFF },
            batteryMv = u8(2) or (u8(3) shl 8),
            charging = u8(4) != 0,
            iconPx = u8(5),
            mapId = if (b.size >= 8) u8(6) or (u8(7) shl 8) else 0,
        )
    }

    fun fnv1a(bytes: ByteArray): Long {
        var h = 2166136261L
        for (b in bytes) h = ((h xor (b.toLong() and 0xFF)) * 16777619L) and 0xFFFFFFFFL
        return if (h == 0L) 1L else h
    }

    private const val U32_UNKNOWN = 0xFFFFFFFFL
    private const val U16_UNKNOWN = 0xFFFF
}

/** Maneuver codes, matching maneuver_t in ui/nav_model.h. */
enum class Maneuver(val code: Int, val label: String) {
    UNKNOWN(0, ""), STRAIGHT(1, "Straight on"), SLIGHT_LEFT(2, "Bear left"), SLIGHT_RIGHT(3, "Bear right"),
    LEFT(4, "Turn left"), RIGHT(5, "Turn right"), SHARP_LEFT(6, "Sharp left"), SHARP_RIGHT(7, "Sharp right"),
    UTURN_LEFT(8, "U-turn"), UTURN_RIGHT(9, "U-turn"), FORK_LEFT(10, "Keep left"), FORK_RIGHT(11, "Keep right"),
    MERGE(12, "Merge"), ROUNDABOUT(13, "Roundabout"), ARRIVE(14, "Arrive"),
}

/** Little-endian byte writer. */
class Le {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = out.write(v and 0xFF)
    fun u16(v: Int) { u8(v); u8(v shr 8) }
    fun i16(v: Int) = u16(v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
    fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun raw(b: ByteArray, off: Int = 0, len: Int = b.size) = out.write(b, off, len)

    /** u8 length + UTF-8, cut on a code point boundary to at most [max] bytes. */
    fun str(s: String, max: Int) {
        val b = s.toByteArray(Charsets.UTF_8)
        var n = minOf(b.size, max)
        while (n in 1 until b.size && (b[n].toInt() and 0xC0) == 0x80) n--
        u8(n)
        raw(b, 0, n)
    }

    val size get() = out.size()
    fun bytes(): ByteArray = out.toByteArray()
}
