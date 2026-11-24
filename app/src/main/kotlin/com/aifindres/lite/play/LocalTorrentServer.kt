package com.aifindres.lite.play

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简本地 HTTP 流服务：把「正在下载的视频文件」以 HTTP 暴露给 ExoPlayer。
 *
 * 为什么要这一层？磁力下载的文件是边下边长的，直接 file:// 读会立刻 EOF。
 * 这里在读到尚未下载的分片时阻塞等待（最多等 pieceTimeoutMs），
 * 并支持 Range: bytes=a-b，ExoPlayer 拖进度条才能工作。
 */
class LocalTorrentServer(private val session: TorrentStreamSession) {
    companion object {
        private const val TAG = "TorrentHttp"
        private const val PIECE_WAIT_MS = 45_000L
        private const val CHUNK = 64 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile var port: Int = 0
        private set

    /** 启动服务，返回可播放的 URL；失败返回 null */
    fun start(): String? {
        val file = session.videoFile ?: return null
        // 视频文件可能在 libtorrent 真正写入前还不存在，先建出来，避免 RandomAccessFile 抛 FileNotFound
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pre-create file failed: ${t.message}")
        }
        val ss = try {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed: ${t.message}")
            return null
        }
        serverSocket = ss
        port = ss.localPort
        running.set(true)
        thread = Thread({
            while (running.get()) {
                try {
                    val client = ss.accept()
                    client.tcpNoDelay = true
                    Thread({ handle(client, file) }, "http-conn").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: SocketException) {
                    break // 服务关闭
                } catch (t: Throwable) {
                    Log.w(TAG, "accept err: ${t.message}")
                }
            }
        }, "http-accept").apply {
            isDaemon = true
            start()
        }
        val url = "http://127.0.0.1:$port/stream"
        Log.i(TAG, "serving $url -> ${file.absolutePath} size=${session.videoSize}")
        return url
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (t: Throwable) { }
        serverSocket = null
        thread = null
    }

    private fun handle(client: Socket, file: File) {
        try {
            val input = client.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            val method = requestLine.substringBefore(' ')
            val isHead = method.equals("HEAD", ignoreCase = true)
            if (!method.equals("GET", ignoreCase = true) && !isHead) {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                client.close()
                return
            }
            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
            }

            val totalSize = session.videoSize
            if (totalSize <= 0) {
                client.getOutputStream().write("HTTP/1.1 503 Not Ready\r\n\r\n".toByteArray())
                client.close()
                return
            }
            var start = 0L
            var end = totalSize - 1
            var partial = false
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").split(',')[0].trim()
                val parts = spec.split('-')
                val a = parts.getOrNull(0)?.trim()?.toLongOrNull()
                val b = parts.getOrNull(1)?.trim()?.toLongOrNull()
                if (a != null) {
                    start = a
                    if (b != null) end = b
                    partial = true
                } else if (b != null) {
                    // bytes=-N 尾部请求（MP4 的 moov 常在文件末尾，ExoPlayer 会这么问）
                    start = maxOf(0, totalSize - b)
                    partial = true
                }
            }
            if (start >= totalSize) {
                client.getOutputStream().write(
                    "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalSize\r\n\r\n".toByteArray()
                )
                client.close()
                return
            }
            end = minOf(end, totalSize - 1)
            val length = (end - start + 1).coerceAtLeast(0)

            // 告诉 BT 引擎：播放器现在要这一段，赶紧下（尤其是末尾 moov / 拖动后的位置）
            session.prioritizeWindow(start, length)

            val header = StringBuilder()
            header.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            header.append("Content-Type: ").append(mimeOf(file.name)).append("\r\n")
            header.append("Accept-Ranges: bytes\r\n")
            header.append("Content-Length: $length\r\n")
            if (partial) header.append("Content-Range: bytes $start-$end/$totalSize\r\n")
            header.append("Connection: close\r\n\r\n")
            val rawOut = client.getOutputStream()
            rawOut.write(header.toString().toByteArray())
            rawOut.flush()
            if (isHead) {
                client.close()
                return
            }
            val out: OutputStream = BufferedOutputStream(rawOut, CHUNK)

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                var remaining = length
                val buf = ByteArray(CHUNK)
                var posInFile = start
                var idleReads = 0
                while (remaining > 0) {
                    // 等到这一块数据真的在磁盘上
                    if (!waitForPiece(posInFile)) {
                        Log.w(TAG, "piece wait timeout at $posInFile (remaining=$remaining), aborting stream")
                        break
                    }
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = raf.read(buf, 0, want)
                    if (n <= 0) {
                        // 文件还没长到这里（分片刚写入但长度未刷盘），短暂退避后重试几次
                        if (idleReads++ < 40) {
                            Thread.sleep(100)
                            continue
                        }
                        Log.w(TAG, "read EOF at $posInFile (remaining=$remaining), aborting")
                        break
                    }
                    idleReads = 0
                    out.write(buf, 0, n)
                    out.flush()
                    remaining -= n
                    posInFile += n
                }
            }
            out.flush()
            client.close()
        } catch (t: Throwable) {
            Log.w(TAG, "conn err: ${t.javaClass.simpleName} ${t.message}")
            try { client.close() } catch (e: Throwable) { }
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        else -> "application/octet-stream"
    }

    /**
     * 阻塞直到包含该偏移的分片已下载。
     *
     * 「边等边加急」：等待期间每 ~900ms 调 [TorrentStreamSession.urgentPiece] 把这一片刷成唯一 TOP，
     * 否则 libtorrent 的 picker 在同档里随机挑片，读者要的那片可能 40 秒都不来。
     * 每 ~3 秒打一行 [TorrentStreamSession.debugState] 诊断。
     *
     * ⚠️ PIECE_WAIT_MS 必须小于播放器读超时（45s < 90s），否则服务端还在等片、播放器已判定超时。
     */
    private fun waitForPiece(offsetInFile: Long): Boolean {
        val deadline = System.currentTimeMillis() + PIECE_WAIT_MS
        val piece = session.pieceForFileOffset(offsetInFile)
        var lastUrgent = 0L
        var lastDbg = 0L
        while (System.currentTimeMillis() < deadline) {
            if (session.hasPiece(piece)) return true
            // 已下完但分片号算错时也放行（防御）
            val p = session.progress()
            if (p.finished) return true
            val now = System.currentTimeMillis()
            if (now - lastUrgent > 900) {
                lastUrgent = now
                try { session.urgentPiece(piece) } catch (t: Throwable) { }
            }
            if (now - lastDbg > 3000) {
                lastDbg = now
                try { Log.i(TAG, session.debugState(piece, " wait")) } catch (t: Throwable) { }
            }
            try { Thread.sleep(120) } catch (e: InterruptedException) { return false }
        }
        Log.w(TAG, "waitForPiece gave up: piece=$piece offset=$offsetInFile")
        return false
    }
}
