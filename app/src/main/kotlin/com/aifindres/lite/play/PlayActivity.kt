package com.aifindres.lite.play

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aifindres.lite.R
import java.util.Locale

/**
 * 磁力视频「边下边播」播放页。
 * 流程：BT 引擎拉元数据 → 本地 HTTP 服务暴露视频 → ExoPlayer 播放本地 URL。
 */
class PlayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAGNET = "extra_magnet"
        const val EXTRA_TITLE = "extra_title"
        private const val TAG = "PlayActivity"
        private const val UI_TICK_MS = 500L
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var session: TorrentStreamSession? = null
    private var server: LocalTorrentServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private var tvTip: TextView? = null
    private var tvStats: TextView? = null
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play)
        playerView = findViewById(R.id.playerView)
        tvTip = findViewById(R.id.tvPlayTip)
        tvStats = findViewById(R.id.tvPlayStats)
        progressBar = findViewById(R.id.playProgress)

        val magnet = intent.getStringExtra(EXTRA_MAGNET)
        val title = intent.getStringExtra(EXTRA_TITLE)
        if (magnet.isNullOrBlank()) {
            Toast.makeText(this, "缺少磁力链接", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!title.isNullOrBlank()) {
            tvTip?.text = title
        }
        startTorrent(magnet)
    }

    private fun startTorrent(magnet: String) {
        val sess = TorrentStreamSession(this, magnet)
        session = sess
        setTip("正在连接 BT 网络…")
        Thread({
            val ok = sess.start()
            if (!ok) {
                runOnUiThread {
                    val reason = sess.failReason ?: "未知错误"
                    Toast.makeText(this, "预览失败：$reason", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "torrent start failed: $reason")
                    setTip("预览失败：$reason")
                    progressBar?.visibility = View.GONE
                }
                sess.stop(true)
                return@Thread
            }
            val srv = LocalTorrentServer(sess)
            val url = srv.start()
            if (url == null) {
                runOnUiThread {
                    Toast.makeText(this, "本地流服务启动失败", Toast.LENGTH_LONG).show()
                    setTip("本地流服务启动失败")
                    progressBar?.visibility = View.GONE
                }
                sess.stop(true)
                return@Thread
            }
            server = srv
            runOnUiThread {
                setTip(sess.torrentName)
                startPlayback(url)
            }
        }, "torrent-start").apply {
            isDaemon = true
            start()
        }
        handler.post(tickRunnable)
    }

    private fun startPlayback(url: String) {
        if (isFinishing || isDestroyed) return
        try {
            val exo = ExoPlayer.Builder(this).build()
            player = exo
            playerView?.player = exo
            playerView?.useController = true
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        progressBar?.visibility = View.GONE
                        started = true
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "player error", error)
                    setTip("播放出错：${error.errorCodeName}")
                    progressBar?.visibility = View.GONE
                }
            })
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            exo.playWhenReady = true
            Log.i(TAG, "playback url=$url")
        } catch (t: Throwable) {
            Log.e(TAG, "player init failed", t)
            setTip("播放器初始化失败：${t.message}")
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val sess = session
            if (sess != null) {
                val p = try { sess.progress() } catch (t: Throwable) { null }
                if (p != null) {
                    val pct = if (p.total > 0) (p.downloaded * 100 / p.total) else 0
                    val stats = String.format(
                        Locale.US,
                        "%d%% · %s / %s · %s/s",
                        pct,
                        human(p.downloaded),
                        human(p.total),
                        human(p.rate),
                    )
                    tvStats?.text = stats
                    if (!started && progressBar != null && progressBar!!.visibility != View.GONE) {
                        progressBar?.progress = pct.toInt()
                    }
                }
            }
            handler.postDelayed(this, UI_TICK_MS)
        }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun setTip(s: String) {
        tvTip?.text = s
    }

    override fun onStop() {
        super.onStop()
        // 切后台省电：暂停 BT，回来再继续
        player?.pause()
        session?.setActive(false)
    }

    override fun onStart() {
        super.onStart()
        session?.setActive(true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (t: Throwable) { }
        player = null
        try { server?.stop() } catch (t: Throwable) { }
        server = null
        Thread({ try { session?.stop(true) } catch (t: Throwable) { } }, "torrent-stop").apply {
            isDaemon = true
            start()
        }
        session = null
        super.onDestroy()
    }
}
