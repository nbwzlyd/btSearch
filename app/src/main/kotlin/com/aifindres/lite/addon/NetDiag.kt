package com.aifindres.lite.addon

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 诊断工具：用 Java Socket 直连 BT peer，判断「App 进程能否出站 TCP」。
 * 只为排查 libtorrent connect_peer 全部失败的原因，稳定后可删。
 */
object NetDiag {
    private const val TAG = "NetDiag"

    fun probeTcp(peers: List<Pair<String, Int>>, timeoutMs: Int = 3000) {
        Thread({
            var ok = 0
            for ((ip, port) in peers.take(8)) {
                val t0 = System.currentTimeMillis()
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, port), timeoutMs)
                        ok++
                        Log.i(TAG, "tcp $ip:$port OK in ${System.currentTimeMillis() - t0}ms")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "tcp $ip:$port FAIL ${t.javaClass.simpleName} ${t.message}")
                }
            }
            Log.i(TAG, "tcp probe done: $ok/${minOf(peers.size, 8)} reachable")
        }, "netdiag").apply { isDaemon = true }.start()
    }
}
