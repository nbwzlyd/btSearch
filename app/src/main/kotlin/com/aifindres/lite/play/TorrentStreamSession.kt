package com.aifindres.lite.play

import android.content.Context
import android.util.Log
import com.aifindres.lite.addon.TrackerProbe
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File

/**
 * 磁力链接「边下边播」会话：libtorrent 拉元数据 → 只下最大的视频文件 → 按读者位置分级提权。
 * 播放由 [LocalTorrentServer] 通过本地 HTTP 暴露给 ExoPlayer（支持 Range / 等待分片）。
 *
 * 优先级模型（本类核心，详见文档第 4 节）：
 *  - 视频文件其它分片  DEFAULT(4)；非视频文件 IGNORE(0)
 *  - 文件第一片        TOP_PRIORITY(7)（唯一 TOP，必须有区分度）
 *  - 头 3 片 / 尾 8 片 SIX(6)（尾部是 MKV Cues / MP4 moov，播放器打开就要）
 *  - 读者卡住那一片    TOP_PRIORITY(7)，其后 3 片 SIX(6)、再后 28 片 FIVE(5)
 *  ⚠️ 不要用 setPieceDeadline 做流式优先级（time-critical 语义，过期即降级 → 卡死）；
 *  ⚠️ 不要用 SEQUENTIAL_DOWNLOAD flag（与片优先级混用实测乱序）。
 */
class TorrentStreamSession(
    private val context: Context,
    private val magnet: String,
) {
    companion object {
        private const val TAG = "TorrentStream"
        private const val METADATA_TIMEOUT_MS = 60_000L
        /** 文件头（EBML/moov）最先要的片数 */
        private const val HEAD_CRITICAL_PIECES = 3
        /** 覆盖 MKV Cues / MP4 moov：8 片 × 8MiB = 64MiB */
        private const val TAIL_BURST_PIECES = 8
        /** 读者片 + 后 31 片（256MiB ≈ 1080p Remux 的 ~60 秒） */
        private const val URGENT_WINDOW_PIECES = 32
        private val VIDEO_EXT = setOf(
            // ⚠️ 不要放 iso：镜像不是视频，ExoPlayer 会报 UnrecognizedInputFormatException
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg", "rmvb", "3gp"
        )
        private val FALLBACK_TRACKERS = listOf(
            "http://tracker.opentrackr.org:1337/announce",
            "http://open.tracker.cl:1337/announce",
            "http://nyaa.tracker.wf:7777/announce",
        )

        /** magnet 里的 info hash（40 hex 或 32 base32→hex），非法返回 null */
        fun infoHash(magnet: String): String? {
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
    }

    /** 所有种子的缓存根目录 */
    private val baseCacheDir = File(context.getExternalFilesDir(null), "torrent_cache")

    /** 按 info hash 隔离的保存目录（多会话并存时不会互相删文件） */
    val saveDir: File = File(
        baseCacheDir,
        infoHash(magnet)?.take(16) ?: ("t" + System.currentTimeMillis().toString(16))
    ).apply { mkdirs() }

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
    /** 视频文件自己的首/末分片（多文件种子时与整块种子的首末不同） */
    @Volatile private var fileFirst: Int = 0
    @Volatile private var fileLast: Int = 0
    @Volatile private var totalPieces: Int = 0
    /** 上一次已提过优先级的窗口起点，避免同一位置重复设置 */
    @Volatile private var lastPrioritizedStart: Int = -1
    /** 写日志限流（避免每 900ms 刷屏） */
    @Volatile private var lastUrgentLog: Long = 0

    /** 启动引擎并等元数据（阻塞，必须子线程调用） */
    fun start(): Boolean {
        try {
            // ★ 起播前清残留：进程被杀后遗留的数据会让 libtorrent 进入 CHECKING_FILES
            //   （43GB 种子实测 90 秒+），而 libtorrent4j 2.1 没有可用的 resume data 读入 API
            //   （saveState() 恢复后 torrents=0）。所以直接清掉，牺牲已下载数据换起播速度。
            clearStaleCache()

            val settings = SettingsPack()
            // ★★★ 真机定案（K40，逐档对照得出的唯一正确配置）：
            //  0) 完全用默认 settings                        ✅ 5 秒拿到元数据
            //  1) 只设下面这组（announce/tracker + 关 uTP）  ✅ 正常（当前生产值）
            //  2) 另加 listen_interfaces=具体 WiFi IP        ✅ 正常（但没必要）
            //  3) 另加 outgoing_interfaces=具体 WiFi IP      ❌ peers 恒 0，永远拿不到元数据
            //  ⇒ 绝不能设 outgoing_interfaces：Android 上写死本机 IP 后 libtorrent 的出站
            //    连接全部无效（/proc/net/tcp 里连 SYN_SENT 都没有），且不报错，极难排查。
            //  ⇒ listen_interfaces 保持默认双栈通配 0.0.0.0:6881,[::]:6881 最稳。
            try {
                // 每个 tracker 都要 announce（元数据阶段只有靠 tracker 找 peer）
                settings.setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true
                )
                settings.setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true
                )
                settings.setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.prefer_udp_trackers.swigValue(), false
                )
                // 国内网络普遍封非 53 端口 UDP（本项目早期排查 DHT 时实测 UDP 6881 全 timeout），
                // 而 libtorrent 默认 enable_outgoing_utp=true 会优先走 uTP(UDP) → 连不上 peer。
                // 关掉 uTP 强制 TCP。
                settings.setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), false
                )
                settings.setBoolean(
                    org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), false
                )
            } catch (t: Throwable) {
                Log.w(TAG, "apply settings failed", t)
            }
            val sm = SessionManager(false)
            // 注意：AlertListener 的 alert() 跑在 native 线程，跨线程调 swig 对象可能直接 SIGABRT，
            // 这里不做 alert 诊断，改用轮询 + debugState。
            sm.start(SessionParams(settings))
            session = sm
            Log.i(TAG, "session started, listenEndpoints=${sm.listenEndpoints()}, ifaces=${sm.listenInterfaces()}")

            // ★ TrackerProbe 改异步：原来同步串行打 6 个已死 tracker × 8s 超时，
            //   起播前白等 8.1 秒（实测 10:26:07.334 → 10:26:15.446）。
            val hashForPeers = infoHash(magnet)
            val probeDone = java.util.concurrent.atomic.AtomicBoolean(false)
            var probePeers: List<TrackerProbe.Peer> = emptyList()
            val injectedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val probeThread = if (hashForPeers != null) {
                Thread({
                    try {
                        probePeers = TrackerProbe.fetchPeers(hashForPeers, magnet, 8000, 40)
                    } catch (t: Throwable) {
                        Log.w(TAG, "fetchPeers failed: ${t.message}")
                    }
                    probeDone.set(true)
                    Log.i(TAG, "fetched ${probePeers.size} peers from http trackers (async)")
                }, "tracker-probe").apply {
                    isDaemon = true
                    start()
                }
            } else null

            // 关键做法（桌面 T8 实测有效）：libtorrent 自己 announce 在很多国内网络里出不去
            // （peers 恒 0、元数据永远拿不到），但我们用 OkHttp 手写 announce 是通的（徽章功能就靠它）。
            // 拿到 handle 后再用探测结果逐个 connect_peer 主动连（set_peers 在真机上实测无效）。
            sm.download(
                withFallbackTrackers(magnet),
                saveDir,
                org.libtorrent4j.swig.torrent_flags_t()
            )

            val hash = hashForPeers ?: run {
                failReason = "磁力链接里没有可用的 info hash"
                return false
            }
            val sha1 = try {
                Sha1Hash.parseHex(hash)
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
                        // AUTO_MANAGED 会让队列管理器接管 torrent，真机上实测会把 resume() 的效果
                        // 抵消掉（flags 停在 40），结果 libtorrent 连一个 SYN 都不发。
                        try {
                            found.unsetFlags(org.libtorrent4j.TorrentFlags.AUTO_MANAGED)
                        } catch (t: Throwable) {
                            Log.w(TAG, "unset AUTO_MANAGED failed: ${t.message}")
                        }
                        found.resume()
                        resumed = true
                        Log.i(TAG, "torrent resumed, flags=${found.flags?.to_int()}")
                        // 注意：**不要**在这里 join 探测线程再注入 peer。
                        // 老磁力自带十几个死 tracker（ktxp 超时 8s、3322.org 域名不解析、prq.to 403），
                        // 探测通常要 8 秒才回来，而这里 join 只有 1.5s → 探测结果永远迟到、
                        // peer 一个都注不进去（实测 fetched 24 peers 但 connect_peer 从未执行）。
                        // 改为在下面的轮询循环里「探测完成即注入」，见 maybeInjectPeers()。
                        try { found.forceReannounce() } catch (t: Throwable) { }
                    } catch (t: Throwable) {
                        Log.w(TAG, "resume failed", t)
                    }
                }
                // 探测一完成就把 peer 主动连上（只做一次），不与 resume 的时机耦合。
                if (resumed && injectedCount.get() == 0 && probeDone.get() && probePeers.isNotEmpty() && found != null && found.isValid) {
                    var ok = 0
                    for (p in probePeers) {
                        try {
                            found.swig().connect_peer(
                                org.libtorrent4j.TcpEndpoint(p.ip, p.port).swig()
                            )
                            ok++
                        } catch (t: Throwable) { }
                    }
                    injectedCount.set(ok)
                    Log.i(TAG, "connect_peer issued for $ok/${probePeers.size} peers (deferred)")
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
                                append(" err=").append(st.errorCode()?.value)
                            }
                            val trs = try { found.trackers() } catch (e: Throwable) { null }
                            if (trs != null) {
                                append(" nTrackers=").append(trs.size)
                                if (tick == 0) {
                                    for (t in trs) append(" [").append(t.url()).append("]")
                                    try { found.forceReannounce() } catch (e: Throwable) { }
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
                    if (ec != null && ec.isError) {
                        failReason = "种子加载失败：${ec.message}"
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
            totalPieces = tinfo.numPieces()
            fileFirst = if (pieceLength > 0) (fileOffset / pieceLength).toInt() else 0
            fileLast = if (pieceLength > 0) {
                minOf(totalPieces - 1, ((fileOffset + videoSize - 1) / pieceLength).toInt())
            } else 0

            // 只下这个文件；视频文件本身用 DEFAULT —— 若整文件设 TOP，全 5000+ 片同档，
            // 事后对某几片再设 TOP 就没有区分度，picker 退化成随机挑片（实测第 0 片 40 秒都不来）。
            val prios = Array(files.numFiles()) { i -> if (i == best) Priority.DEFAULT else Priority.IGNORE }
            h.prioritizeFiles(prios)

            // 起播头尾提权（不再设 SEQUENTIAL_DOWNLOAD：实测与优先级混用会乱序）
            if (pieceLength > 0 && fileLast >= fileFirst) {
                setPriority(fileFirst, fileFirst, Priority.TOP_PRIORITY)
                setPriority(fileFirst + 1, minOf(fileFirst + HEAD_CRITICAL_PIECES - 1, fileLast), Priority.SIX)
                val tailFirst = maxOf(fileFirst, fileLast - TAIL_BURST_PIECES + 1)
                setPriority(tailFirst, fileLast, Priority.SIX)
                Log.i(
                    TAG,
                    "priority burst: head=TOP($fileFirst) head2=SIX tail=SIX($tailFirst..$fileLast) of $totalPieces"
                )
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
     * 播放器请求某段数据时，把它（以及尾部窗口）提权。
     *
     * 为什么必须有：MKV 的 Cues、MP4 的 moov 常在文件**末尾**，ExoPlayer 一打开就发
     * `Range: bytes=<末尾>-` 的探测请求；不主动提优先级就会一直等到超时 → 播放器报错。
     */
    fun prioritizeWindow(offsetInFile: Long, length: Long = 0) {
        if (pieceLength <= 0 || handle == null) return
        val first = pieceForFileOffset(offsetInFile)
        if (first == lastPrioritizedStart) return
        lastPrioritizedStart = first
        urgentPiece(first)
        // 请求落到尾部附近时，把尾部 Cues/moov 再提一次（可能已被后续请求覆盖）
        val tailFirst = maxOf(fileFirst, fileLast - TAIL_BURST_PIECES + 1)
        if (first >= tailFirst - 8) setPriority(tailFirst, fileLast, Priority.SIX)
        Log.i(TAG, "prioritize piece $first (ref=${if (length > 0) "len" else "open"}) fileOffset=$fileOffset")
    }

    /**
     * 「边等边加急」：读者正阻塞等这一片，把它设为**唯一 TOP**，其后 3 片 SIX、再后 28 片 FIVE。
     * 由 [LocalTorrentServer.waitForPiece] 每 ~900ms 调用。
     */
    fun urgentPiece(piece: Int) {
        if (pieceLength <= 0 || handle == null) return
        val base = piece.coerceIn(fileFirst, maxOf(fileFirst, fileLast))
        setPriority(base, base, Priority.TOP_PRIORITY)
        setPriority(base + 1, base + 3, Priority.SIX)
        setPriority(base + 4, base + URGENT_WINDOW_PIECES - 1, Priority.FIVE)
    }

    /** 只改指定区间的片优先级；已下载的片跳过；失败日志 2 秒限流。 */
    private fun setPriority(from: Int, to: Int, prio: Priority) {
        val h = handle ?: return
        if (pieceLength <= 0 || to < from) return
        val a = from.coerceIn(0, maxOf(0, totalPieces - 1))
        val b = to.coerceIn(a, maxOf(0, totalPieces - 1))
        var n = 0
        for (p in a..b) {
            try {
                if (h.havePiece(p)) continue
                h.piecePriority(p, prio)
                n++
            } catch (t: Throwable) {
                val now = System.currentTimeMillis()
                if (now - lastUrgentLog > 2000) {
                    lastUrgentLog = now
                    Log.w(TAG, "piecePriority $p=$prio failed: ${t.message}")
                }
            }
        }
        if (n > 0) Log.i(TAG, "priority $prio $a..$b ($n pieces)")
    }

    /**
     * 一行诊断（await / have0..15 / haveAwait / nHave / 扫描 have 列表 / peers / seeds / rate / done / state）。
     * ⚠️ 每次做 O(n) 全分片扫描（5000+ 片），仅供调试，正式版应移除或降频。
     */
    fun debugState(awaiting: Int, tag: String): String {
        val h = handle ?: return "dbg$tag no-handle"
        return try {
            val st = h.status()
            val sb = StringBuilder("dbg$tag await=").append(awaiting)
            sb.append(" have0..15=")
            for (p in 0..15) sb.append(if (safeHave(h, p)) '1' else '0')
            sb.append(" haveAwait=").append(if (safeHave(h, awaiting)) '1' else '0')
            var nHave = 0
            val found = ArrayList<Int>()
            for (p in 0 until totalPieces) {
                if (safeHave(h, p)) {
                    nHave++
                    if (found.size < 20) found.add(p)
                }
            }
            sb.append(" nHave=").append(nHave).append("/").append(totalPieces)
            sb.append(" 扫描have=").append(found)
            sb.append(" peers=").append(st.numPeers())
            sb.append(" seeds=").append(st.numSeeds())
            sb.append(" rate=").append(humanRate(session?.downloadRate() ?: 0))
            sb.append(" done=").append(st.totalWantedDone())
            sb.append(" state=").append(st.state())
            sb.toString()
        } catch (t: Throwable) {
            "dbg$tag err=${t.message}"
        }
    }

    private fun safeHave(h: TorrentHandle, p: Int): Boolean = try {
        h.havePiece(p)
    } catch (t: Throwable) {
        false
    }

    private fun humanRate(bps: Long): String = when {
        bps >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1fMB/s", bps / 1024.0 / 1024)
        bps >= 1024 -> String.format(java.util.Locale.US, "%.0fKB/s", bps / 1024.0)
        else -> "${bps}B/s"
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
            // ★ libtorrent 2.x 的 TorrentStatus.total() 实测恒为 0（1.x 的旧语义已废弃），
            //   必须用 totalWanted()/totalWantedDone()；再兜底到视频文件自身大小。
            val wanted = st.totalWanted()
            val total = if (wanted > 0) wanted else videoSize
            val done = st.totalWantedDone().let { if (it > 0) it else st.totalDone() }
            TorrentProgress(
                downloaded = if (total > 0) minOf(done, total) else done,
                total = total,
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

    // ---- 缓存 ----

    /**
     * 清掉 torrent_cache 下的所有残留数据。
     *
     * 为什么必须：进程被杀后残留数据会让 libtorrent 重新校验全部已有分片
     * （`state=CHECKING_FILES`，43GB 种子实测 90 秒+，表现是「数据都在却一直转圈」）；
     * 而 libtorrent4j 2.1 **没有可用的 resume data 读入 API**
     * （`saveState()` 恢复后 torrents=0；`AddTorrentParams` 只能 writeResumeDataBuf 出去）。
     * 所以起播前直接删干净，牺牲已下载数据换起播速度。
     */
    private fun clearStaleCache() {
        try {
            val items = baseCacheDir.listFiles() ?: return
            if (items.isEmpty()) return
            var total = 0L
            var count = 0
            for (f in items) {
                total += sizeOf(f)
                count++
                try { f.deleteRecursively() } catch (t: Throwable) { }
            }
            baseCacheDir.mkdirs()
            saveDir.mkdirs()
            if (count > 0) {
                Log.i(TAG, "clearStaleCache: cleared $count item(s), ${total / 1024 / 1024}MB")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clearStaleCache failed: ${t.message}")
        }
    }

    private fun sizeOf(f: File): Long {
        if (f.isFile) return f.length()
        var sum = 0L
        f.listFiles()?.forEach { sum += sizeOf(it) }
        return sum
    }

    // ---- tracker ----

    /**
     * 补上可用的 HTTP tracker。
     *
     * ⚠️ 恒补而不是「已有 http tracker 就跳过」：实测有磁力自带 19 个 tracker 全是死链
     * （`nTrackers=19` 全死、`peers=0`），必须有兜底；同一 URL 去重避免重复 announce。
     * 另外这里顺手把 `&amp;` 还原成 `&`（HtmlParser 旧版本会污染 magnet）。
     */
    private fun withFallbackTrackers(magnet: String): String {
        val base = magnet.trim().replace("&amp;", "&").trimEnd('&')
        val existing = try {
            Regex("[?&]tr=([^&]+)").findAll(base)
                .mapNotNull {
                    try {
                        java.net.URLDecoder.decode(it.groupValues[1], "UTF-8").lowercase()
                    } catch (t: Throwable) { null }
                }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
        val sb = StringBuilder(base)
        var added = 0
        for (t in FALLBACK_TRACKERS) {
            if (existing.contains(t.lowercase())) continue
            sb.append("&tr=").append(java.net.URLEncoder.encode(t, "UTF-8"))
            added++
        }
        Log.i(TAG, "magnet has ${existing.size} http trackers, added $added fallback")
        return sb.toString()
    }
}
