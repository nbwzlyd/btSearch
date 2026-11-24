package com.aifindres.lite.addon

import java.util.regex.Pattern

/**
 * Parse resolution / codec tags from a torrent title into a badge string.
 *
 * NOTE: `\b` does not fire between a CJK char and an ASCII digit
 * ("4K增强版": 4 is ASCII, 强 is a word char for Java regex), so all the
 * resolution patterns use an explicit ASCII-boundary lookaround instead.
 */
object QualityTag {
    private const val B = "(?<![0-9A-Za-z])"
    private const val E = "(?![0-9A-Za-z])"
    private val P4320 = Pattern.compile("(?i)$B(4320p|8k)$E")
    private val P2160 = Pattern.compile("(?i)$B(2160p|4k|uhd|ultra\\s*hd)$E")
    private val P1080 = Pattern.compile("(?i)$B(1080[pi]|fhd)$E")
    private val P720 = Pattern.compile("(?i)${B}720p$E")
    private val PDOLBY = Pattern.compile("(?i)(dolby\\s*vision|dolbyvision|杜比视界|${B}dv$E|dovi)")
    private val PHDR = Pattern.compile("(?i)$B(hdr10\\+?|hdr|hdr10plus)$E")
    private val PHEVC = Pattern.compile("(?i)$B(x265|h\\.?265|hevc)$E")
    private val PAUDIO = Pattern.compile("(?i)(atmos|全景声|dts-hd|dtsx|truehd)")

    /** e.g. "4K·HDR·HEVC"; "" when nothing matched. */
    fun badge(title: String?): String {
        if (title.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        when {
            P4320.matcher(title).find() -> sb.append("8K·")
            P2160.matcher(title).find() -> sb.append("4K·")
            P1080.matcher(title).find() -> sb.append("1080P·")
            P720.matcher(title).find() -> sb.append("720P·")
        }
        if (PDOLBY.matcher(title).find()) sb.append("杜比视界·")
        else if (PHDR.matcher(title).find()) sb.append("HDR·")
        if (PHEVC.matcher(title).find()) sb.append("HEVC·")
        if (PAUDIO.matcher(title).find()) sb.append("全景声·")
        return if (sb.isEmpty()) "" else sb.deleteCharAt(sb.length - 1).toString()
    }

    /**
     * e.g. "🌱12 种 · 🔥5 下载中"; "" when both unknown.
     * Seeders drive download speed, leechers show how busy the swarm is.
     */
    fun peersBadge(seeders: Int, leechers: Int): String {
        val parts = ArrayList<String>(2)
        if (seeders > 0) parts.add("🌱${seeders} 种")
        if (leechers > 0) parts.add("🔥${leechers} 下载中")
        return parts.joinToString(" · ")
    }
}
