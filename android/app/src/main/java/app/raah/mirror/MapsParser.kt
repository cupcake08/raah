package app.raah.mirror

import app.raah.proto.Maneuver
import app.raah.proto.NavProtocol
import app.raah.proto.NavProtocol.NavState
import kotlin.math.roundToLong

/**
 * Reads Google Maps' turn-by-turn notification. Its layout isn't documented and
 * changes between versions, so this looks for the parts by shape (a distance, an
 * instruction, "min", a clock time) instead of by position. The maneuver arrow
 * itself comes from the notification's bitmap, so a missed keyword only loses the
 * vector icon fallback.
 */
object MapsParser {
    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+)\s?(?:hr|hrs|h)\b""", RegexOption.IGNORE_CASE)
    private val MINS = Regex("""(\d+)\s?min""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""\b(\d{1,2}):(\d{2})\s?([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)
    private val SEP = Regex("""\s*[·•|–-]\s*""")

    fun parse(title: String?, text: String?, subText: String?, iconHash: Long): NavState? {
        val t = title.orEmpty().trim()
        val x = text.orEmpty().trim()
        if (t.isEmpty() && x.isEmpty()) return null

        val distMatch = DIST.find(t) ?: DIST.find(x)
        val instruction = listOf(t, x)
            .map { it.replace(DIST, "").split(SEP).filter { p -> p.isNotBlank() }.joinToString(" · ").trim() }
            .firstOrNull { it.length > 2 } ?: ""
        val arrived = instruction.contains("arrive", true) || instruction.contains("destination", true)

        val tail = subText.orEmpty()
        val minutes = (HOURS.find(tail)?.groupValues?.get(1)?.toLong() ?: 0) * 60 +
            (MINS.find(tail)?.groupValues?.get(1)?.toLong() ?: 0)
        val left = DIST.find(tail)?.let { toMetres(it) }
        val eta = CLOCK.find(tail)?.let { m ->
            var h = m.groupValues[1].toInt() % 24
            val pm = m.groupValues[3].startsWith("p", true)
            if (m.groupValues[3].isNotEmpty() && h < 12 && pm) h += 12
            if (m.groupValues[3].startsWith("a", true) && h == 12) h = 0
            h * 60 + m.groupValues[2].toInt()
        }

        return NavState(
            flags = NavProtocol.F_NAVIGATING or if (arrived) NavProtocol.F_ARRIVED else 0,
            maneuver = if (arrived) Maneuver.ARRIVE else maneuverOf(instruction),
            exitNum = Regex("""(\d)(?:st|nd|rd|th) exit""", RegexOption.IGNORE_CASE).find(instruction)
                ?.groupValues?.get(1)?.toInt() ?: 0,
            distToTurnM = distMatch?.let { toMetres(it) },
            distLeftM = left,
            timeLeftS = if (minutes > 0) minutes * 60 else null,
            etaMinOfDay = eta,
            iconHash = iconHash,
            primary = street(instruction),
            secondary = instruction,
        )
    }

    private fun toMetres(m: MatchResult): Long {
        val v = m.groupValues[1].replace(',', '.').toDouble()
        return when (m.groupValues[2].lowercase()) {
            "km" -> v * 1000
            "mi" -> v * 1609.344
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            else -> v
        }.roundToLong()
    }

    /** "Turn left onto MG Road" → "MG Road"; falls back to the whole instruction. */
    private fun street(instruction: String): String {
        for (k in listOf(" onto ", " on to ", " toward ", " towards ", " on ")) {
            val i = instruction.indexOf(k, ignoreCase = true)
            if (i >= 0) return instruction.substring(i + k.length).trim()
        }
        return instruction
    }

    fun maneuverOf(s: String): Maneuver {
        val l = s.lowercase()
        val left = "left" in l
        val right = "right" in l
        return when {
            "roundabout" in l || "rotary" in l -> Maneuver.ROUNDABOUT
            "u-turn" in l || "uturn" in l || "make a u" in l -> if (right) Maneuver.UTURN_RIGHT else Maneuver.UTURN_LEFT
            "sharp" in l && left -> Maneuver.SHARP_LEFT
            "sharp" in l && right -> Maneuver.SHARP_RIGHT
            "slight" in l && left -> Maneuver.SLIGHT_LEFT
            "slight" in l && right -> Maneuver.SLIGHT_RIGHT
            "keep" in l && left -> Maneuver.FORK_LEFT
            "keep" in l && right -> Maneuver.FORK_RIGHT
            "merge" in l -> Maneuver.MERGE
            left -> Maneuver.LEFT
            right -> Maneuver.RIGHT
            "continue" in l || "head" in l || "straight" in l -> Maneuver.STRAIGHT
            else -> Maneuver.UNKNOWN
        }
    }
}
