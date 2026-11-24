package com.aifindres.lite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aifindres.lite.MainActivity
import com.aifindres.lite.R
import com.aifindres.lite.addon.DhtProbe
import com.aifindres.lite.addon.TrackerProbe
import com.aifindres.lite.addon.QualityTag
import com.aifindres.lite.data.MagnetItem
import com.aifindres.lite.data.Prefs
import com.aifindres.lite.data.Site
import com.aifindres.lite.data.SiteRepository
import com.aifindres.lite.net.SearchEngine
import com.aifindres.lite.play.PlayActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class MagnetFragment : Fragment(R.layout.fragment_magnet) {

    private var prefs: Prefs? = null
    private var adapter: MagnetAdapter? = null
    private val seen = HashSet<String>()
    private val probing = HashSet<String>()
    @Volatile private var scanning = false
    private var doneCount = 0
    private var totalCount = 0

    private var tvStatus: TextView? = null
    private var tvSites: TextView? = null
    private var btnSearch: MaterialButton? = null
    private var btnMore: MaterialButton? = null
    private var progress: LinearProgressIndicator? = null
    private var etKeyword: TextInputEditText? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvStatus = view.findViewById(R.id.tvStatus)
        tvSites = view.findViewById(R.id.tvSites)
        btnSearch = view.findViewById(R.id.btnSearch)
        btnMore = view.findViewById(R.id.btnMore)
        progress = view.findViewById(R.id.progress)
        etKeyword = view.findViewById(R.id.etKeyword)
        val rv = view.findViewById<RecyclerView>(R.id.rv)

        prefs = Prefs(requireContext())
        val ad = MagnetAdapter(
            click = { showDetail(it) },
            longClick = { item -> if (item.magnet.isNotEmpty()) copy(item.magnet, "磁力链接") },
        )
        adapter = ad
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = ad

        btnSearch?.setOnClickListener { doSearch(reset = true) }
        btnMore?.setOnClickListener { doSearch(reset = false) }
        btnMore?.isEnabled = false
        tvSites?.setOnClickListener { openSites() }
        etKeyword?.setOnEditorActionListener { _, _, _ ->
            doSearch(reset = true); true
        }

        refreshSiteCount()
        tvStatus?.setText(R.string.empty_magnet)

        val kw = arguments?.getString(MainActivity.EXTRA_KEYWORD)
        if (!kw.isNullOrBlank()) {
            etKeyword?.setText(kw)
            view.post { doSearch(reset = true) }
        }
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    private fun copy(text: String, label: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制$label")
    }

    private fun refreshSiteCount() {
        val all = SiteRepository.all(requireContext())
        tvSites?.text = "站点 ${prefs!!.enabledIds(all).size}/${all.size} · 管理"
    }

    private fun openSites() {
        val sheet = SitesSheet()
        sheet.onChanged = { refreshSiteCount() }
        sheet.show(childFragmentManager, "sites")
    }

    fun doSearch(reset: Boolean) {
        val kw = etKeyword?.text?.toString()?.trim().orEmpty()
        if (kw.isEmpty()) { toast("请输入关键词"); return }
        val all = SiteRepository.all(requireContext())
        val enabled = prefs!!.enabledIds(all)
        val sites = all.filter { enabled.contains(it.id) }
        if (sites.isEmpty()) { toast("请先在「站点」里勾选至少一个站点"); return }

        if (reset) {
            adapter?.items?.clear()
            adapter?.notifyDataSetChanged()
            seen.clear()
            probing.clear()
        }
        etKeyword?.windowToken?.let { token ->
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(token, 0)
        }
        scanning = true
        doneCount = 0
        totalCount = sites.size
        progress?.visibility = View.VISIBLE
        btnSearch?.isEnabled = false
        btnMore?.isEnabled = false
        tvStatus?.text = "正在扫描 0/$totalCount 个站点…"

        val page = if (reset) 1 else 1
        Thread {
            val sem = Semaphore(6)
            val done = AtomicInteger(0)
            val workers = sites.map { site ->
                Thread {
                    var items: List<MagnetItem> = emptyList()
                    var err: String? = null
                    try {
                        sem.acquire()
                        if (!Thread.interrupted()) items = SearchEngine.search(site, kw, page)
                    } catch (t: Throwable) {
                        err = t.message ?: t.javaClass.simpleName
                    } finally {
                        sem.release()
                    }
                    val fItems = items
                    val fErr = err
                    activity?.runOnUiThread {
                        if (isAdded && scanning) onSiteResult(site, fItems, fErr)
                    }
                }.also { it.start() }
            }
            workers.forEach { w ->
                try { w.join() } catch (e: InterruptedException) { return@Thread }
            }
        }.start()
    }

    private fun onSiteResult(site: Site, items: List<MagnetItem>, err: String?) {
        if (!isAdded) return
        doneCount++
        val fresh = ArrayList<MagnetItem>()
        for (it in items) {
            val key = if (it.magnet.isNotEmpty()) {
                val i = it.magnet.indexOf("btih:")
                (if (i >= 0) it.magnet.substring(i + 5) else it.magnet).lowercase(Locale.ROOT)
            } else {
                it.url.lowercase(Locale.ROOT)
            }
            if (seen.add(key)) fresh.add(it)
        }
        if (fresh.isNotEmpty()) {
            val start = adapter!!.items.size
            adapter!!.items.addAll(fresh)
            adapter!!.notifyItemRangeInserted(start, fresh.size)
            startDhtProbes(fresh)
        }
        val label = when {
            err != null -> when {
                err.contains("CertPathValidator") || err.contains("SSLHandshake") ||
                    err.contains("PKIX") || err.contains("Trust anchor") -> "证书错误"
                err.contains("Timeout") || err.contains("timed out") -> "超时"
                err.contains("Unable to resolve host") || err.contains("UnknownHost") -> "域名失效"
                err.contains("Connection refused") -> "拒绝连接"
                err.contains("401") -> "401"
                err.contains("404") -> "404"
                else -> "失败"
            }
            items.isEmpty() -> "无结果"
            else -> "${fresh.size} 条"
        }
        tvStatus?.text = "扫描中 $doneCount/$totalCount · 命中 ${adapter!!.items.size} · ${site.name}: $label"
        if (doneCount >= totalCount) {
            scanning = false
            progress?.visibility = View.GONE
            btnSearch?.isEnabled = true
            val has = adapter!!.items.isNotEmpty()
            btnMore?.isEnabled = has
            tvStatus?.text = if (has)
                "共 ${adapter!!.items.size} 条结果 · 点条目看详情，长按直接复制磁力"
            else
                "本页没有结果，站点可能已失效，换个关键词或换站点试试"
        }
    }

    /** Fire DHT probes + quality badges for fresh items; badges update in place. */
    private fun startDhtProbes(fresh: List<MagnetItem>) {
        var changed = false
        for (it in fresh) {
            it.quality = QualityTag.badge(it.title)
            if (it.quality.isNotEmpty()) changed = true
            val hash = it.infoHash() ?: continue
            if (!probing.add(hash)) continue
            it.probing = true
            changed = true
            TrackerProbe.probeAsync(hash, it.magnet, 8000) { seeders, leechers ->
                it.probing = false
                it.seeders = seeders
                it.leechers = leechers
                activity?.runOnUiThread {
                    val idx = adapter?.items?.indexOf(it) ?: -1
                    if (idx >= 0) adapter?.notifyItemChanged(idx)
                }
            }
        }
        if (changed) adapter?.notifyDataSetChanged()
    }

    private fun showDetail(item: MagnetItem) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (item.magnet.isNotEmpty()) {
            labels.add("⚡ 在线预览（边下边播）")
            actions.add { openPreview(item) }
            labels.add("用迅雷下载")
            actions.add { openWithXunlei(item.magnet) }
        }
        labels.add(getString(R.string.copy_magnet))
        actions.add { copy(item.magnet, "磁力链接") }
        labels.add(getString(R.string.copy_title))
        actions.add { copy(item.title, "标题") }
        if (item.url.isNotEmpty()) {
            labels.add("浏览器打开详情")
            actions.add {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                } catch (t: Throwable) {
                    toast("没有可用的浏览器")
                }
            }
            labels.add("复制详情链接")
            actions.add { copy(item.url, "详情链接") }
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打开「边下边播」预览页 */
    private fun openPreview(item: MagnetItem) {
        if (item.magnet.isEmpty()) {
            toast("这条没有磁力链接")
            return
        }
        try {
            startActivity(Intent(requireContext(), PlayActivity::class.java).apply {
                putExtra(PlayActivity.EXTRA_MAGNET, item.magnet)
                putExtra(PlayActivity.EXTRA_TITLE, item.title)
            })
        } catch (t: Throwable) {
            toast("无法打开预览页：${t.message}")
        }
    }

    private fun openWithXunlei(magnet: String) {
        val c = requireContext()
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("magnet", magnet))
        val uri = Uri.parse(magnet)
        val hasXunlei = try {
            c.packageManager.getLaunchIntentForPackage("com.xunlei.downloadprovider") != null
        } catch (t: Throwable) { false }
        if (hasXunlei) {
            if (tryStart(c, Intent(Intent.ACTION_VIEW, uri).setPackage("com.xunlei.downloadprovider"))) {
                toast("已复制磁力链接，正在打开迅雷"); return
            }
            if (tryStart(c, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent")
                    .setPackage("com.xunlei.downloadprovider"))) {
                toast("已复制磁力链接，正在打开迅雷"); return
            }
        }
        if (tryStart(c, Intent(Intent.ACTION_VIEW, uri))) {
            toast("已复制磁力链接，已调用系统下载器"); return
        }
        if (tryStart(c, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent"))) {
            toast("已复制磁力链接，已调用系统下载器"); return
        }
        val launch = if (hasXunlei) c.packageManager.getLaunchIntentForPackage("com.xunlei.downloadprovider") else null
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(c, launch)) {
                toast("磁力链接已复制，粘贴到迅雷即可下载"); return
            }
        }
        toast("磁力链接已复制（未检测到迅雷，可粘贴到任意下载器）")
    }

    private fun tryStart(c: Context, i: Intent): Boolean =
        try { c.startActivity(i); true } catch (t: Throwable) { false }

    override fun onDestroyView() {
        scanning = false
        super.onDestroyView()
    }
}
