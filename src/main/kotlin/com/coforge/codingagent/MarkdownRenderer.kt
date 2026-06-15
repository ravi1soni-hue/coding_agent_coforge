package com.coforge.codingagent

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Converts markdown text to a styled Swing component.
 * Handles: headings, bold, italic, inline code, bullet lists, numbered lists.
 */
object MarkdownRenderer {

    fun createComponent(markdown: String, bgColor: Color, textColor: Color = Color.WHITE): JComponent {
        val html = toHtml(markdown.trim())
        val css = buildCss(bgColor, textColor)

        val kit = HTMLEditorKit()
        val styleSheet = StyleSheet()
        styleSheet.addRule(css)
        kit.styleSheet = styleSheet

        return JEditorPane().apply {
            editorKit = kit
            contentType = "text/html"
            text = "<html><body>$html</body></html>"
            isEditable = false
            isOpaque = false
            background = bgColor
            border = BorderFactory.createEmptyBorder()
            // Make links do nothing (no browser open)
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) { /* no-op */ }
            }
            // Wrap properly inside any parent width
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = Font("Inter", Font.PLAIN, 13)
        }
    }

    private fun buildCss(bg: Color, fg: Color): String {
        val fgHex = colorToHex(fg)
        val bgHex = colorToHex(bg)
        val codeBg = colorToHex(Color(22, 27, 34))
        val codeColor = colorToHex(Color(173, 186, 199))
        val mutedColor = colorToHex(Color(139, 148, 158))
        val accentColor = colorToHex(Color(88, 166, 255))
        return """
            body { font-family: Inter, sans-serif; font-size: 13px; color: $fgHex; background: $bgHex; margin: 0; padding: 0; }
            h1 { font-size: 18px; font-weight: bold; color: $fgHex; margin: 12px 0 6px 0; }
            h2 { font-size: 16px; font-weight: bold; color: $fgHex; margin: 10px 0 4px 0; }
            h3 { font-size: 14px; font-weight: bold; color: $fgHex; margin: 8px 0 4px 0; }
            p  { margin: 4px 0; line-height: 1.5; }
            ul, ol { margin: 4px 0 4px 16px; padding: 0; }
            li { margin: 2px 0; line-height: 1.5; }
            code { font-family: 'JetBrains Mono', monospace; font-size: 11px; background: $codeBg; color: $codeColor; padding: 1px 4px; border-radius: 3px; }
            pre { background: $codeBg; border-radius: 4px; padding: 8px 10px; margin: 6px 0; overflow-x: auto; }
            pre code { background: transparent; padding: 0; border-radius: 0; white-space: pre; }
            b, strong { font-weight: bold; color: $fgHex; }
            i, em { font-style: italic; color: $mutedColor; }
            a { color: $accentColor; text-decoration: none; }
            hr { border: 0; border-top: 1px solid #30363d; margin: 8px 0; }
            blockquote { border-left: 3px solid #30363d; margin: 4px 0 4px 8px; padding-left: 8px; color: $mutedColor; }
        """.trimIndent()
    }

    fun toHtml(md: String): String {
        val lines = md.lines()
        val sb = StringBuilder()
        var inUl = false
        var inOl = false
        var inBlockquote = false
        var inCode = false
        var codeLang = ""

        fun closeList() {
            if (inUl) { sb.append("</ul>"); inUl = false }
            if (inOl) { sb.append("</ol>"); inOl = false }
        }
        fun closeBlockquote() {
            if (inBlockquote) { sb.append("</blockquote>"); inBlockquote = false }
        }

        for (line in lines) {
            // Fenced code block toggle
            if (line.startsWith("```")) {
                if (!inCode) {
                    closeList(); closeBlockquote()
                    codeLang = line.removePrefix("```").trim().ifBlank { "code" }
                    sb.append("<pre><code class=\"lang-$codeLang\">")
                    inCode = true
                } else {
                    sb.append("</code></pre>")
                    inCode = false
                }
                continue
            }
            if (inCode) {
                // Inside code block — escape HTML, preserve newlines
                sb.append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                sb.append("\n")
                continue
            }
            when {
                line.startsWith("### ") -> {
                    closeList(); closeBlockquote()
                    sb.append("<h3>${inlineFormat(line.removePrefix("### "))}</h3>")
                }
                line.startsWith("## ") -> {
                    closeList(); closeBlockquote()
                    sb.append("<h2>${inlineFormat(line.removePrefix("## "))}</h2>")
                }
                line.startsWith("# ") -> {
                    closeList(); closeBlockquote()
                    sb.append("<h1>${inlineFormat(line.removePrefix("# "))}</h1>")
                }
                line.startsWith("> ") -> {
                    closeList()
                    if (!inBlockquote) { sb.append("<blockquote>"); inBlockquote = true }
                    sb.append(inlineFormat(line.removePrefix("> ")))
                }
                line.matches(Regex("^[-*] .+")) -> {
                    closeBlockquote()
                    if (!inUl) { if (inOl) { sb.append("</ol>"); inOl = false }; sb.append("<ul>"); inUl = true }
                    sb.append("<li>${inlineFormat(line.substring(2))}</li>")
                }
                line.matches(Regex("^\\d+\\. .+")) -> {
                    closeBlockquote()
                    if (!inOl) { if (inUl) { sb.append("</ul>"); inUl = false }; sb.append("<ol>"); inOl = true }
                    sb.append("<li>${inlineFormat(line.replaceFirst(Regex("^\\d+\\. "), ""))}</li>")
                }
                line == "---" || line == "***" || line == "___" -> {
                    closeList(); closeBlockquote(); sb.append("<hr>")
                }
                line.isBlank() -> {
                    closeList(); closeBlockquote(); sb.append("<p></p>")
                }
                else -> {
                    closeList(); closeBlockquote()
                    sb.append("<p>${inlineFormat(line)}</p>")
                }
            }
        }
        if (inCode) sb.append("</code></pre>")
        closeList()
        closeBlockquote()
        return sb.toString()
    }

    private fun inlineFormat(text: String): String {
        var s = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        // Bold + italic
        s = s.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*"), "<b><i>$1</i></b>")
        // Bold
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        s = s.replace(Regex("__(.+?)__"), "<b>$1</b>")
        // Italic
        s = s.replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
        s = s.replace(Regex("_(.+?)_"), "<i>$1</i>")
        // Inline code
        s = s.replace(Regex("`(.+?)`"), "<code>$1</code>")
        // Strikethrough
        s = s.replace(Regex("~~(.+?)~~"), "<s>$1</s>")
        return s
    }

    private fun colorToHex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
