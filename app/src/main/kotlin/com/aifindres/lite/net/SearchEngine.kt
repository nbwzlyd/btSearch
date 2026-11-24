package com.aifindres.lite.net

import com.aifindres.lite.data.MagnetItem
import com.aifindres.lite.data.Site
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Multi-site magnet search engine (mirror of o0/f). */
object SearchEngine {
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; M2012K11AC) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val MAGNET_FULL = Pattern.compile("magnet:\\?[^\"'\\s<>]+")
    private val BTIH_RE = Pattern.compile("xt=urn:btih:[A-Za-z0-9]+")

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Search one site, one page. Never throws. */
    fun search(site: Site, keyword: String, page: Int): List<MagnetItem> {
        val out = ArrayList<MagnetItem>()
        try {
            val req = site.request ?: return out
            val expr = site.expressionModel ?: return out
            val searchUrl = req.searchUrl ?: return out
            val kw = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
            var url = searchUrl.replace("[keyword]", kw)
            val p0 = maxOf(page - 1, 0)
            url = url.replace("[page0]", p0.toString()).replace("[page]", page.toString())
            val html = fetch(url, req.header) ?: return out
            val doc = HtmlParser.parse(html) ?: return out
            val group = HtmlParser.evalList(doc, expr.group) ?: return out
            if (group.length == 0) return out
            var detailBudget = 24
            for (i in 0 until group.length) {
                val node = group.item(i)
                val title = clean(HtmlParser.eval(node, expr.title))
                val date = clean(HtmlParser.eval(node, expr.date))
                val size = clean(HtmlParser.eval(node, expr.size))
                val link = clean(HtmlParser.eval(node, expr.url))
                val magnetRaw = clean(HtmlParser.eval(node, expr.magnet))
                var magnet = normalizeMagnet(magnetRaw) ?: normalizeMagnet(link)
                if (magnet == null && detailBudget > 0) {
                    val detail = when {
                        isCandidateUrl(magnetRaw) -> absolutize(magnetRaw, req.mainUrl)
                        isCandidateUrl(link) -> absolutize(link, req.mainUrl)
                        else -> null
                    }
                    if (detail != null) {
                        detailBudget--
                        val dh = try { fetch(detail, req.header) } catch (e: Exception) { null }
                        if (dh != null) {
                            val ddoc = HtmlParser.parse(dh)
                            magnet = if (ddoc != null) normalizeMagnet(clean(HtmlParser.eval(ddoc, expr.magnet))) else null
                            if (magnet == null) magnet = normalizeMagnet(dh)
                        }
                    }
                }
                if (title.isNotEmpty() && (link.isNotEmpty() || !magnet.isNullOrEmpty())) {
                    out.add(MagnetItem(title, date, size, absolutize(link, req.mainUrl), magnet ?: "", site.id, site.name))
                }
            }
        } catch (e: Exception) {
            // swallow: treat as site error upstream
        }
        return out
    }

    @Throws(Exception::class)
    fun fetch(url: String?, header: Map<String, String>?): String? {
        if (url.isNullOrEmpty()) return null
        val rb = Request.Builder().url(url).get()
        var hasUA = false
        header?.forEach { (k, v) ->
            if (k.isEmpty() || k.equals("Host", true) || k.equals("Content-Length", true)) return@forEach
            if (k.equals("User-Agent", true)) hasUA = true
            try { rb.header(k, v) } catch (e: Exception) {}
        }
        if (!hasUA) rb.header("User-Agent", DEFAULT_UA)
        rb.header("Accept-Language", "zh-CN,zh;q=0.9")
        client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val bytes = body.bytes()
            if (bytes.isEmpty()) return null
            val cs = body.contentType()?.charset()?.name() ?: sniffCharset(bytes)
            return try {
                String(bytes, charset(cs))
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    /** 取原始字节（tracker announce 是 bencode 二进制，不能按文本解码）。 */
    @Throws(Exception::class)
    fun fetchBytes(url: String?, header: Map<String, String>?): ByteArray? {
        if (url.isNullOrEmpty()) return null
        val rb = Request.Builder().url(url).get()
        var hasUA = false
        header?.forEach { (k, v) ->
            if (k.isEmpty() || k.equals("Host", true) || k.equals("Content-Length", true)) return@forEach
            if (k.equals("User-Agent", true)) hasUA = true
            try { rb.header(k, v) } catch (e: Exception) {}
        }
        if (!hasUA) rb.header("User-Agent", DEFAULT_UA)
        client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }

    fun clean(s: String?): String =
        (s ?: "").replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    fun normalizeMagnet(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        // 兜底：从原始 HTML 文本里正则捞磁力时，`&` 可能还带着 `&amp;` 转义，
        // 会让后面按 `[?&]tr=` 提 tracker 全部失效（表现为 magnet has 0 http trackers）。
        val t = if (s.contains("&amp;")) s.replace("&amp;", "&") else s
        if (!t.startsWith("magnet:")) return null
        val m = MAGNET_FULL.matcher(t)
        if (!m.find()) return null
        val g = m.group()
        return if (BTIH_RE.matcher(g).find()) g.trim() else null
    }

    private fun isCandidateUrl(s: String?): Boolean =
        !s.isNullOrEmpty() && (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("/"))

    private fun absolutize(path: String?, base: String?): String {
        if (path.isNullOrEmpty()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (base == null) return path
        val cut = base.lastIndexOf('/')
        if (cut < 0) return path
        val root = base.substring(0, cut + 1)
        if (!root.startsWith("http")) return path
        return if (path.startsWith("/")) root + path.substring(1) else root + path
    }

    private fun sniffCharset(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        return when {
            head.contains("charset=gb2312") -> "GB2312"
            head.contains("charset=gbk") -> "GBK"
            head.contains("charset=gb18030") -> "GB18030"
            else -> "UTF-8"
        }
    }
}
