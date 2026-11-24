package com.aifindres.lite.play

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.aifindres.lite.R
import java.util.Locale

/**
 * 磁力视频「边下边播」播放页。
 * 流程：BT 引擎拉元数据 → 本地 HTTP 服务暴露视频 → ExoPlayer 播放本地 URL。
 *
 * 控制条为自绘（关闭 media3 内置控制器）：所有按钮固定在屏幕底部，不遮挡画面中心；
 * 全屏按钮切换 横屏 + 沉浸式（隐藏状态栏/导航栏）。
 */
class PlayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAGNET = "extra_magnet"
        const val EXTRA_TITLE = "extra_title"
        private const val TAG = "PlayActivity"
        private const val UI_TICK_MS = 500L
        private const val SEEK_MAX = 1000
        private const val SEEK_STEP_MS = 10_000L
        private const val HIDE_CONTROLS_DELAY_MS = 4000L
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var session: TorrentStreamSession? = null
    private var server: LocalTorrentServer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var tvTip: TextView? = null
    private var tvStats: TextView? = null
    private var progressBar: ProgressBar? = null
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var seekBar: SeekBar? = null
    private var tvCurTime: TextView? = null
    private var tvTotalTime: TextView? = null
    private var btnPlayPause: ImageButton? = null
    private var btnFullscreen: ImageButton? = null

    private var isFullscreen = false
    private var dragging = false
    private var playbackReady = false
    private var controlsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        tvTip = findViewById(R.id.tvPlayTip)
        tvStats = findViewById(R.id.tvPlayStats)
        progressBar = findViewById(R.id.playProgress)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        seekBar = findViewById(R.id.seekBar)
        tvCurTime = findViewById(R.id.tvCurTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnFullscreen = findViewById(R.id.btnFullscreen)

        setupControls()

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

    // ---------------------------------------------------------------- 自绘控制条

    private fun setupControls() {
        playerView?.setOnClickListener { toggleControls() }
        findViewById<View>(R.id.rootPlay).setOnClickListener { toggleControls() }

        btnPlayPause?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
            showControls()
        }
        findViewById<ImageButton>(R.id.btnReplay).setOnClickListener {
            seekBy(-SEEK_STEP_MS)
            showControls()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            seekBy(SEEK_STEP_MS)
            showControls()
        }
        btnFullscreen?.setOnClickListener {
            toggleFullscreen()
            showControls()
        }

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = player?.duration ?: 0L
                if (dur > 0) tvCurTime?.text = formatTime(dur * progress / SEEK_MAX)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                val dur = player?.duration ?: 0L
                val target = dur * (sb?.progress ?: 0) / SEEK_MAX
                if (dur > 0) player?.seekTo(target)
                showControls()
            }
        })

        showControls()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, dur)
        p.seekTo(target)
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        topBar?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        // 播放中才自动隐藏；未就绪时保持可见
        if (player?.isPlaying == true) {
            handler.postDelayed(hideRunnable, HIDE_CONTROLS_DELAY_MS)
        }
    }

    private fun hideControls() {
        controlsVisible = false
        topBar?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
        handler.removeCallbacks(hideRunnable)
    }

    private val hideRunnable = Runnable { if (player?.isPlaying == true) hideControls() }

    // ---------------------------------------------------------------- 全屏

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        btnFullscreen?.contentDescription = "退出全屏"
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        btnFullscreen?.contentDescription = "全屏"
    }

    // ---------------------------------------------------------------- BT + 播放

    private fun startTorrent(magnet: String) {
        // ★ 先清场：切播放页/重复点预览时，旧会话仍在监听另一个端口、且与新的共用缓存目录，
        //   旧页 stop(true) 会把新页正在写的文件删掉（实测 10:06 / 10:07 两个 session 并存）。
        try { player?.release() } catch (t: Throwable) { }
        player = null
        playerView?.player = null
        try { server?.stop() } catch (t: Throwable) { }
        server = null
        try { session?.stop(true) } catch (t: Throwable) { }
        session = null
        playbackReady = false

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
            // ★ 必须显式调大读超时：media3 的 DefaultHttpDataSource 默认读超时只有 8 秒，
            //   而本地服务可能为等一个分片阻塞到 45 秒（PIECE_WAIT_MS）。默认值会导致
            //   SocketTimeoutException → Source error（表现为「下载在涨、画面全黑」）。
            val httpFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(90_000)
            val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
            val exo = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
                .build()
            player = exo
            playerView?.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    Log.i(
                        TAG,
                        "playback state=$state pos=${exo.currentPosition} buffered=${exo.bufferedPosition} " +
                            "isPlaying=${exo.isPlaying}"
                    )
                    if (state == Player.STATE_READY) {
                        playbackReady = true
                        progressBar?.visibility = View.GONE
                        val dur = exo.duration
                        if (dur > 0) tvTotalTime?.text = formatTime(dur)
                        showControls()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i(
                        TAG,
                        "playback isPlaying=$isPlaying pos=${exo.currentPosition} " +
                            "buffered=${exo.bufferedPosition} state=${exo.playbackState}"
                    )
                    btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    btnPlayPause?.contentDescription = if (isPlaying) "暂停" else "播放"
                    if (isPlaying) showControls() else {
                        handler.removeCallbacks(hideRunnable)
                        controlsVisible = true
                        topBar?.visibility = View.VISIBLE
                        bottomBar?.visibility = View.VISIBLE
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
            updateDownloadStats()
            updateSeekBar()
            handler.postDelayed(this, UI_TICK_MS)
        }
    }

    private fun updateDownloadStats() {
        val sess = session ?: return
        val p = try { sess.progress() } catch (t: Throwable) { null } ?: return
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
        if (!playbackReady && progressBar != null && progressBar!!.visibility != View.GONE) {
            progressBar?.progress = pct.toInt()
        }
        // ★ 健康度预警：只在「还没起播」或「起播后卡在缓冲」时把原因摆到标题上。
        //   死种（tracker 说 0 个做种、一个 peer 都连不上）以前只能干等 45 秒再看到
        //   「播放出错」，用户根本不知道是种的问题；现在直接把判断结论说出来。
        val conn = try { sess.connected() } catch (t: Throwable) { intArrayOf(0, 0) }
        val hint = try { sess.healthHint(conn[0], conn[1]) } catch (t: Throwable) { null }
        if (hint != null) {
            val stalled = !playbackReady || player?.playbackState == Player.STATE_BUFFERING
            if (stalled) setTip(hint)
        } else if (playbackReady) {
            setTip(sess.torrentName)
        }
    }

    private fun updateSeekBar() {
        val p = player ?: return
        if (dragging) return
        val dur = p.duration
        if (dur <= 0) return
        tvTotalTime?.text = formatTime(dur)
        val pos = p.currentPosition.coerceIn(0L, dur)
        tvCurTime?.text = formatTime(pos)
        seekBar?.progress = (pos * SEEK_MAX / dur).toInt().coerceIn(0, SEEK_MAX)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
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
        showControls()
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
