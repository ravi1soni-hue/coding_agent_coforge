package com.coforge.codingagent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Fully functional Swing-based chat panel — used when JCEF is not available.
 *
 * Features:
 * - Shows conversation history (loaded from ProjectHistoryService) via MarkdownRenderer
 * - Streams AI responses (appends tokens live in a JTextArea, replaces with
 *   MarkdownRenderer on completion for clean rendering)
 * - Ctrl+Enter (or Cmd+Enter) sends the message
 * - Banner linking to JCEF setup instructions
 */
class SwingChatPanel(private val project: Project, private val history: MutableList<Message>) {

    private val BG    = Color(13, 17, 23)
    private val CARD  = Color(22, 27, 34)
    private val BORD  = Color(48, 54, 61)
    private val TEXT  = Color(230, 237, 243)
    private val MUTE  = Color(125, 133, 144)
    private val ACCN  = Color(88, 166, 255)
    private val USER_BG = Color(30, 38, 50)

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = BG
        border = JBUI.Borders.empty(8)
    }
    private val scrollPane = JBScrollPane(messagesPanel).apply {
        border = null
        viewport.background = BG
        verticalScrollBarPolicy   = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val inputArea = JTextArea(3, 40).apply {
        lineWrap = true; wrapStyleWord = true
        background = CARD; foreground = TEXT
        caretColor = TEXT; font = Font("JetBrains Mono", Font.PLAIN, 12)
        border = JBUI.Borders.empty(8)
    }

    private val sendBtn = object : JButton("Send  ↵") {
        init {
            isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
            foreground = Color.WHITE; font = Font("Inter", Font.BOLD, 12)
            border = JBUI.Borders.empty(8, 14); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = ACCN
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
            super.paintComponent(g)
        }
    }

    /** The root component to embed in ChatToolWindowContent when JCEF is unavailable. */
    val component: JComponent = buildRoot()

    // Placeholder for the currently streaming AI message panel
    @Volatile private var streamingArea: JTextArea? = null
    @Volatile private var streamingWrapper: JPanel? = null

    init {
        // Render existing history on startup
        history.forEach { msg -> appendMessage(msg.role, msg.content, streaming = false) }

        sendBtn.addActionListener { sendMessage() }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val isSend = (e.isControlDown || e.isMetaDown) && e.keyCode == KeyEvent.VK_ENTER
                if (isSend) { e.consume(); sendMessage() }
            }
        })
    }

    private fun buildRoot(): JPanel {
        val root = JPanel(BorderLayout()).apply { background = BG }

        // Banner: text mode indicator + JCEF hint
        val banner = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            background = Color(25, 35, 50)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, BORD)
        }
        banner.add(JLabel("⚠ Text mode — ").apply { foreground = Color(255, 193, 7); font = Font("Inter", Font.BOLD, 11) })
        banner.add(JLabel("enable JCEF for full UI: ⌘⇧A → \"Choose Boot Runtime\" → pick \"with JCEF\" → Restart").apply {
            foreground = MUTE; font = Font("Inter", Font.PLAIN, 11)
        })

        // Bottom input row
        val inputWrapper = JPanel(BorderLayout(0, 0)).apply {
            background = CARD
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORD),
                JBUI.Borders.empty(6, 10)
            )
        }
        val inputScroll = JBScrollPane(inputArea).apply {
            border = BorderFactory.createLineBorder(BORD, 1)
            viewport.background = CARD
            preferredSize = Dimension(0, 76)
        }
        inputWrapper.add(inputScroll, BorderLayout.CENTER)
        inputWrapper.add(sendBtn, BorderLayout.EAST)

        val hint = JLabel("  Ctrl+Enter to send").apply { foreground = MUTE; font = Font("Inter", Font.PLAIN, 10) }
        val bottomPanel = JPanel(BorderLayout()).apply { background = CARD }
        bottomPanel.add(inputWrapper, BorderLayout.CENTER)
        bottomPanel.add(hint, BorderLayout.SOUTH)

        root.add(banner, BorderLayout.NORTH)
        root.add(scrollPane, BorderLayout.CENTER)
        root.add(bottomPanel, BorderLayout.SOUTH)
        return root
    }

    private fun sendMessage() {
        val text = inputArea.text.trim()
        if (text.isBlank()) return
        inputArea.text = ""
        inputArea.isEnabled = false
        sendBtn.isEnabled = false

        history.add(Message("user", text))
        appendMessage("user", text, streaming = false)

        // Prepare streaming area
        val (streamArea, wrapper) = addStreamingPlaceholder()
        streamingArea = streamArea
        streamingWrapper = wrapper

        Thread {
            // Build context on background thread
            val context = try {
                ApplicationManager.getApplication().runReadAction<String> {
                    EditorContext.getSmartContext(project, emptyList())
                }
            } catch (_: Exception) { "" }
            val graphCtx = try { EditorContext.getIndexedContext(project, text) } catch (_: Exception) { "" }
            val fullContext = if (graphCtx.isNotBlank()) "$context\n\n---\n\n$graphCtx" else context

            AiService.callAgentChain(
                userMessage = text,
                history     = history.dropLast(1),
                context     = fullContext,
                project     = project,
                onStatus    = { },
                onReasoning = { },
                onToken     = { tok ->
                    SwingUtilities.invokeLater {
                        streamArea.append(tok)
                        scrollToBottom()
                    }
                },
                onComplete  = { full ->
                    history.add(Message("assistant", full))
                    ProjectHistoryService.getInstance(project).save(project, history)
                    SwingUtilities.invokeLater {
                        // Replace streaming JTextArea with rendered MarkdownRenderer
                        replaceStreamingWithRendered(wrapper, full)
                        inputArea.isEnabled = true
                        sendBtn.isEnabled = true
                        inputArea.requestFocusInWindow()
                        scrollToBottom()
                    }
                }
            )
        }.apply { isDaemon = true; name = "SwingChat-AI" }.start()
    }

    private fun appendMessage(role: String, content: String, streaming: Boolean) {
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = if (role == "user") USER_BG else BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORD),
                JBUI.Borders.empty(10, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val label = JLabel(if (role == "user") "You" else "Coforge AI").apply {
            foreground = if (role == "user") ACCN else Color(63, 185, 80)
            font = Font("Inter", Font.BOLD, 11)
            border = JBUI.Borders.emptyBottom(6)
        }
        wrapper.add(label)

        if (!streaming) {
            val rendered = MarkdownRenderer.createComponent(content, if (role == "user") USER_BG else BG, TEXT)
            rendered.alignmentX = Component.LEFT_ALIGNMENT
            rendered.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            wrapper.add(rendered)
        }

        SwingUtilities.invokeLater {
            messagesPanel.add(wrapper)
            messagesPanel.revalidate()
            messagesPanel.repaint()
            scrollToBottom()
        }
    }

    /** Adds a live-streaming JTextArea for the incoming AI response. Returns the text area + wrapper. */
    private fun addStreamingPlaceholder(): Pair<JTextArea, JPanel> {
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORD),
                JBUI.Borders.empty(10, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        val label = JLabel("Coforge AI").apply {
            foreground = Color(63, 185, 80); font = Font("Inter", Font.BOLD, 11)
            border = JBUI.Borders.emptyBottom(6)
        }
        val area = JTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            background = BG; foreground = TEXT
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            border = null
        }
        wrapper.add(label)
        wrapper.add(area)
        SwingUtilities.invokeLater {
            messagesPanel.add(wrapper)
            messagesPanel.revalidate()
            scrollToBottom()
        }
        return area to wrapper
    }

    private fun replaceStreamingWithRendered(wrapper: JPanel, fullContent: String) {
        // Remove the JTextArea (second component) and add MarkdownRenderer
        if (wrapper.componentCount >= 2) wrapper.remove(1)
        val rendered = MarkdownRenderer.createComponent(fullContent, BG, TEXT)
        rendered.alignmentX = Component.LEFT_ALIGNMENT
        rendered.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        wrapper.add(rendered)
        wrapper.revalidate()
        wrapper.repaint()
    }

    private fun scrollToBottom() {
        val vsb = scrollPane.verticalScrollBar
        SwingUtilities.invokeLater { vsb.value = vsb.maximum }
    }

    /** Programmatically prefill and send a prompt (used by QuickActionsGroup). */
    fun prefillAndSend(prompt: String) {
        SwingUtilities.invokeLater {
            inputArea.text = prompt
            sendMessage()
        }
    }
}
