package com.aifindres.lite.data

import java.util.Locale

class MagnetItem(
    val title: String,
    val date: String,
    val size: String,
    val url: String,
    val magnet: String,
    val siteId: Int,
    val siteName: String,
) {
    /** Tracker probe result: seeders (complete), 0 = unknown. */
    @Volatile var seeders: Int = 0
    /** Tracker probe result: leechers (incomplete), 0 = unknown. */
    @Volatile var leechers: Int = 0
    /** Quality badge parsed from title, "" = none. */
    @Volatile var quality: String = ""
    /** True while a DHT probe is in flight for this item. */
    @Volatile var probing: Boolean = false

    /** Extract lowercase infohash (40-hex or 32-base32) from magnet link, or null. */
    fun infoHash(): String? {
        if (magnet.isEmpty()) return null
        val i = magnet.indexOf("btih:")
        if (i < 0) return null
        val start = i + 5
        var end = start
        while (end < magnet.length && magnet[end].isLetterOrDigit()) end++
        if (end == start) return null
        var h = magnet.substring(start, end).lowercase(Locale.ROOT)
        // Some sites emit percent-encoded or truncated display forms; decode once.
        if (h.contains('%')) {
            h = try {
                java.net.URLDecoder.decode(h, "UTF-8").lowercase(Locale.ROOT)
            } catch (e: Exception) {
                return null
            }
        }
        val isHex40 = h.length == 40 && h.all { it in '0'..'9' || it in 'a'..'f' }
        val isBase32 = h.length == 32 && h.all { it in 'a'..'z' || it in '2'..'7' }
        return if (isHex40 || isBase32) h else null
    }
}
