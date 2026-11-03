package app.raah.net

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Blocking HTTP for the free OSM services. Call from Dispatchers.IO. */
object Http {
    /** The public OSM services ask clients to identify themselves. */
    private const val USER_AGENT = "Raah/0.1 (bike navigation display; personal use)"

    class Error(val code: Int, message: String) : IOException(message)

    fun get(url: String): ByteArray = request(url, null)

    fun postJson(url: String, json: String): ByteArray = request(url, json.toByteArray())

    /** Where [url] redirects to, without following it; null when it doesn't. */
    fun redirect(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            return if (c.responseCode in 300..399) c.getHeaderField("Location")?.let { URL(URL(url), it).toString() } else null
        } finally {
            c.disconnect()
        }
    }

    private fun request(url: String, body: ByteArray?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { maybeGunzip(it).readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw Error(code, "HTTP $code: ${bytes.decodeToString().take(200)}")
            return bytes
        } finally {
            c.disconnect()
        }
    }

    /** Vector tiles are sometimes served gzipped without Content-Encoding. */
    private fun maybeGunzip(s: InputStream): InputStream {
        val b = s.buffered()
        b.mark(2)
        val gz = b.read() == 0x1f && b.read() == 0x8b
        b.reset()
        return if (gz) GZIPInputStream(b) else b
    }
}
