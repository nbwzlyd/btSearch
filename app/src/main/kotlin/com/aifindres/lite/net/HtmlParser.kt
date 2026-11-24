package com.aifindres.lite.net

import org.htmlcleaner.CleanerProperties
import org.htmlcleaner.DomSerializer
import org.htmlcleaner.HtmlCleaner
import org.htmlcleaner.TagNode
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * HtmlCleaner + XPath helper (mirror of o0/C0355a).
 *
 * ⚠️ 为什么必须 unescape（真机踩坑，2026-11）：
 * HtmlCleaner 走 `DomSerializer.createDOM()` 再序列化时，属性值里的 `&` 会被转义成 `&amp;`。
 * 磁力链接的 tracker 恰恰是 `magnet:?xt=urn:btih:...&dn=...&tr=http://...` 这种形式，
 * 取回来的 href 就变成 `...&amp;tr=...` → 后面按 `[?&]tr=` 提取 tracker 时一个都匹配不到
 * （日志表现为 `magnet has 0 http trackers`，元数据永远拿不到）。
 * 站点原始 HTML 里 `&tr=` 有 380 处、`&amp;tr=` 是 0 处，所以这里在**取值时**统一解码实体。
 *
 * 未验证点：这是"取值时解码"而不是"解析时保留原始属性"；若将来某个 xpath 需要字面量 `&amp;`
 * 文本，此改法会误伤。
 */
object HtmlParser {
    private val cleaner = HtmlCleaner()

    init {
        val p: CleanerProperties = cleaner.properties
        p.isTranslateSpecialEntities = true
        p.isOmitComments = true
    }

    /** 命名实体表（只需覆盖站点常见的那批）。 */
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "hellip" to "\u2026",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "middot" to "\u00B7",
        "times" to "\u00D7",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
    )

    private val ENTITY_RE = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    /** 把 HTML 实体还原成字符（`&amp;` → `&`、`&#38;` / `&#x26;` → `&`）。 */
    fun unescape(s: String): String {
        if (s.isEmpty() || s.indexOf('&') < 0) return s
        return ENTITY_RE.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    cpOrNull(body.substring(2), 16) ?: m.value
                body.startsWith("#") ->
                    cpOrNull(body.substring(1), 10) ?: m.value
                else -> NAMED_ENTITIES[body.lowercase()] ?: m.value
            }
        }
    }

    /** 码点合法（排除代理区与越界）才转成字符，否则返回 null 保持原样。 */
    private fun cpOrNull(num: String, radix: Int): String? {
        if (num.isEmpty()) return null
        val cp = try {
            num.toInt(radix)
        } catch (e: NumberFormatException) {
            return null
        }
        if (cp <= 0 || cp > 0x10FFFF) return null
        if (cp in 0xD800..0xDFFF) return null // 代理区单独出现非法
        return try {
            String(Character.toChars(cp))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun parse(html: String): Document? = try {
        val node: TagNode = cleaner.clean(html)
        DomSerializer(cleaner.properties).createDOM(node)
    } catch (e: Exception) {
        null
    }

    /** Evaluate an XPath to first node's value (attribute → nodeValue, element → text). */
    fun eval(root: Node?, xpath: String?): String {
        if (root == null || xpath.isNullOrEmpty()) return ""
        return try {
            val x = XPathFactory.newInstance().newXPath()
            val r = x.evaluate(xpath, root, XPathConstants.NODE)
            (r as? Node)?.let { n ->
                when (n.nodeType) {
                    Node.ATTRIBUTE_NODE -> unescape(n.nodeValue ?: "")
                    Node.ELEMENT_NODE -> unescape(n.textContent ?: "")
                    else -> unescape(n.nodeValue ?: "")
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun evalList(root: Node?, xpath: String?): NodeList? {
        if (root == null || xpath.isNullOrEmpty()) return null
        return try {
            val x = XPathFactory.newInstance().newXPath()
            x.evaluate(xpath, root, XPathConstants.NODESET) as? NodeList
        } catch (e: Exception) {
            null
        }
    }
}
