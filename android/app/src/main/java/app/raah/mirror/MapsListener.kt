package app.raah.mirror

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.raah.RaahApp
import app.raah.proto.NavProtocol

/**
 * Mirrors Google Maps navigation when Raah isn't routing itself. Needs the user
 * to grant notification access (Settings → Notifications → Device & app notifications).
 */
class MapsListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        val e = n.extras
        val icon = n.getLargeIcon()?.let { a8(it) }
        val hash = icon?.let { NavProtocol.fnv1a(it) } ?: 0L
        val state = MapsParser.parse(
            e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            hash,
        ) ?: return
        RaahApp.engine(this).onMirror(state, icon)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS && sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            RaahApp.engine(this).onMirror(null, null)
        }
    }

    /** The maneuver bitmap as NAV_ICON_PX² alpha: white-on-transparent arrows keep their shape, coloured fills drop out. */
    private fun a8(icon: Icon): ByteArray? {
        val d = icon.loadDrawable(this) ?: return null
        val px = NavProtocol.ICON_PX
        val src = createBitmap(maxOf(1, d.intrinsicWidth), maxOf(1, d.intrinsicHeight))
        d.setBounds(0, 0, src.width, src.height)
        d.draw(Canvas(src))
        val bmp: Bitmap = src.scale(px, px)
        val argb = IntArray(px * px)
        bmp.getPixels(argb, 0, px, 0, 0, px, px)
        val out = ByteArray(px * px)
        var max = 1
        for (i in argb.indices) {
            val c = argb[i]
            val a = c ushr 24
            val lum = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
            val v = a * lum / 255
            out[i] = v.toByte()
            if (v > max) max = v
        }
        for (i in out.indices) out[i] = ((out[i].toInt() and 0xFF) * 255 / max).toByte()
        return out
    }

    companion object {
        const val MAPS = "com.google.android.apps.maps"
    }
}
