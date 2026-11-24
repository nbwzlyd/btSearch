package com.aifindres.lite.addon

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Minimal BEP-3/BEP-9 DHT client: ask public bootstrap routers for peers of an
 * infohash and count compact peer entries. Pure Kotlin/JVM, no dependencies.
 */
object DhtProbe {
    private val ROUTERS = arrayOf(
        arrayOf("router.utorrent.com", "6881"),
        arrayOf("router.bittorrent.com", "6881"),
        arrayOf("dht.transmissionbt.com", "6881"),
        arrayOf("router.siloton.us", "6881"),
        arrayOf("dht.ael.is", "6881"),
    )
    private val SELF_ID = hex("54696D65727375696D706C653132333435363738393031")

    fun interface Callback { fun onResult(peers: Int) }

    /** Fire-and-forget probe on a daemon thread; callback receives best peer count (0 = none). */
    fun probeAsync(infoHashHex: String, timeoutMs: Int, cb: Callback) {
        thread(name = "dht-probe", isDaemon = true) {
            val n = probe(infoHashHex, timeoutMs)
            Log.i("DhtProbe", "probe $infoHashHex -> $n peers")
            cb.onResult(n)
        }
    }

    /** Blocking probe: best peer count found within timeoutMs across public routers. */
    fun probe(infoHashHex: String, timeoutMs: Int): Int {
        val best = AtomicInteger(0)
        val deadline = System.currentTimeMillis() + timeoutMs
        val threads = ROUTERS.map { r ->
            thread(name = "dht-${r[0]}", isDaemon = true) {
                val n = queryRouter(r[0], r[1].toInt(), infoHashHex, maxOf(1500, timeoutMs))
                Log.i("DhtProbe", "${r[0]} -> $n")
                if (n > 0) best.updateAndGet { cur -> if (n > cur) n else cur }
            }
        }
        for (t in threads) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            try { t.join(remain) } catch (e: InterruptedException) { break }
        }
        return best.get()
    }

    private fun queryRouter(host: String, port: Int, hashHex: String, timeoutMs: Int): Int {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(host)
            val hash = hex(hashHex)
            val tid = byteArrayOf(0x4A, 0x1F)
            val head = "d1:ad2:id20:".toByteArray(Charsets.ISO_8859_1)
            val mid = "6:info_hash20:".toByteArray(Charsets.ISO_8859_1)
            val tail = "e1:q9:get_peers1:v4:UT181:t2:".toByteArray(Charsets.ISO_8859_1)
            val end = "y1:qe".toByteArray(Charsets.ISO_8859_1)
            val buf = ByteArray(head.size + 20 + mid.size + 20 + tail.size + 2 + end.size)
            var off = 0
            System.arraycopy(head, 0, buf, off, head.size); off += head.size
            System.arraycopy(SELF_ID, 0, buf, off, 20); off += 20
            System.arraycopy(mid, 0, buf, off, mid.size); off += mid.size
            System.arraycopy(hash, 0, buf, off, 20); off += 20
            System.arraycopy(tail, 0, buf, off, tail.size); off += tail.size
            buf[off] = tid[0]; buf[off + 1] = tid[1]; off += 2
            System.arraycopy(end, 0, buf, off, end.size); off += end.size

            sock.send(DatagramPacket(buf, off, addr, port))

            val rx = ByteArray(2048)
            var peers = 0
            val endAt = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < endAt) {
                val packet = DatagramPacket(rx, rx.size)
                try {
                    sock.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val n = countValues(packet.data, packet.length)
                if (n > 0) {
                    peers += n
                    break
                }
            }
            peers
        } catch (t: Throwable) {
            Log.w("DhtProbe", "$host err: ${t.javaClass.simpleName}: ${t.message}")
            0
        } finally {
            sock?.close()
        }
    }

    /** Count 6-byte compact IPv4 entries in the bencoded "values" list. */
    internal fun countValues(b: ByteArray, len: Int): Int {
        val s = String(b, 0, len, Charsets.ISO_8859_1)
        var i = s.indexOf("values")
        if (i < 0) return 0
        i += "values".length
        if (i >= len || b[i] != 'l'.code.toByte()) return 0
        i++
        var count = 0
        while (i < len && b[i] != 'e'.code.toByte()) {
            var j = i
            while (j < len && b[j] in '0'.code.toByte()..'9'.code.toByte()) j++
            if (j >= len || b[j] != ':'.code.toByte()) break
            val n = s.substring(i, j).toIntOrNull() ?: break
            i = j + 1 + n
            count++
        }
        return count
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
