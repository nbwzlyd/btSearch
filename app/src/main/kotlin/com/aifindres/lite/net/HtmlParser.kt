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

/** HtmlCleaner + XPath helper (mirror of o0/C0355a). */
object HtmlParser {
    private val cleaner = HtmlCleaner()

    init {
        val p: CleanerProperties = cleaner.properties
        p.isTranslateSpecialEntities = true
        p.isOmitComments = true
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
                    Node.ATTRIBUTE_NODE -> n.nodeValue ?: ""
                    Node.ELEMENT_NODE -> n.textContent ?: ""
                    else -> n.nodeValue ?: ""
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
