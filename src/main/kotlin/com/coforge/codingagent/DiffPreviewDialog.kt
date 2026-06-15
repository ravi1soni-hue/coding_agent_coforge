package com.coforge.codingagent

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Shown before any file is written to disk.
 * The user sees a compact summary with line-change counts, and can open
 * the full IntelliJ diff viewer before deciding to approve or cancel.
 */
class DiffPreviewDialog(
    private val project: Project,
    private val filePath: String,
    private val oldContent: String,
    private val newContent: String,
    private val isNew: Boolean
) : DialogWrapper(project) {

    init {
        title = if (isNew) "New file: $filePath" else "Modify: $filePath"
        setOKButtonText("Apply")
        setCancelButtonText("Skip")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val diff = computeDiff(oldContent, newContent)
        val added = diff.count { it.type == DiffLine.Type.ADDED }
        val removed = diff.count { it.type == DiffLine.Type.REMOVED }

        val panel = JPanel(BorderLayout(0, 12)).apply {
            background = Color(22, 27, 34)
            border = JBUI.Borders.empty(16)
            preferredSize = Dimension(600, 380)
        }

        // Header summary
        val summary = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JBLabel(filePath).apply { foreground = Color(88, 166, 255); font = font.deriveFont(Font.BOLD, 13f) })
            if (!isNew) {
                add(JBLabel("+$added").apply { foreground = Color(63, 185, 80); font = font.deriveFont(Font.BOLD, 11f) })
                add(JBLabel("-$removed").apply { foreground = Color(248, 81, 73); font = font.deriveFont(Font.BOLD, 11f) })
            } else {
                add(JBLabel("new file · $added lines").apply { foreground = Color(63, 185, 80); font = font.deriveFont(11f) })
            }
        }

        // Mini diff preview (first 40 lines)
        val diffPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(13, 17, 23)
            border = JBUI.Borders.empty(8)
        }
        diff.take(40).forEach { line ->
            val (text, fg, bg) = when (line.type) {
                DiffLine.Type.ADDED   -> Triple("+ ${line.text}", Color(63, 185, 80), Color(22, 38, 22))
                DiffLine.Type.REMOVED -> Triple("- ${line.text}", Color(248, 81, 73), Color(38, 18, 18))
                DiffLine.Type.CONTEXT -> Triple("  ${line.text}", Color(139, 148, 158), Color(13, 17, 23))
            }
            diffPanel.add(JBLabel(text).apply {
                foreground = fg; background = bg; isOpaque = true
                font = Font("JetBrains Mono", Font.PLAIN, 11)
                border = JBUI.Borders.empty(0, 4)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        if (diff.size > 40) {
            diffPanel.add(JBLabel("  … ${diff.size - 40} more lines").apply {
                foreground = Color(139, 148, 158); font = font.deriveFont(10f)
                border = JBUI.Borders.empty(4); alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        val scroll = JBScrollPane(diffPanel).apply {
            border = BorderFactory.createLineBorder(Color(48, 54, 61), 1)
            viewport.background = Color(13, 17, 23)
        }

        // "Open full diff" button
        val openDiffBtn = JButton("Open in Diff Viewer").apply {
            isContentAreaFilled = false; border = JBUI.Borders.empty(4, 10)
            foreground = Color(88, 166, 255); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { openIntelliJDiff() }
        }
        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false; add(openDiffBtn)
        }

        panel.add(summary, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(btnRow, BorderLayout.SOUTH)
        return panel
    }

    private fun openIntelliJDiff() {
        val factory = DiffContentFactory.getInstance()
        val left  = factory.create(project, if (isNew) "" else oldContent)
        val right = factory.create(project, newContent)
        val request = SimpleDiffRequest(
            "Proposed change: $filePath",
            left, right,
            "Current", "Proposed"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    // ─── Simple line diff (context = 3 lines) ────────────────────────────────

    data class DiffLine(val type: Type, val text: String) {
        enum class Type { ADDED, REMOVED, CONTEXT }
    }

    private fun computeDiff(old: String, new: String): List<DiffLine> {
        val oldLines = old.lines()
        val newLines = new.lines()
        if (isNew) return newLines.map { DiffLine(DiffLine.Type.ADDED, it) }

        // Guard: skip LCS for very large files to avoid O(n²) memory + time.
        // Show simplified diff instead (won't have context lines, but won't OOM).
        if (oldLines.size > 2000 || newLines.size > 2000) {
            val result = mutableListOf<DiffLine>()
            oldLines.forEach { result.add(DiffLine(DiffLine.Type.REMOVED, it)) }
            newLines.forEach { result.add(DiffLine(DiffLine.Type.ADDED, it)) }
            return result.take(200)
        }

        // LCS-based diff — iterative backtrack (avoids StackOverflow on large files)
        val lcs = lcsMatrix(oldLines, newLines)
        val result = mutableListOf<DiffLine>()
        var i = oldLines.size
        var j = newLines.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> {
                    result.add(DiffLine(DiffLine.Type.CONTEXT, oldLines[i - 1])); i--; j--
                }
                j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
                    result.add(DiffLine(DiffLine.Type.ADDED, newLines[j - 1])); j--
                }
                else -> {
                    result.add(DiffLine(DiffLine.Type.REMOVED, oldLines[i - 1])); i--
                }
            }
        }
        result.reverse()

        // Collapse unchanged runs to 3-line context windows
        return collapseContext(result, contextLines = 3)
    }

    private fun lcsMatrix(a: List<String>, b: List<String>): Array<IntArray> {
        val m = a.size; val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp
    }

    private fun collapseContext(lines: List<DiffLine>, contextLines: Int): List<DiffLine> {
        if (lines.all { it.type == DiffLine.Type.CONTEXT }) return lines
        val result = mutableListOf<DiffLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.type != DiffLine.Type.CONTEXT) {
                // Include context before
                val start = maxOf(0, i - contextLines)
                if (result.isNotEmpty() && start > result.size) result.add(DiffLine(DiffLine.Type.CONTEXT, "…"))
                for (k in start until i) if (lines[k].type == DiffLine.Type.CONTEXT) result.add(lines[k])
                result.add(line)
            } else {
                // Peek ahead: if a changed line is within contextLines, include this context
                val nearChange = lines.subList(i, minOf(lines.size, i + contextLines + 1))
                    .any { it.type != DiffLine.Type.CONTEXT }
                if (nearChange) result.add(line)
            }
            i++
        }
        return result
    }
}
