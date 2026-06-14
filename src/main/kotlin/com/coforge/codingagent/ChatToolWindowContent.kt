package com.coforge.codingagent

import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.regex.Pattern
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.Timer
import javax.swing.TransferHandler

class ChatToolWindowContent(private val project: Project) {

    val contentPanel = JPanel(CardLayout())
    private val cardLayout = contentPanel.layout as CardLayout

    private val chatPanel    = JPanel(BorderLayout())
    private val welcomePanel = JPanel(GridBagLayout())

    private val messagePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = BG_BASE; border = JBUI.Borders.empty(12, 14)
    }

    // ── Input area ────────────────────────────────────────────────────────────
    private val inputArea = JBTextArea(3, 20).apply {
        lineWrap = true; wrapStyleWord = true
        background = BG_INPUT; foreground = TEXT_PRIMARY
        caretColor = ACCENT_BLUE; selectedTextColor = TEXT_PRIMARY
        selectionColor = Color(88, 166, 255, 60)
        font = Font("Inter", Font.PLAIN, 13)
        margin = JBUI.insets(12, 14, 12, 14); border = BorderFactory.createEmptyBorder()
    }

    // ── Status row ────────────────────────────────────────────────────────────
    private val statusLabel = JBLabel("Ready").apply {
        foreground = TEXT_MUTED; font = Font("Inter", Font.PLAIN, 11)
    }
    private val intentBadge = RoundedLabel("").apply { isVisible = false }
    private val stopBtn = pill("■ Stop", Color(248, 81, 73)).apply {
        isVisible = false
        addActionListener { AiService.stop(); isVisible = false; statusLabel.text = "Stopped" }
    }

    // ── File/image attachments ────────────────────────────────────────────────
    private val taggedFiles   = mutableSetOf<VirtualFile>()
    private val attachBar     = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
    private val pendingImages = mutableListOf<Triple<String, ImageIcon, Int>>()
    private var imageIndex    = 0

    // ── Conversation ──────────────────────────────────────────────────────────
    private val conversationHistory = mutableListOf<Message>()

    // ── Project badge ─────────────────────────────────────────────────────────
    private val projectBadge = RoundedLabel("").apply {
        bg = BG_ELEVATED; fg = TEXT_MUTED; padH = 8; padV = 3
        font = Font("Inter", Font.BOLD, 9)
    }

    // ── Terminal panel ────────────────────────────────────────────────────────
    private val terminalOutput = JTextArea().apply {
        isEditable = false; background = Color(10, 12, 16); foreground = Color(180, 200, 180)
        font = Font("JetBrains Mono", Font.PLAIN, 11); margin = JBUI.insets(10, 12)
    }
    private val terminalPanel: JPanel = buildTerminalPanel()

    private val isFlutter get() =
        ProjectTypeDetector.detect(project).type == ProjectTypeDetector.ProjectType.FLUTTER

    // ─────────────────────────────────────────────────────────────────────────
    init {
        updateProjectBadge()
        setupWelcomeUI()
        setupChatUI()
        contentPanel.add(welcomePanel, "WELCOME")
        contentPanel.add(chatPanel,    "CHAT")
        cardLayout.show(contentPanel,  "WELCOME")
        ProjectIndexer.warmUp(project)
        Thread { CodebaseGraph.build(project) }
            .apply { isDaemon = true; name = "CoforgeGraphWarmup" }.start()
    }

    // ─── Public ───────────────────────────────────────────────────────────────
    fun prefillAndSend(prompt: String) {
        switchToChat(); inputArea.text = prompt; sendMessage()
    }

    // ─── Welcome screen ───────────────────────────────────────────────────────
    private fun setupWelcomeUI() {
        welcomePanel.background = BG_BASE
        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = GridBagConstraints.RELATIVE
            insets = JBUI.insets(6); anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.HORIZONTAL
        }

        // Logo
        welcomePanel.add(object : JPanel() {
            init { isOpaque = false; preferredSize = Dimension(260, 64) }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Gradient "C" circle
                val grad = GradientPaint(0f, 0f, ACCENT_PURPLE, 48f, 48f, ACCENT_BLUE)
                g2.paint = grad
                g2.fillOval(0, 8, 48, 48)
                g2.paint = null
                g2.color = Color.WHITE
                g2.font = Font("Inter", Font.BOLD, 26)
                val fm = g2.fontMetrics
                g2.drawString("C", 14, 8 + 48 / 2 + fm.ascent / 2 - 2)
                // Name
                g2.color = TEXT_PRIMARY
                g2.font = Font("Inter", Font.BOLD, 20)
                g2.drawString("Coforge AI", 60, 38)
            }
        }, gbc)

        welcomePanel.add(projectBadge, gbc.also { it.insets = JBUI.insets(0, 6, 2, 6) })

        welcomePanel.add(JBLabel("Kimi · Gemini · GPT · Vision").apply {
            foreground = TEXT_SUBTLE; font = Font("Inter", Font.PLAIN, 11)
            horizontalAlignment = SwingConstants.CENTER
        }, gbc)

        // Suggestion chips
        val chips = JPanel(GridLayout(2, 2, 8, 8)).apply {
            isOpaque = false; border = JBUI.Borders.empty(12, 4)
        }
        listOf(
            Triple("💡 Explain Code",      "Explain how this code works",          "Explain: "),
            Triple("🐛 Fix Bug",           "Debug, trace and fix issues",           "Fix the bug in:\n\n"),
            Triple("🏗️ Build Feature",     "Implement from a Jira ticket or brief", "Implement: "),
            Triple("🧪 Write Tests",       "Generate unit & widget tests",          "Write tests for:\n\n")
        ).forEach { (title, sub, prefix) ->
            chips.add(object : JPanel(BorderLayout(0, 4)) {
                init {
                    isOpaque = false; border = JBUI.Borders.empty(12, 14)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    add(JBLabel(title).apply {
                        foreground = TEXT_PRIMARY; font = Font("Inter", Font.BOLD, 12)
                    }, BorderLayout.NORTH)
                    add(JBLabel("<html><small>$sub</small></html>").apply {
                        foreground = TEXT_MUTED
                    }, BorderLayout.CENTER)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) = switchToChat(prefix)
                        override fun mouseEntered(e: MouseEvent) { repaint() }
                        override fun mouseExited(e: MouseEvent)  { repaint() }
                    })
                }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val hovered = mousePosition != null
                    g2.color = if (hovered) BG_SURFACE else BG_ELEVATED
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f))
                    g2.color = BG_BORDER
                    g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 10f, 10f))
                }
            })
        }
        welcomePanel.add(chips, gbc.also { it.fill = GridBagConstraints.BOTH })

        welcomePanel.add(JBLabel("Paste screenshots · Drop images · @-tag files").apply {
            foreground = TEXT_SUBTLE; font = Font("Inter", Font.PLAIN, 10)
            horizontalAlignment = SwingConstants.CENTER
        }, gbc)
    }

    // ─── Chat screen ──────────────────────────────────────────────────────────
    private fun setupChatUI() {
        chatPanel.background = BG_BASE

        // Header
        val header = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = BG_ELEVATED
                g.fillRect(0, 0, width, height)
                (g as Graphics2D).color = BG_BORDER
                g.drawLine(0, height - 1, width, height - 1)
            }
        }.apply {
            isOpaque = false; border = JBUI.Borders.empty(0, 14)
            preferredSize = Dimension(0, 44)

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
            // Logo dot
            left.add(object : JComponent() {
                init { preferredSize = Dimension(22, 22) }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.paint = GradientPaint(0f, 0f, ACCENT_PURPLE, 22f, 22f, ACCENT_BLUE)
                    g2.fillOval(0, 0, 22, 22)
                    g2.paint = null; g2.color = Color.WHITE; g2.font = Font("Inter", Font.BOLD, 13)
                    val fm = g2.fontMetrics
                    g2.drawString("C", 6, 11 + fm.ascent / 2 - 1)
                }
            })
            left.add(JBLabel("Coforge AI").apply { foreground = TEXT_PRIMARY; font = Font("Inter", Font.BOLD, 13) })
            left.add(projectBadge)

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
            right.add(iconBtn(AllIcons.Actions.Refresh, "New session") { resetSession() })
            right.add(iconBtn(AllIcons.General.Settings, "Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AppSettingsConfigurable::class.java)
            })

            add(left, BorderLayout.WEST); add(right, BorderLayout.EAST)
        }

        val scroll = JBScrollPane(messagePanel).apply {
            border = null; viewport.background = BG_BASE; verticalScrollBar.unitIncrement = 20
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Center stack = messages + terminal
        val centerStack = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(scroll, BorderLayout.CENTER)
            add(terminalPanel, BorderLayout.SOUTH)
        }

        val footer = buildFooter()

        chatPanel.add(header,      BorderLayout.NORTH)
        chatPanel.add(centerStack, BorderLayout.CENTER)
        chatPanel.add(footer,      BorderLayout.SOUTH)

        setupTagging()
        setupImageDrop()
    }

    // ─── Footer / input box ───────────────────────────────────────────────────
    private fun buildFooter(): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            background = BG_BASE; border = JBUI.Borders.empty(8, 10, 10, 10)
        }

        val box = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BG_INPUT
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f))
                g2.color = BG_BORDER
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 12f, 12f))
            }
        }.apply { isOpaque = false }

        // Attachment row (tags + images)
        attachBar.border = JBUI.Borders.empty(6, 10, 0, 10)
        val attachScroll = JScrollPane(attachBar).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, 32)
            isVisible = false
        }

        val inputScroll = JBScrollPane(inputArea).apply {
            border = null; viewport.background = BG_INPUT; isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(0, 80)
            maximumSize = Dimension(Int.MAX_VALUE, 200)
        }

        // Bottom toolbar inside the box
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.empty(4, 10, 8, 10)

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
            left.add(statusLabel); left.add(intentBadge); left.add(stopBtn)
            // Run buttons
            left.add(JSeparator(SwingConstants.VERTICAL).apply {
                preferredSize = Dimension(1, 16); foreground = BG_BORDER
            })
            left.add(terminalChip("Tests") {
                startAgenticTestLoop(emptyList(), attempt = 1)
            })
            left.add(terminalChip("Build") {
                runTerminalCommand("Building...") { out, done ->
                    if (isFlutter) TerminalExecutor.flutterBuild(project, out, done)
                    else           TerminalExecutor.buildDebug(project, out, done)
                }
            })
            left.add(terminalChip("Lint") {
                runTerminalCommand("Analyzing...") { out, done ->
                    if (isFlutter) TerminalExecutor.flutterAnalyze(project, out, done)
                    else           TerminalExecutor.runLint(project, out, done)
                }
            })

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
            right.add(iconBtn(AllIcons.General.Add, "Attach image") { openImagePicker() })
            // Send button — colored circle
            right.add(object : JButton() {
                init {
                    isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
                    preferredSize = Dimension(30, 30); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Send (Enter)"
                    addActionListener { sendMessage() }
                }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = if (inputArea.isEnabled) ACCENT_BLUE else BG_BORDER
                    g2.fillOval(1, 1, 28, 28)
                    // Arrow glyph
                    g2.color = Color.WHITE; g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(8, 15, 22, 15); g2.drawLine(16, 9, 22, 15); g2.drawLine(16, 21, 22, 15)
                }
            })

            add(left, BorderLayout.WEST); add(right, BorderLayout.EAST)
        }

        box.add(attachScroll,  BorderLayout.NORTH)
        box.add(inputScroll,   BorderLayout.CENTER)
        box.add(toolbar,       BorderLayout.SOUTH)

        wrapper.add(box, BorderLayout.CENTER)

        // Show attach bar only when there are attachments
        val updateAttachBar: () -> Unit = {
            attachScroll.isVisible = attachBar.componentCount > 0
            attachScroll.revalidate()
        }
        attachBar.addContainerListener(object : java.awt.event.ContainerAdapter() {
            override fun componentAdded(e: java.awt.event.ContainerEvent)   = updateAttachBar()
            override fun componentRemoved(e: java.awt.event.ContainerEvent) = updateAttachBar()
        })

        return wrapper
    }

    private fun buildTerminalPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            background = Color(10, 12, 16); isVisible = false
            preferredSize = Dimension(0, 200)
        }
        val header = JPanel(BorderLayout()).apply {
            background = Color(18, 20, 24); border = JBUI.Borders.empty(5, 12)
            add(JBLabel("Terminal").apply {
                foreground = TEXT_MUTED; font = Font("Inter", Font.BOLD, 10)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(iconBtn(AllIcons.Actions.Close, "Close") { panel.isVisible = false; panel.revalidate() })
            }, BorderLayout.EAST)
        }
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(terminalOutput).apply { border = null }, BorderLayout.CENTER)
        return panel
    }

    // ─── Tagging & images ─────────────────────────────────────────────────────
    private fun setupTagging() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) { if (e.keyChar == '@') showFilePicker() }
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); sendMessage() }
            }
        })
    }

    private fun showFilePicker() {
        val files = EditorContext.getAllProjectFiles(project)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(files.map { it.name })
            .setTitle("Tag a file (@)")
            .setItemChosenCallback { name ->
                val f = files.find { it.name == name } ?: return@setItemChosenCallback
                addFilePill(f)
                val t = inputArea.text; val at = t.lastIndexOf('@')
                if (at >= 0) inputArea.text = t.substring(0, at)
            }.createPopup().showUnderneathOf(inputArea)
    }

    private fun addFilePill(file: VirtualFile) {
        if (!taggedFiles.add(file)) return
        val pill = object : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {
            init {
                isOpaque = false; cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
                add(JBLabel("@${file.name}").apply {
                    foreground = ACCENT_BLUE; font = Font("Inter", Font.BOLD, 10)
                })
                val x = JBLabel("×").apply {
                    foreground = TEXT_MUTED; font = Font("Inter", Font.PLAIN, 12)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            taggedFiles.remove(file)
                            attachBar.remove(parent); attachBar.revalidate(); attachBar.repaint()
                        }
                    })
                }
                add(x)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(88, 166, 255, 20)
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f))
                g2.color = Color(88, 166, 255, 60)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 6f, 6f))
            }
        }
        attachBar.add(pill); attachBar.revalidate()
    }

    private fun setupImageDrop() {
        val originalPaste = inputArea.actionMap.get("paste")
        inputArea.actionMap.put("paste", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val clip = Toolkit.getDefaultToolkit().systemClipboard
                val cont = clip.getContents(null)
                if (cont?.isDataFlavorSupported(DataFlavor.imageFlavor) == true) {
                    (cont.getTransferData(DataFlavor.imageFlavor) as? BufferedImage)
                        ?.let { addImageAttachment(it, "Pasted") }
                } else originalPaste?.actionPerformed(e)
            }
        })
        inputArea.transferHandler = object : TransferHandler() {
            override fun canImport(s: TransferSupport) =
                s.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                s.isDataFlavorSupported(DataFlavor.imageFlavor)
            override fun importData(s: TransferSupport): Boolean {
                if (!canImport(s)) return false
                return try {
                    when {
                        s.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                            @Suppress("UNCHECKED_CAST")
                            (s.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                                .filter { it.extension.lowercase() in setOf("png","jpg","jpeg","gif","webp","bmp") }
                                .forEach { f -> ImageIO.read(f)?.let { addImageAttachment(it, f.name) } }
                            true
                        }
                        s.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                            (s.transferable.getTransferData(DataFlavor.imageFlavor) as? BufferedImage)
                                ?.let { addImageAttachment(it, "Dropped") }; true
                        }
                        else -> false
                    }
                } catch (_: Exception) { false }
            }
        }
    }

    private fun openImagePicker() {
        val fc = JFileChooser().apply {
            dialogTitle = "Attach Image"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Images","png","jpg","jpeg","gif","webp")
            isMultiSelectionEnabled = true
        }
        if (fc.showOpenDialog(contentPanel) == JFileChooser.APPROVE_OPTION)
            fc.selectedFiles.forEach { f -> ImageIO.read(f)?.let { addImageAttachment(it, f.name) } }
    }

    private fun addImageAttachment(image: BufferedImage, name: String) {
        val resized = resizeImage(image, 1024)
        val b64     = encodeBase64(resized)
        val thumb   = ImageIcon(resized.getScaledInstance(40, 40, Image.SCALE_SMOOTH))
        val idx     = ++imageIndex
        pendingImages.add(Triple(b64, thumb, idx))

        val pill = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false; preferredSize = Dimension(50, 50); maximumSize = Dimension(50, 50)
                add(JLabel(thumb), BorderLayout.CENTER)
                val x = JBLabel("×").apply {
                    foreground = TEXT_MUTED; font = Font("Inter", Font.BOLD, 13)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    horizontalAlignment = SwingConstants.CENTER
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            pendingImages.removeIf { it.third == idx }
                            attachBar.remove(parent); attachBar.revalidate(); attachBar.repaint()
                        }
                    })
                }
                add(x, BorderLayout.NORTH); toolTipText = name
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BG_SURFACE
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f))
            }
        }
        attachBar.add(pill); attachBar.revalidate(); switchToChat()
    }

    // ─── Send ─────────────────────────────────────────────────────────────────
    private fun sendMessage() {
        val text   = inputArea.text.trim()
        val images = pendingImages.map { it.first }
        if (text.isEmpty() && images.isEmpty()) return

        switchToChat(); inputArea.text = ""; setInputEnabled(false); stopBtn.isVisible = true

        val imgCount = pendingImages.size
        pendingImages.clear(); attachBar.removeAll(); attachBar.revalidate()

        val intent = AiService.detectIntent(text)
        intentBadge.text = when (intent) {
            Intent.EXPLAIN      -> "EXPLAIN"
            Intent.FIX          -> "FIX"
            Intent.CODE_SIMPLE  -> "EDIT"
            Intent.CODE_COMPLEX -> "BUILD"
        }
        intentBadge.bg = when (intent) {
            Intent.EXPLAIN      -> Color(31, 111, 235, 100)
            Intent.FIX          -> Color(210, 100, 30, 100)
            Intent.CODE_SIMPLE  -> Color(88, 166, 255, 80)
            Intent.CODE_COMPLEX -> Color(139, 92, 246, 100)
        }
        intentBadge.isVisible = true

        if (conversationHistory.size >= 100) conversationHistory.subList(0, 40).clear()
        conversationHistory.add(Message("user", text + if (imgCount > 0) " [+$imgCount image(s)]" else ""))
        appendUserBubble(text, imgCount)

        val context = EditorContext.getSmartContext(project, taggedFiles.toList())
        val (streamArea, reasoningArea) = appendAiBubble()

        AiService.callAgentChain(
            userMessage = text,
            history     = conversationHistory.dropLast(1),
            context     = context,
            images      = images,
            project     = project,
            onStatus    = { s   -> SwingUtilities.invokeLater { statusLabel.text = s } },
            onReasoning = { r   -> SwingUtilities.invokeLater {
                reasoningArea.text = r
                reasoningArea.parent?.isVisible = true; reasoningArea.parent?.revalidate()
            }},
            onToken     = { tok -> SwingUtilities.invokeLater { streamArea.append(tok); scrollToBottom() } },
            onComplete  = { full -> SwingUtilities.invokeLater {
                conversationHistory.add(Message("assistant", full))
                val body = streamArea.parent as? JPanel
                if (body != null && full.isNotBlank()) {
                    body.removeAll()
                    parseMessageParts(full).forEach { body.add(it) }
                    val changes = parseAllFileChanges(full)
                    if (changes.size >= 2) body.add(buildApplyAllRow(changes))
                    body.revalidate(); body.repaint()
                }
                statusLabel.text = "Ready"; intentBadge.isVisible = false
                stopBtn.isVisible = false; setInputEnabled(true); scrollToBottom()
            }}
        )
    }

    // ─── Message bubbles ──────────────────────────────────────────────────────

    private fun appendUserBubble(text: String, imgCount: Int) {
        val bubble = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = USER_BUBBLE
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f))
            }
        }.apply {
            isOpaque = false; border = JBUI.Borders.empty(10, 14)
            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                if (text.isNotBlank()) add(MarkdownRenderer.createComponent(text, USER_BUBBLE).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                })
                if (imgCount > 0) add(JBLabel("📎 $imgCount image(s) attached").apply {
                    foreground = ACCENT_BLUE; font = Font("Inter", Font.PLAIN, 11)
                    border = JBUI.Borders.emptyTop(4); alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            add(inner, BorderLayout.CENTER)
        }
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.empty(2, 0, 10, 0)
            add(Box.createHorizontalStrut(80), BorderLayout.WEST)
            add(bubble, BorderLayout.CENTER)
        }
        messagePanel.add(row); messagePanel.revalidate(); scrollToBottom()
    }

    private fun appendAiBubble(): Pair<JTextArea, JTextArea> {
        // Streaming text area (replaced by markdown on completion)
        val streamArea = JTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            background = Color(0,0,0,0); foreground = TEXT_PRIMARY
            font = Font("Inter", Font.PLAIN, 13); border = BorderFactory.createEmptyBorder()
            isOpaque = false
        }

        val reasoningContent = JTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            background = BG_ELEVATED; foreground = TEXT_MUTED
            font = Font("JetBrains Mono", Font.PLAIN, 10); border = JBUI.Borders.empty(6, 10)
            isVisible = false
        }

        val reasoningPanel = JPanel(BorderLayout()).apply {
            background = BG_ELEVATED
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BG_BORDER, 1),
                JBUI.Borders.empty(0))
            isVisible = false
            alignmentX = Component.LEFT_ALIGNMENT
            val toggle = JButton("▶  Thinking").apply {
                isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
                foreground = TEXT_MUTED; font = Font("Inter", Font.BOLD, 10)
                border = JBUI.Borders.empty(5, 10); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    reasoningContent.isVisible = !reasoningContent.isVisible
                    text = if (reasoningContent.isVisible) "▼  Thinking" else "▶  Thinking"
                    revalidate()
                }
            }
            add(toggle, BorderLayout.NORTH); add(reasoningContent, BorderLayout.CENTER)
        }

        // Body panel — holds streamArea while streaming, then replaced by markdown
        val bodyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(streamArea)
        }

        // Typing indicator (3 animated dots)
        val typingDots = buildTypingIndicator()

        // Action bar (Copy / Retry)
        val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(tinyBtn("Copy") {
                val c = conversationHistory.lastOrNull { it.role == "assistant" }?.content ?: ""
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c), null)
            })
            add(tinyBtn("Retry") {
                val last = conversationHistory.lastOrNull { it.role == "user" }?.content ?: return@tinyBtn
                conversationHistory.removeLastOrNull(); conversationHistory.removeLastOrNull()
                inputArea.text = last.removeSuffix(RE_IMAGE_SUFFIX.find(last)?.value ?: "")
                sendMessage()
            })
        }

        // Avatar dot
        val avatar = object : JComponent() {
            init { preferredSize = Dimension(28, 28); minimumSize = Dimension(28, 28) }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.paint = GradientPaint(0f, 0f, ACCENT_PURPLE, 28f, 28f, ACCENT_BLUE)
                g2.fillOval(0, 0, 28, 28)
                g2.paint = null; g2.color = Color.WHITE; g2.font = Font("Inter", Font.BOLD, 14)
                val fm = g2.fontMetrics; g2.drawString("C", 8, 14 + fm.ascent / 2 - 1)
            }
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            add(Box.createVerticalStrut(2))
            add(typingDots)
            add(reasoningPanel)
            add(Box.createVerticalStrut(4))
            add(bodyPanel)
            add(Box.createVerticalStrut(4))
            add(actionBar)
        }

        val bubble = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false; border = JBUI.Borders.empty(2, 0, 14, 0)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false; border = JBUI.Borders.emptyTop(2)
                add(avatar, BorderLayout.NORTH)
            }, BorderLayout.WEST)
            add(inner, BorderLayout.CENTER)
            add(Box.createHorizontalStrut(60), BorderLayout.EAST)
        }
        messagePanel.add(bubble); messagePanel.revalidate()
        return streamArea to reasoningContent
    }

    private fun buildTypingIndicator(): JPanel {
        val dots = Array(3) { i ->
            object : JComponent() {
                var alpha = if (i == 0) 1f else 0.3f
                init { preferredSize = Dimension(7, 7); maximumSize = Dimension(7, 7) }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(88, 166, 255, (255 * alpha).toInt())
                    g2.fillOval(0, 0, 7, 7)
                }
            }
        }
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            dots.forEach { add(it) }
            border = JBUI.Borders.emptyBottom(4)
        }

        var frame = 0
        val timer = Timer(400) {
            dots.forEachIndexed { i, d ->
                d.alpha = if (i == frame % 3) 1f else 0.25f
                d.repaint()
            }
            frame++
        }
        timer.start()

        // Stop timer when this panel is removed from hierarchy
        panel.addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (!panel.isShowing) timer.stop()
            }
        }
        return panel
    }

    // ─── Message parsing ──────────────────────────────────────────────────────
    private fun parseMessageParts(message: String): List<Component> {
        val components = mutableListOf<Component>()
        val split = Pattern.compile(
            "(```[\\w]*\\n[\\s\\S]+?\\n```|<(?:file_change|new_file) path=\".+?\">[\\s\\S]+?</(?:file_change|new_file)>)"
        )
        val codePat   = Pattern.compile("```(\\w*)\\n([\\s\\S]+?)\\n```")
        val changePat = Pattern.compile("<(file_change|new_file) path=\"(.+?)\">([\\s\\S]+?)</(file_change|new_file)>")
        var lastIdx = 0
        val m = split.matcher(message)
        while (m.find()) {
            val prose = message.substring(lastIdx, m.start()).trim()
            if (prose.isNotEmpty()) components.add(buildMarkdown(prose))
            val block = m.group()
            when {
                block.startsWith("```") -> {
                    val cm = codePat.matcher(block)
                    if (cm.find()) components.add(buildCodeBlock(cm.group(2), cm.group(1).ifEmpty { "code" }))
                }
                else -> {
                    val cm = changePat.matcher(block)
                    if (cm.find()) components.add(buildChangeProposal(cm.group(2), cm.group(3), cm.group(1) == "new_file"))
                }
            }
            lastIdx = m.end()
        }
        val tail = message.substring(lastIdx).trim()
        if (tail.isNotEmpty()) components.add(buildMarkdown(tail))
        return components
    }

    private fun buildMarkdown(text: String) =
        MarkdownRenderer.createComponent(text, Color(0, 0, 0, 0), TEXT_PRIMARY).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

    private fun buildCodeBlock(code: String, lang: String) = object : JPanel(BorderLayout()) {
        init {
            isOpaque = false; border = JBUI.Borders.empty(4, 0, 8, 0)
            alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 300)

            val header = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics) {
                    g.color = Color(22, 27, 34)
                    g.fillRect(0, 0, width, height)
                }
            }.apply {
                isOpaque = false; border = JBUI.Borders.empty(5, 12)
                add(RoundedLabel(lang.uppercase()).apply {
                    bg = Color(88, 166, 255, 30); fg = ACCENT_BLUE; padH = 6; padV = 2
                    font = Font("JetBrains Mono", Font.BOLD, 9)
                }, BorderLayout.WEST)
                add(iconBtn(AllIcons.Actions.Copy, "Copy code") {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
                }, BorderLayout.EAST)
            }

            val area = JTextArea(code).apply {
                background = Color(13, 17, 23); foreground = Color(173, 186, 199)
                font = Font("JetBrains Mono", Font.PLAIN, 12); isEditable = false
                margin = JBUI.insets(12); border = BorderFactory.createEmptyBorder()
            }

            add(header, BorderLayout.NORTH)
            add(JBScrollPane(area).apply { border = null; viewport.background = Color(13, 17, 23) }, BorderLayout.CENTER)
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(13, 17, 23)
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
            g2.color = BG_BORDER
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 8f, 8f))
        }
    }

    private fun buildChangeProposal(path: String, code: String, isNew: Boolean) =
        object : JPanel(BorderLayout()) {
            init {
                isOpaque = false; border = JBUI.Borders.empty(3, 0, 6, 0)
                alignmentX = Component.LEFT_ALIGNMENT

                val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
                left.add(RoundedLabel(if (isNew) "NEW" else "EDIT").apply {
                    bg = if (isNew) Color(63, 185, 80, 30) else Color(88, 166, 255, 30)
                    fg = if (isNew) Color(63, 185, 80) else ACCENT_BLUE
                    padH = 6; padV = 2; font = Font("Inter", Font.BOLD, 9)
                })
                left.add(JBLabel(path).apply {
                    foreground = TEXT_PRIMARY; font = Font("JetBrains Mono", Font.PLAIN, 11)
                })

                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
                right.add(tinyBtn("Skip") { parent?.remove(this@object); parent?.revalidate() })
                right.add(tinyBtn("Diff") {
                    val base = project.basePath ?: return@tinyBtn
                    val old = if (isNew) "" else try { File("$base/$path").readText() } catch (_: Exception) { "" }
                    DiffPreviewDialog(project, path, old, code, isNew).show()
                })
                right.add(object : JButton("Apply") {
                    init {
                        background = Color(35, 134, 54); foreground = Color.WHITE
                        font = Font("Inter", Font.BOLD, 11); border = JBUI.Borders.empty(5, 12)
                        isFocusPainted = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        addActionListener { showDiffAndApply(path, code, isNew) }
                    }
                    override fun paintComponent(g: Graphics) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = background
                        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f))
                        g2.color = foreground; g2.font = font
                        val fm = g2.fontMetrics
                        g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
                    }
                })

                val row = JPanel(BorderLayout()).apply {
                    isOpaque = false; border = JBUI.Borders.empty(7, 12)
                    add(left, BorderLayout.WEST); add(right, BorderLayout.EAST)
                }
                add(row, BorderLayout.CENTER)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BG_ELEVATED
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
                g2.color = BG_BORDER
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 8f, 8f))
            }
        }

    // ─── Apply All panel ──────────────────────────────────────────────────────
    private fun buildApplyAllRow(changes: List<FileChange>) = JPanel(BorderLayout()).apply {
        isOpaque = false; border = JBUI.Borders.emptyTop(10)
        alignmentX = Component.LEFT_ALIGNMENT

        val left = JBLabel("${changes.size} files proposed").apply {
            foreground = Color(63, 185, 80); font = Font("Inter", Font.PLAIN, 11)
        }
        val btn = object : JButton("Apply All ${changes.size} Files") {
            init {
                background = Color(35, 134, 54); foreground = Color.WHITE
                font = Font("Inter", Font.BOLD, 12); border = JBUI.Borders.empty(7, 16)
                isFocusPainted = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { showApplyAllDialog(changes) }
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
                g2.color = foreground; g2.font = font
                val fm = g2.fontMetrics
                g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
            }
        }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false; add(left); add(btn) }
        add(row, BorderLayout.WEST)
    }

    // ─── Diff & apply ─────────────────────────────────────────────────────────
    private fun showDiffAndApply(path: String, code: String, isNew: Boolean) {
        val base = project.basePath ?: return
        val old  = if (isNew) "" else try { File("$base/$path").readText() } catch (_: Exception) { "" }
        val dlg  = DiffPreviewDialog(project, path, old, code, isNew)
        if (dlg.showAndGet()) {
            WriteCommandAction.runWriteCommandAction(project) { applyChangeInternal(path, code, isNew) }
            Messages.showInfoMessage(project, "Applied → $path", "Done")
        }
    }

    private fun applyChangeInternal(path: String, code: String, isNew: Boolean) {
        val base = project.basePath ?: run {
            Messages.showErrorDialog(project, "Project base path is not set.", "Error"); return
        }
        try {
            val file = File("$base/$path")
            if (isNew) {
                file.parentFile?.mkdirs()
                FileUtil.writeToFile(file, code)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            } else {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (vf != null) FileDocumentManager.getInstance().getDocument(vf)?.setText(code)
                else { FileUtil.writeToFile(file, code); LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) }
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Failed to apply $path: ${e.message}", "Error")
        }
    }

    // ─── Apply All dialog ─────────────────────────────────────────────────────
    private fun showApplyAllDialog(changes: List<FileChange>) {
        val checks = changes.map { JCheckBox(it.path, true) }
        val panel  = JPanel(BorderLayout(0, 10)).apply {
            preferredSize = Dimension(640, minOf(440, 90 + changes.size * 46))
            border = JBUI.Borders.empty(16)
            add(JBLabel("<html><b>Review proposed changes</b></html>"), BorderLayout.NORTH)
        }
        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        checks.forEachIndexed { i, check ->
            val ch = changes[i]
            val row = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, 40)
                border = JBUI.Borders.emptyBottom(2); add(check, BorderLayout.CENTER)
                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
                right.add(RoundedLabel(if (ch.isNew) "NEW" else "EDIT").apply {
                    bg = if (ch.isNew) Color(63,185,80,30) else Color(88,166,255,30)
                    fg = if (ch.isNew) Color(63,185,80) else ACCENT_BLUE; padH = 6; padV = 2
                })
                right.add(tinyBtn("Diff") {
                    val old = if (ch.isNew) "" else try { File("${project.basePath ?: ""}/${ch.path}").readText() } catch (_: Exception) { "" }
                    DiffPreviewDialog(project, ch.path, old, ch.content, ch.isNew).show()
                })
                add(right, BorderLayout.EAST)
            }
            list.add(row); if (i < changes.size - 1) list.add(JSeparator())
        }
        panel.add(JBScrollPane(list).apply { border = BorderFactory.createEtchedBorder() }, BorderLayout.CENTER)

        val result = JOptionPane.showConfirmDialog(contentPanel, panel,
            "Apply Changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
        if (result == JOptionPane.OK_OPTION) {
            val selected = changes.filterIndexed { i, _ -> checks[i].isSelected }
            WriteCommandAction.runWriteCommandAction(project) {
                selected.forEach { applyChangeInternal(it.path, it.content, it.isNew) }
            }
            Messages.showInfoMessage(project,
                if (selected.size == changes.size) "All ${selected.size} files applied."
                else "${selected.size} of ${changes.size} files applied.", "Done")
            startAgenticTestLoop(selected.map { it.path }, attempt = 1)
        }
    }

    // ─── Terminal runner ──────────────────────────────────────────────────────
    private fun runTerminalCommand(
        label: String, autoFix: Boolean = true,
        executor: ((String) -> Unit, (TerminalExecutor.CommandResult) -> Unit) -> Unit
    ) {
        SwingUtilities.invokeLater {
            terminalOutput.text = "$ $label\n"
            terminalPanel.isVisible = true; terminalPanel.revalidate(); switchToChat()
        }
        executor(
            { line -> SwingUtilities.invokeLater { terminalOutput.append(line) } },
            { result ->
                SwingUtilities.invokeLater {
                    terminalOutput.append("\n${result.summary}\n")
                    if (!result.isSuccess && autoFix) {
                        val errs = result.output.lines()
                            .filter { it.contains("error", true) || it.contains("FAILED") }
                            .take(30).joinToString("\n")
                        if (errs.isNotBlank()) invokeAutoFix("Fix these errors:\n```\n$errs\n```")
                    }
                }
            }
        )
    }

    private fun invokeAutoFix(prompt: String) {
        if (!inputArea.isEnabled) return
        if (conversationHistory.size >= 100) conversationHistory.subList(0, 40).clear()
        conversationHistory.add(Message("user", prompt))
        appendUserBubble(prompt, 0)
        setInputEnabled(false); stopBtn.isVisible = true; statusLabel.text = "Auto-fixing..."
        val ctx = try { EditorContext.getSmartContext(project, taggedFiles.toList()) } catch (_: Exception) { "" }
        val (stream, reasoning) = appendAiBubble()
        AiService.callAgentChain(
            userMessage = prompt, history = conversationHistory.dropLast(1),
            context = ctx, project = project,
            onStatus    = { s   -> SwingUtilities.invokeLater { statusLabel.text = s } },
            onReasoning = { r   -> SwingUtilities.invokeLater {
                reasoning.text = r; reasoning.parent?.isVisible = true; reasoning.parent?.revalidate()
            }},
            onToken     = { tok -> SwingUtilities.invokeLater { stream.append(tok); scrollToBottom() } },
            onComplete  = { full -> SwingUtilities.invokeLater {
                conversationHistory.add(Message("assistant", full))
                val body = stream.parent as? JPanel
                if (body != null && full.isNotBlank()) {
                    body.removeAll()
                    parseMessageParts(full).forEach { body.add(it) }
                    val ch = parseAllFileChanges(full)
                    if (ch.size >= 2) body.add(buildApplyAllRow(ch))
                    body.revalidate(); body.repaint()
                }
                statusLabel.text = "Ready"; intentBadge.isVisible = false
                stopBtn.isVisible = false; setInputEnabled(true); scrollToBottom()
            }}
        )
    }

    // ─── Agentic test loop ────────────────────────────────────────────────────
    private fun startAgenticTestLoop(changedFiles: List<String>, attempt: Int) {
        if (attempt > 3) {
            appendSystemLine("⚠️ Auto-fix gave up after 3 attempts — manual review needed."); return
        }
        switchToChat()
        appendSystemLine("🤖 Attempt $attempt/3 — running tests...")
        SwingUtilities.invokeLater {
            terminalOutput.text = "=== Test run $attempt/3 ===\n"
            terminalPanel.isVisible = true; terminalPanel.revalidate()
        }
        val onOutput: (String) -> Unit = { line -> SwingUtilities.invokeLater { terminalOutput.append(line) } }
        val onDone: (TerminalExecutor.CommandResult) -> Unit = { result ->
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    appendSystemLine("✅ All tests pass! (attempt $attempt)")
                } else {
                    appendSystemLine("❌ Tests failed ($attempt/3) — AI is fixing...")
                    setInputEnabled(false); stopBtn.isVisible = true
                    val (stream, _) = appendAiBubble()
                    AiService.fixTestFailures(
                        failureOutput = result.output.takeLast(4000),
                        changedFiles  = changedFiles, project = project,
                        onStatus  = { s   -> SwingUtilities.invokeLater { statusLabel.text = s } },
                        onToken   = { tok -> SwingUtilities.invokeLater { stream.append(tok); scrollToBottom() } },
                        onComplete = { full -> SwingUtilities.invokeLater {
                            conversationHistory.add(Message("assistant", full))
                            val body = stream.parent as? JPanel
                            if (body != null && full.isNotBlank()) {
                                body.removeAll(); parseMessageParts(full).forEach { body.add(it) }
                                body.revalidate(); body.repaint()
                            }
                            stopBtn.isVisible = false; setInputEnabled(true)
                            val fixes = parseAllFileChanges(full)
                            if (fixes.isEmpty()) { appendSystemLine("⚠️ No changes returned. Stopping."); return@invokeLater }
                            WriteCommandAction.runWriteCommandAction(project) {
                                fixes.forEach { applyChangeInternal(it.path, it.content, it.isNew) }
                            }
                            appendSystemLine("Applied ${fixes.size} fix(es): ${fixes.joinToString { it.path }}")
                            startAgenticTestLoop((changedFiles + fixes.map { it.path }).distinct(), attempt + 1)
                        }}
                    )
                }
            }
        }
        if (isFlutter) TerminalExecutor.flutterTest(project, onOutput, onDone)
        else           TerminalExecutor.runTests(project, onOutput, onDone)
    }

    private fun appendSystemLine(text: String) {
        SwingUtilities.invokeLater {
            val lbl = JBLabel(text).apply {
                foreground = TEXT_SUBTLE; font = Font("Inter", Font.ITALIC, 11)
                border = JBUI.Borders.empty(2, 42, 2, 0); alignmentX = Component.LEFT_ALIGNMENT
            }
            messagePanel.add(lbl); messagePanel.revalidate(); messagePanel.repaint(); scrollToBottom()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun updateProjectBadge() {
        val info = ProjectTypeDetector.detect(project)
        projectBadge.text = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER        -> "Flutter · Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android · ${info.mainLanguage}"
            ProjectTypeDetector.ProjectType.UNKNOWN        -> ""
        }
    }

    private fun setInputEnabled(enabled: Boolean) { inputArea.isEnabled = enabled }

    private fun scrollToBottom() {
        val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, messagePanel) as? JScrollPane
        SwingUtilities.invokeLater { sp?.verticalScrollBar?.value = sp?.verticalScrollBar?.maximum ?: 0 }
    }

    private fun switchToChat(initial: String? = null) {
        cardLayout.show(contentPanel, "CHAT")
        if (initial != null) { inputArea.text = initial; inputArea.caretPosition = initial.length }
        inputArea.requestFocusInWindow()
    }

    private fun resetSession() {
        AiService.stop(); conversationHistory.clear(); messagePanel.removeAll()
        taggedFiles.clear(); attachBar.removeAll(); pendingImages.clear()
        terminalPanel.isVisible = false; terminalOutput.text = ""
        ProjectTypeDetector.invalidate(project); ProjectDependencyAnalyzer.invalidate(project)
        ProjectIndexer.invalidate(project); CodebaseGraph.invalidate(project)
        updateProjectBadge(); ProjectIndexer.warmUp(project)
        cardLayout.show(contentPanel, "WELCOME")
        messagePanel.revalidate(); messagePanel.repaint()
    }

    private fun resizeImage(src: BufferedImage, max: Int): BufferedImage {
        if (src.width <= max && src.height <= max) return src
        val scale = max.toDouble() / maxOf(src.width, src.height)
        val nw = (src.width * scale).toInt(); val nh = (src.height * scale).toInt()
        val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().also { g ->
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, nw, nh, null); g.dispose()
        }
        return out
    }
    private fun encodeBase64(img: BufferedImage): String {
        val b = ByteArrayOutputStream(); ImageIO.write(img, "PNG", b)
        return Base64.getEncoder().encodeToString(b.toByteArray())
    }

    // ─── Data / parsing ───────────────────────────────────────────────────────
    private data class FileChange(val path: String, val content: String, val isNew: Boolean)

    private fun parseAllFileChanges(response: String): List<FileChange> {
        val list = mutableListOf<FileChange>()
        RE_FILE_CHANGE.findAll(response).forEach { list.add(FileChange(it.groupValues[1].trim(), it.groupValues[2].trim(), false)) }
        RE_NEW_FILE.findAll(response).forEach    { list.add(FileChange(it.groupValues[1].trim(), it.groupValues[2].trim(), true))  }
        return list
    }

    // ─── Widget helpers ───────────────────────────────────────────────────────
    private fun iconBtn(icon: javax.swing.Icon, tip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tip; isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); addActionListener { action() }
    }
    private fun tinyBtn(label: String, action: () -> Unit) = JButton(label).apply {
        isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        foreground = TEXT_MUTED; font = Font("Inter", Font.PLAIN, 11)
        border = JBUI.Borders.empty(3, 6); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }
    private fun pill(label: String, bg: Color) = object : JButton(label) {
        init {
            isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
            foreground = Color.WHITE; font = Font("Inter", Font.BOLD, 11)
            border = JBUI.Borders.empty(4, 10); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bg
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
            super.paintComponent(g)
        }
    }
    private fun terminalChip(label: String, action: () -> Unit) = object : JButton(label) {
        init {
            isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
            foreground = TEXT_MUTED; font = Font("Inter", Font.PLAIN, 10)
            border = JBUI.Borders.empty(3, 8); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (mousePosition != null) BG_SURFACE else BG_ELEVATED
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f))
            super.paintComponent(g)
        }
    }

    // ─── Rounded label component ──────────────────────────────────────────────
    inner class RoundedLabel(text: String) : JComponent() {
        var text: String = text; set(v) { field = v; repaint() }
        var bg: Color = BG_ELEVATED; var fg: Color = TEXT_MUTED
        var padH: Int = 8; var padV: Int = 3
        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font ?: Font("Inter", Font.BOLD, 9))
            return Dimension(fm.stringWidth(text) + padH * 2, fm.height + padV * 2)
        }
        override fun paintComponent(g: Graphics) {
            if (text.isBlank()) return
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bg
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
            g2.color = fg; g2.font = font ?: Font("Inter", Font.BOLD, 9)
            val fm = g2.fontMetrics
            g2.drawString(text, padH, padV + fm.ascent)
        }
    }

    companion object {
        const val CLIENT_KEY = "CoforgeAiChatContent"

        // Color system — GitHub dark palette
        val BG_BASE     = Color(13,  17,  23)   // deepest background
        val BG_ELEVATED = Color(22,  27,  34)   // cards, panels
        val BG_SURFACE  = Color(33,  38,  45)   // raised surfaces
        val BG_INPUT    = Color(22,  27,  34)   // input box
        val BG_BORDER   = Color(48,  54,  61)   // borders & dividers
        val TEXT_PRIMARY = Color(230, 237, 243)  // main text
        val TEXT_MUTED  = Color(125, 133, 144)  // secondary text
        val TEXT_SUBTLE = Color(72,  79,  88)   // placeholder / hints
        val ACCENT_BLUE   = Color(88,  166, 255)
        val ACCENT_GREEN  = Color(63,  185, 80)
        val ACCENT_PURPLE = Color(188, 140, 255)
        val ACCENT_ORANGE = Color(227, 179,  65)
        val USER_BUBBLE   = Color(31,  35,  40)  // user message bg

        // Compiled regex — reused across all messages
        val RE_IMAGE_SUFFIX = Regex(""" \[\+\d+ image\(s\)\]""")
        val RE_FILE_CHANGE  = Regex("""<file_change\s+path="([^"]+)">([\s\S]*?)</file_change>""")
        val RE_NEW_FILE     = Regex("""<new_file\s+path="([^"]+)">([\s\S]*?)</new_file>""")
    }
}
