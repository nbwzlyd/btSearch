package com.aifindres.lite.play

import android.content.Context
import android.util.Log
import com.aifindres.lite.addon.TrackerProbe
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import java.io.File

/**
 * 磁力链接「边下边播」会话：jlibtorrent 拉元数据 → 只下最大的视频文件 → 顺序下载。
 * 播放由 [LocalTorrentServer] 通过本地 HTTP 暴露给 ExoPlayer（支持 Range / 等待分片）。
 */
class TorrentStreamSession(
    private val context: Context,
    private val magnet: String,
) {
    companion object {
        private const val TAG = "TorrentStream"
        private const val METADATA_TIMEOUT_MS = 60_000L
        private const val HEAD_BURST_PIECES = 48
        /** 播放器读到哪里，就把它前面这么多分片设为「尽快要」（约 4MB 一档 × 4） */
        private const val STREAM_WINDOW_PIECES = 96
        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg", "rmvb", "3gp", "iso"
        )
    }

    val saveDir = File(context.getExternalFilesDir(null), "torrent_cache").apply { mkdirs() }

    private var session: SessionManager? = null
    private var handle: TorrentHandle? = null

    /** 视频文件本地路径（元数据就绪后有效） */
    @Volatile var videoFile: File? = null
        private set
    @Volatile var videoSize: Long = 0
        private set
    @Volatile var torrentName: String = ""
        private set
    @Volatile var ready: Boolean = false
        private set
    /** 失败原因（人类可读），ready=false 时给 UI 展示 */
    @Volatile var failReason: String? = null
        private set

    /** 视频文件在整块存储里的起始偏移（用于把文件内偏移换算成分片号） */
    @Volatile private var fileOffset: Long = 0
    @Volatile private var pieceLength: Int = 0
    @Volatile private var fileIndex: Int = -1
    /** 上一次已提过优先级的窗口起点，避免每个请求都 clearPieceDeadlines */
    @Volatile private var lastPrioritizedStart: Int = -1

    /** 启动引擎并等元数据（阻塞，必须子线程调用） */
    fun start(): Boolean {
        try {
            val settings = SettingsPack()
            // 桌面实测（T7）：显式 listen_interfaces=0.0.0.0:6881 + announce_to_all_trackers/tiers
            // 后 3 秒就能从 opentrackr 拿到 peers 并收到元数据；默认设置下 announce 迟迟不触发。
            try {
                settings.setString(
                    com.frostwire.jlibtorrent.swig.settings_pack.string_types.listen_interfaces.swigValue(),
                    "0.0.0.0:0"
                )
                settings.setBoolean(
                    com.frostwire.jlibtorrent.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true
                )
                settings.setBoolean(
                    com.frostwire.jlibtorrent.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true
                )
                settings.setBoolean(
                    com.frostwire.jlibtorrent.swig.settings_pack.bool_types.prefer_udp_trackers.swigValue(), false
                )
            } catch (t: Throwable) {
                Log.w(TAG, "apply settings failed", t)
            }
            val sm = SessionManager(false)
            // 注意：AlertListener 的 alert() 跑在 native 线程，跨线程调 swig 对象可能直接 SIGABRT，
            // 这里不做 alert 诊断，改用轮询 + tracker 端点状态。
            sm.start(SessionParams(settings))
            session = sm
            Log.i(TAG, "session started, listenEndpoints=${sm.listenEndpoints()}, ifaces=${sm.listenInterfaces()}")
            // 诊断：App 进程内能否直连 tracker（区分「网络/DNS 被挡」与「libtorrent 不 announce」）
            try {
                val u = java.net.URL("http://tracker.opentrackr.org:1337/announce?info_hash=%ed%0f%01%34%75%53%5f%19%b5%41%0d%68%fd%20%f6%27%e4%95%0b%1f&peer_id=-UT1810-0123456789ab&port=6881&uploaded=0&downloaded=0&left=0&compact=1&event=started")
                val c = u.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val body = c.inputStream.use { it.readBytes() }
                Log.i(TAG, "app tracker probe: code=${c.responseCode} len=${body.size} head=${String(body).take(60)}")
            } catch (t: Throwable) {
                Log.w(TAG, "app tracker probe failed: ${t.javaClass.simpleName} ${t.message}")
            }
            Log.i(TAG, "session started, adding magnet")

            // 关键做法（桌面 T8 实测有效）：libtorrent 自己 announce 在很多国内网络里出不去
            // （peers 恒 0、元数据永远拿不到），但我们用 OkHttp 手写 announce 是通的（徽章功能就靠它）。
            // 所以先用 TrackerProbe 抓一轮 compact peers，直接塞进 add_torrent_params.set_peers，
            // libtorrent 不用请求 tracker 就能连 peer 拉元数据 —— 实测 5 秒内 METADATA。
            val hashForPeers = infoHash(magnet)
            val injected = try {
                if (hashForPeers != null) {
                    TrackerProbe.fetchPeers(hashForPeers, magnet, 8000, 40)
                } else emptyList()
            } catch (t: Throwable) {
                Log.w(TAG, "fetchPeers failed: ${t.message}")
                emptyList()
            }
            Log.i(TAG, "injecting ${injected.size} peers from http trackers")
            if (injected.isNotEmpty()) {
                try {
                    val ec = com.frostwire.jlibtorrent.swig.error_code()
                    val atp = com.frostwire.jlibtorrent.swig.add_torrent_params.parse_magnet_uri(
                        withFallbackTrackers(magnet), ec
                    )
                    if (ec.value() == 0) {
                        val vec = com.frostwire.jlibtorrent.swig.tcp_endpoint_vector()
                        for (p in injected) {
                            try { vec.push_back(com.frostwire.jlibtorrent.TcpEndpoint(p.ip, p.port).swig()) } catch (t: Throwable) { }
                        }
                        atp.set_peers(vec)
                        atp.setSave_path(saveDir.absolutePath)
                        sm.swig().async_add_torrent(atp)
                    } else {
                        Log.w(TAG, "parse_magnet_uri failed: ${ec.message()}")
                        sm.download(withFallbackTrackers(magnet), saveDir)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "peer injection failed, fallback to download(): ${t.message}")
                    sm.download(withFallbackTrackers(magnet), saveDir)
                }
            } else {
                sm.download(withFallbackTrackers(magnet), saveDir)
            }

            val hash = infoHash(magnet) ?: run {
                failReason = "磁力链接里没有可用的 info hash"
                return false
            }
            val sha1 = try {
                sha1FromHex(hash)
            } catch (t: Throwable) {
                failReason = "info hash 解析失败"
                return false
            }

            // 轮询等元数据
            val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
            var h: TorrentHandle? = null
            var tick = 0
            var resumed = false
            while (System.currentTimeMillis() < deadline) {
                val found = try { sm.find(sha1) } catch (t: Throwable) { null }
                // ⚠️ 关键：magnet 加入后 libtorrent 会给 torrent 打上 PAUSED 标志（实测 flags=24 含 16），
                // 暂停状态下完全不会 announce tracker → peer 恒 0 → 永远拿不到元数据。
                // download() 只清掉了 AUTO_MANAGED，所以必须自己 resume()。
                if (!resumed && found != null && found.isValid) {
                    try {
                        found.resume()
                        resumed = true
                        Log.i(TAG, "torrent resumed, flags=${found.flags()?.to_int()}")
                    } catch (t: Throwable) {
                        Log.w(TAG, "resume failed", t)
                    }
                }
                if (tick % 5 == 0) {
                    val diag = try {
                        if (found == null) "find=NULL"
                        else buildString {
                            append("valid=").append(found.isValid)
                            append(" hasMeta=").append(found.torrentFile() != null)
                            val st = try { found.status() } catch (e: Throwable) { null }
                            if (st != null) {
                                append(" state=").append(st.state())
                                append(" peers=").append(st.numPeers())
                                append(" seeds=").append(st.numSeeds())
                                append(" done=").append(st.totalDone())
                                append(" err=").append(st.errorCode()?.value())
                            }
                            val trs = try { found.trackers() } catch (e: Throwable) { null }
                            if (trs != null) {
                                append(" nTrackers=").append(trs.size)
                                if (tick == 0) {
                                    for (t in trs) append(" [").append(t.url()).append("]")
                                    try { found.forceReannounce() } catch (e: Throwable) { }
                                }
                                if (tick % 10 == 0 && trs.isNotEmpty()) {
                                    try {
                                        for (ep in trs[0].endpoints()) {
                                            append(" ep{work=").append(ep.isWorking())
                                            append(" fails=").append(ep.fails())
                                            append(" msg=").append(ep.message())
                                            append(" err=").append(ep.lastError()?.message())
                                            append("}")
                                        }
                                    } catch (e: Throwable) {
                                        append(" epErr=").append(e.message)
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        "diag err ${t.message}"
                    }
                    Log.i(TAG, "wait metadata tick=$tick $diag")
                }
                tick++
                if (found != null && found.isValid) {
                    if (found.torrentFile() != null) { h = found; break }
                    val ec = try { found.status().errorCode() } catch (t: Throwable) { null }
                    if (ec != null && ec.value() != 0) {
                        failReason = "种子加载失败：${ec.message()}"
                        return false
                    }
                }
                if (!sm.isRunning) {
                    failReason = "BT 引擎已停止"
                    return false
                }
                Thread.sleep(400)
            }
            if (h == null) {
                failReason = "获取种子信息超时（可能没有做种，或网络屏蔽了 BT）"
                return false
            }
            handle = h
            val tinfo: TorrentInfo = h.torrentFile() ?: run {
                failReason = "获取种子信息失败"
                return false
            }
            torrentName = tinfo.name() ?: "video"

            // 选最大的视频文件
            val files = tinfo.files()
            var best = -1
            var bestSize = 0L
            for (i in 0 until files.numFiles()) {
                val path = files.filePath(i) ?: continue
                val ext = path.substringAfterLast('.', "").lowercase()
                val size = files.fileSize(i)
                if (ext in VIDEO_EXT && size > bestSize) { best = i; bestSize = size }
            }
            if (best < 0) {
                failReason = "这个种子里没有可播放的视频文件（共 ${files.numFiles()} 个文件）"
                return false
            }
            fileIndex = best
            fileOffset = files.fileOffset(best)
            pieceLength = tinfo.pieceLength()
            videoSize = bestSize
            videoFile = File(saveDir, files.filePath(best) ?: "video")

            // 只下这个文件
            val prios = Array(files.numFiles()) { i -> if (i == best) Priority.SEVEN else Priority.IGNORE }
            h.prioritizeFiles(prios)

            // 顺序下载 + 开头分片抢时间
            try { h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) } catch (t: Throwable) {
                Log.w(TAG, "sequential flag failed: ${t.message}")
            }
            if (pieceLength > 0) {
                val first = (fileOffset / pieceLength).toInt()
                val total = tinfo.numPieces()
                val last = minOf(first + HEAD_BURST_PIECES, total - 1)
                for (p in first..last) {
                    try { h.setPieceDeadline(p, 1500) } catch (t: Throwable) { }
                }
            }

            ready = true
            Log.i(TAG, "ready: ${videoFile?.absolutePath} size=$videoSize pieces=$pieceLength")
            return true
        } catch (t: Throwable) {
            failReason = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "start failed", t)
            return false
        }
    }

    /** 文件内偏移 → 该偏移所在分片的序号（全局分片编号） */
    fun pieceForFileOffset(offsetInFile: Long): Int {
        if (pieceLength <= 0) return 0
        return ((fileOffset + offsetInFile) / pieceLength).toInt()
    }

    /**
     * 播放器请求某段数据时，把这一段（+ 前方窗口）的分片提升为「尽快要」。
     *
     * 为什么必须有：MP4 的 moov 常常在文件**末尾**，ExoPlayer 一上来就会发
     * `Range: bytes=<末尾>-` 的探测请求。而顺序下载模式下末尾分片排在最后，
     * 不主动提优先级就会一直等到超时 → 播放器报错。
     */
    fun prioritizeWindow(offsetInFile: Long, length: Long = 0) {
        val h = handle ?: return
        if (pieceLength <= 0) return
        val first = pieceForFileOffset(offsetInFile)
        val lastByLen = if (length > 0) pieceForFileOffset(offsetInFile + length) else first
        val windowStart = first
        if (windowStart == lastPrioritizedStart) return
        lastPrioritizedStart = windowStart
        try {
            h.clearPieceDeadlines()
            val total = try { h.torrentFile()?.numPieces() ?: 0 } catch (t: Throwable) { 0 }
            val end = if (total > 0) minOf(lastByLen + STREAM_WINDOW_PIECES, total - 1)
                      else lastByLen + STREAM_WINDOW_PIECES
            for (p in first..end) {
                try { h.setPieceDeadline(p, 0) } catch (t: Throwable) { }
            }
            Log.i(TAG, "prioritize window pieces $first..$end (fileOffset=$fileOffset)")
        } catch (t: Throwable) {
            Log.w(TAG, "prioritizeWindow failed: ${t.message}")
        }
    }

    fun hasPiece(piece: Int): Boolean = try {
        handle?.havePiece(piece) ?: false
    } catch (t: Throwable) {
        false
    }

    fun progress(): TorrentProgress {
        val h = handle ?: return TorrentProgress(0, videoSize, 0, false)
        return try {
            val st = h.status()
            TorrentProgress(
                downloaded = st.totalDone(),
                total = st.total(),
                rate = session?.downloadRate() ?: 0,
                finished = st.isFinished || st.isSeeding,
            )
        } catch (t: Throwable) {
            TorrentProgress(0, videoSize, 0, false)
        }
    }

    /** 临时暂停/恢复（用户切后台时省电） */
    fun setActive(active: Boolean) {
        try {
            if (active) handle?.resume() else handle?.pause()
        } catch (t: Throwable) { }
    }

    fun stop(deleteFiles: Boolean = true) {
        try { handle?.pause() } catch (t: Throwable) { }
        try { session?.stop() } catch (t: Throwable) { }
        handle = null
        session = null
        ready = false
        if (deleteFiles) {
            try { saveDir.deleteRecursively() } catch (t: Throwable) { }
            saveDir.mkdirs()
        }
    }

    data class TorrentProgress(
        val downloaded: Long,
        val total: Long,
        val rate: Long,
        val finished: Boolean,
    )

    // ---- infohash helpers ----

    /**
     * magnet 里如果没带 tracker（或只带 udp:// 的），补上可用的 HTTP tracker。
     * 本网络 DHT(UDP) 被封，元数据只能靠 HTTP tracker 拿到 peer。
     */
    private fun withFallbackTrackers(magnet: String): String {
        val fallbacks = listOf(
            "http://tracker.opentrackr.org:1337/announce",
            "http://open.tracker.cl:1337/announce",
            "http://nyaa.tracker.wf:7777/announce",
        )
        val existingHttp = Regex("[?&]tr=(http[^&]+)").findAll(magnet).count()
        Log.i(TAG, "magnet has $existingHttp http trackers")
        if (existingHttp > 0) return magnet
        val sb = StringBuilder(magnet)
        for (t in fallbacks) {
            sb.append("&tr=").append(java.net.URLEncoder.encode(t, "UTF-8"))
        }
        return sb.toString()
    }

    private fun infoHash(magnet: String): String? {
        val i = magnet.indexOf("btih:")
        if (i < 0) return null
        val s = i + 5
        var e = s
        while (e < magnet.length && magnet[e].isLetterOrDigit()) e++
        if (e == s) return null
        val v = magnet.substring(s, e)
        return when {
            v.length == 40 -> v.lowercase()
            v.length == 32 -> base32ToHex(v)
            else -> null
        }
    }

    private fun base32ToHex(b32: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val up = b32.uppercase()
        val out = ByteArray(20)
        var buffer = 0L
        var bits = 0
        var idx = 0
        for (c in up) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[idx++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        if (idx != 20) return null
        return out.joinToString("") { String.format("%02x", it) }
    }

    private fun sha1FromHex(hex: String): Sha1Hash {
        val bytes = ByteArray(20) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Sha1Hash(bytes)
    }
}
