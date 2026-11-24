package com.aifindres.lite.addon

import com.aifindres.lite.net.SearchEngine
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Peer-count probe via HTTP(S) tracker announce.
 *
 * DHT (UDP 6881) is unreachable on many mainland networks (DNS pollution + UDP
 * filtering), but plaintext HTTP trackers still work, so we ask them instead:
 * GET <tracker>/announce?info_hash=..&peer_id=.. -> bencoded reply with
 *   complete   = seeders   (决定能下多快)
 *   incomplete = leechers  (同时下载中的人数)
 *
 * Tracker pool: the magnet's own "tr=" params first, then a built-in list of
 * HTTP trackers verified reachable from mainland China without a proxy.
 */
object TrackerProbe {
    private val FALLBACK_TRACKERS = listOf(
        "http://tracker.opentrackr.org:1337/announce",
        "http://open.tracker.cl:1337/announce",
    )
    private const val PEER_ID = "-UT1810-0123456789ab"
    /** Cap concurrent announces so a 200-item result page cannot flood the network. */
    private val GATE = Semaphore(6, true)
    private const val TAG = "TrackerProbe"

    fun interface Callback { fun onResult(seeders: Int, leechers: Int) }

    /** Fire-and-forget probe; callback gets best seeders/leechers seen (0 = none). */
    fun probeAsync(infoHashHex: String, magnet: String, timeoutMs: Int, cb: Callback) {
        thread(name = "tracker-probe", isDaemon = true) {
            GATE.acquire()
            try {
                val r = probe(infoHashHex, magnet, timeoutMs)
                cb.onResult(r[0], r[1])
            } finally {
                GATE.release()
            }
        }
    }

    /** Blocking probe across all trackers; returns [seeders, leechers] maxima. */
    fun probe(infoHashHex: String, magnet: String, timeoutMs: Int): IntArray {
        val bestSeed = AtomicInteger(0)
        val bestLeech = AtomicInteger(0)
        val trackers = collectTrackers(magnet).take(6)
        val deadline = System.currentTimeMillis() + timeoutMs
        val threads = trackers.map { base ->
            thread(name = "tk", isDaemon = true) {
                val r = announce(base, infoHashHex)
                if (r[0] > 0) bestSeed.updateAndGet { cur -> if (r[0] > cur) r[0] else cur }
                if (r[1] > 0) bestLeech.updateAndGet { cur -> if (r[1] > cur) r[1] else cur }
            }
        }
        for (t in threads) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            try { t.join(remain) } catch (e: InterruptedException) { break }
        }
        val result = intArrayOf(bestSeed.get(), bestLeech.get())
        android.util.Log.i(TAG, "probe ${infoHashHex.take(12)}… trackers=${trackers.size} -> seed=${result[0]} leech=${result[1]}")
        return result
    }

    internal fun collectTrackers(magnet: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("[?&]tr=([^&]+)").findAll(magnet).forEach { m ->
            try {
                val tr = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
                if (tr.startsWith("http://") || tr.startsWith("https://")) out.add(tr)
            } catch (e: Exception) {}
        }
        out.addAll(FALLBACK_TRACKERS)
        return out.toList()
    }

    /** Single announce; returns [seeders, leechers] or [0, 0]. */
    internal fun announce(baseUrl: String, infoHashHex: String): IntArray {
        return try {
            val url = "$baseUrl?${buildAnnounceQuery(infoHashHex)}"
            val body = SearchEngine.fetch(url, mapOf("User-Agent" to "Mozilla/5.0"))
            if (body == null) {
                android.util.Log.i(TAG, "announce $baseUrl -> no body")
                return intArrayOf(0, 0)
            }
            val counts = parseCounts(body)
            android.util.Log.i(TAG, "announce ${baseUrl.take(40)} -> seed=${counts[0]} leech=${counts[1]}")
            counts
        } catch (e: Exception) {
            android.util.Log.i(TAG, "announce ${baseUrl.take(40)} err: ${e.javaClass.simpleName} ${e.message}")
            intArrayOf(0, 0)
        }
    }

    private fun buildAnnounceQuery(infoHashHex: String): String {
        val sb = StringBuilder("info_hash=")
        // info_hash must be the raw 20 bytes, percent-encoded (RFC 3986 unreserved go literal)
        for (b in infoHashToBytes(infoHashHex)) {
            val v = b.toInt() and 0xFF
            val unreserved = v in 0x41..0x5A || v in 0x61..0x7A || v in 0x30..0x39 ||
                v == 0x2D || v == 0x2E || v == 0x5F || v == 0x7E
            if (unreserved) sb.append(v.toChar()) else sb.append('%').append(String.format("%02X", v))
        }
        sb.append("&peer_id=").append(PEER_ID)
        sb.append("&port=6881&uploaded=0&downloaded=0&left=0&compact=1&event=started")
        return sb.toString()
    }

    /**
     * Decode an infohash string to its 20 raw bytes.
     * Accepts 40-char hex (most sites) or 32-char base32 (AnimeTosho/Nyaa style).
     */
    internal fun infoHashToBytes(hash: String): ByteArray {
        val h = hash.trim()
        if (h.length == 40 && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return ByteArray(20) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        if (h.length == 32) {
            // RFC 4648 base32 alphabet, no padding
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val up = h.uppercase()
            val out = ByteArray(20)
            var buffer = 0L
            var bits = 0
            var idx = 0
            for (c in up) {
                val v = alphabet.indexOf(c)
                if (v < 0) throw IllegalArgumentException("bad base32 char: $c")
                buffer = (buffer shl 5) or v.toLong()
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out[idx++] = ((buffer shr bits) and 0xFF).toByte()
                }
            }
            if (idx != 20) throw IllegalArgumentException("base32 length mismatch: $idx")
            return out
        }
        throw IllegalArgumentException("bad infohash length ${h.length}")
    }

    /** Parse "completei<N>e" and "incompletei<N>e" from the bencoded reply. */
    internal fun parseCounts(body: String): IntArray {
        val seed = Regex("completei(\\d+)e").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val leech = Regex("incompletei(\\d+)e").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return intArrayOf(seed, leech)
    }

    /**
     * 拿 compact peer 列表（"5:peers" + 长度 + 每 6 字节 IP(4)+端口(2)）。
     *
     * 用途是「边下边播」：libtorrent 自己在很多国内网络里 announce 不出去（peers 恒 0），
     * 但我们用 OkHttp 手写 announce 是通的（徽章功能就靠它），所以先把 peer 抓下来
     * 直接注入 torrent（add_torrent_params.set_peers），元数据几秒就能拿到。
     */
    fun fetchPeers(infoHashHex: String, magnet: String, timeoutMs: Int, maxPeers: Int = 40): List<Peer> {
        val out = LinkedHashMap<String, Peer>() // "ip:port" 去重
        val trackers = collectTrackers(magnet).take(6)
        val deadline = System.currentTimeMillis() + timeoutMs
        for (base in trackers) {
            if (System.currentTimeMillis() >= deadline) break
            if (out.size >= maxPeers) break
            val raw = try {
                val url = "$base?${buildAnnounceQuery(infoHashHex)}"
                SearchEngine.fetchBytes(url, mapOf("User-Agent" to "Mozilla/5.0"))
            } catch (e: Exception) {
                android.util.Log.i(TAG, "fetchPeers ${base.take(40)} err: ${e.message}")
                null
            } ?: continue
            for (p in parseCompactPeers(raw)) {
                val k = "${p.ip}:${p.port}"
                if (!out.containsKey(k)) out[k] = p
                if (out.size >= maxPeers) break
            }
        }
        android.util.Log.i(TAG, "fetchPeers ${infoHashHex.take(12)}… -> ${out.size} peers")
        return out.values.toList()
    }

    data class Peer(val ip: String, val port: Int)

    /** 从 bencode 里取 "peers" 字段（compact 格式，6 字节一条）。 */
    internal fun parseCompactPeers(b: ByteArray): List<Peer> {
        val key = "5:peers".toByteArray(Charsets.ISO_8859_1)
        val i = indexOfBytes(b, key)
        if (i < 0) return emptyList()
        var d = i + key.size
        var len = 0
        var digits = 0
        while (d < b.size && b[d] in '0'.code.toByte()..'9'.code.toByte()) {
            len = len * 10 + (b[d].toInt() - '0'.code)
            d++; digits++
        }
        if (digits == 0 || d >= b.size || b[d] != ':'.code.toByte()) return emptyList()
        val start = d + 1
        if (len <= 0 || start + len > b.size) return emptyList()
        val peers = ArrayList<Peer>(len / 6)
        var k = 0
        while (k + 6 <= len) {
            val ip = "${b[start + k].toInt() and 0xFF}.${b[start + k + 1].toInt() and 0xFF}." +
                "${b[start + k + 2].toInt() and 0xFF}.${b[start + k + 3].toInt() and 0xFF}"
            val port = ((b[start + k + 4].toInt() and 0xFF) shl 8) or (b[start + k + 5].toInt() and 0xFF)
            if (port > 0 && port < 65536 && !ip.startsWith("0.")) peers.add(Peer(ip, port))
            k += 6
        }
        return peers
    }

    private fun indexOfBytes(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
