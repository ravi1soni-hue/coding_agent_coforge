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
import javax.swing.TransferHandler

class ChatToolWindowContent(private val project: Project) {

    val contentPanel = JPanel(CardLayout())
    private val cardLayout = contentPanel.layout as CardLayout

    private val chatPanel    = JPanel(BorderLayout())
    private val welcomePanel = JPanel(GridBagLayout())

    private val messagePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = BG_DEEP; border = JBUI.Borders.empty(16)
    }

    // Input area — auto-expands up to 8 rows for large task descriptions
    private val inputArea = JBTextArea(3, 20).apply {
        lineWrap = true; wrapStyleWord = true
        background = BG_SURFACE; foreground = Color.WHITE
        caretColor = ACCENT_BLUE
        font = Font("Inter", Font.PLAIN, 13)
        margin = JBUI.insets(12); border = BorderFactory.createEmptyBorder()
    }

    private val statusLabel = JBLabel("Ready").apply {
        foreground = Color(139, 148, 158); font = font.deriveFont(11f)
    }
    private val intentLabel = JBLabel("").apply {
        foreground = ACCENT_BLUE; font = font.deriveFont(Font.BOLD, 10f); isVisible = false
    }
    private val stopBtn = JButton("■ Stop").apply {
        background = Color(248, 81, 73); foreground = Color.WHITE
        border = JBUI.Borders.empty(5, 10); isFocusPainted = false; isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { AiService.stop(); isVisible = false; statusLabel.text = "Stopped" }
    }

    // File @-tags
    private val taggedFiles = mutableSetOf<VirtualFile>()
    private val taggedPillsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 3)).apply { isOpaque = false }

    // Image attachments — (base64, thumbnail, index)
    private val pendingImages = mutableListOf<Triple<String, ImageIcon, Int>>()
    private var imageIndex = 0
    private val imagePillsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { isOpaque = false }

    // Conversation memory
    private val conversationHistory = mutableListOf<Message>()

    // Project type badge shown in header
    private val projectTypeBadge = JBLabel("").apply {
        foreground = Color(139, 148, 158); font = font.deriveFont(Font.BOLD, 9f)
    }

    // Terminal panel — shows Gradle/Flutter command output
    private val terminalOutput = JTextArea().apply {
        isEditable = false; background = Color(10, 12, 16); foreground = Color(200, 210, 200)
        font = Font("JetBrains Mono", Font.PLAIN, 11); margin = JBUI.insets(8)
    }
    private val terminalPanel = JPanel(BorderLayout()).apply {
        background = Color(10, 12, 16); isVisible = false
        preferredSize = Dimension(0, 180); maximumSize = Dimension(Int.MAX_VALUE, 300)
        val header = JPanel(BorderLayout()).apply {
            background = Color(22, 27, 34); border = JBUI.Borders.empty(4, 10)
            add(JBLabel("Terminal").apply { foreground = Color(139, 148, 158); font = font.deriveFont(Font.BOLD, 10f) }, BorderLayout.WEST)
            add(JButton("✕").apply {
                isContentAreaFilled = false; isBorderPainted = false; foreground = Color(139, 148, 158)
                font = font.deriveFont(11f); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { this@apply.parent.parent.isVisible = false }
            }, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(terminalOutput).apply { border = null }, BorderLayout.CENTER)
    }

    // Detect project type once for button routing
    private val isFlutter get() = ProjectTypeDetector.detect(project).type == ProjectTypeDetector.ProjectType.FLUTTER

    init {
        updateProjectBadge()
        setupWelcomeUI()
        setupChatUI()
        setupTaggingSupport()
        setupImageSupport()
        contentPanel.add(welcomePanel, "WELCOME")
        contentPanel.add(chatPanel, "CHAT")
        cardLayout.show(contentPanel, "WELCOME")
        // Warm up index and graph in background so they're ready on first query
        ProjectIndexer.warmUp(project)
        Thread { CodebaseGraph.build(project) }.apply { isDaemon = true; name = "CoforgeGraphWarmup" }.start()
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun prefillAndSend(prompt: String) {
        switchToChat(); inputArea.text = prompt; sendMessage()
    }

    // ─── Project type badge ───────────────────────────────────────────────────

    private fun updateProjectBadge() {
        val info = ProjectTypeDetector.detect(project)
        projectTypeBadge.text = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER        -> "Flutter · Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android · ${info.mainLanguage}"
            ProjectTypeDetector.ProjectType.UNKNOWN        -> ""
        }
    }

    // ─── Welcome ──────────────────────────────────────────────────────────────

    private fun setupWelcomeUI() {
        welcomePanel.background = BG_DEEP
        val c = GridBagConstraints().apply {
            gridx = 0; gridy = GridBagConstraints.RELATIVE
            insets = JBUI.insets(6); anchor = GridBagConstraints.CENTER
        }
        welcomePanel.add(JBLabel("Coforge AI Agent").apply { foreground = Color.WHITE; font = font.deriveFont(Font.BOLD, 22f) }, c)
        welcomePanel.add(projectTypeBadge, c)
        welcomePanel.add(JBLabel("Kimi  ·  Gemini  ·  GPT  ·  Vision").apply { foreground = ACCENT_BLUE; font = font.deriveFont(11f) }, c)

        val cards = JPanel(GridLayout(2, 2, 12, 12)).apply {
            isOpaque = false; border = JBUI.Borders.empty(24, 16)
            add(modeCard("💡 Explain",        "What does this code do?")                  { switchToChat("Explain: ") })
            add(modeCard("🐛 Fix Bug",         "Debug and fix issues.")                   { switchToChat("Fix the bug in:\n\n") })
            add(modeCard("🏗️ Build Feature",  "Implement from scratch.")                  { switchToChat("Implement: ") })
            add(modeCard("🧪 Write Tests",     "Generate comprehensive tests.")           { switchToChat("Write tests for:\n\n") })
        }
        welcomePanel.add(cards, c)
        welcomePanel.add(JBLabel("Paste screenshots · Drop image files · @-tag source files").apply {
            foreground = Color(88, 88, 100); font = font.deriveFont(10f)
        }, c)
    }

    private fun modeCard(title: String, desc: String, action: () -> Unit): JPanel {
        val bg = BG_SURFACE
        return object : JPanel(BorderLayout()) {
            init {
                background = bg; border = JBUI.Borders.empty(14); preferredSize = Dimension(160, 90)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(JBLabel(title).apply { foreground = Color.WHITE; font = font.deriveFont(Font.BOLD, 13f) }, BorderLayout.NORTH)
                add(JTextArea(desc).apply {
                    foreground = Color(139, 148, 158); isEditable = false; isOpaque = false
                    lineWrap = true; wrapStyleWord = true; font = Font("Inter", Font.PLAIN, 11)
                }, BorderLayout.CENTER)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = action()
                    override fun mouseEntered(e: MouseEvent) { background = bg.brighter(); repaint() }
                    override fun mouseExited(e: MouseEvent)  { background = bg; repaint() }
                })
            }
            override fun paintComponent(g: Graphics) {
                (g.create() as Graphics2D).also { g2 ->
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = background
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f))
                    g2.dispose()
                }
            }
        }
    }

    // ─── Chat screen ──────────────────────────────────────────────────────────

    private fun setupChatUI() {
        chatPanel.background = BG_DEEP

        val header = JPanel(BorderLayout()).apply {
            background = BG_SURFACE
            border = JBUI.Borders.compound(BorderFactory.createMatteBorder(0,0,1,0, BG_BORDER), JBUI.Borders.empty(8, 16))
            val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(JBLabel("COFORGE AI").apply { foreground = Color.WHITE; font = font.deriveFont(Font.BOLD, 11f) })
                add(projectTypeBadge)
            }
            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                // Tests button → full agentic loop (run → fail → fix → re-run, up to 3×)
                add(createTerminalBtn("▶ Tests", "Run tests + auto-fix failures (up to 3 attempts)") {
                    startAgenticTestLoop(emptyList(), attempt = 1)
                })
                // Build button → run and auto-invoke AI on failure
                add(createTerminalBtn("⚙ Build", "Build debug (auto-fix on error)") {
                    runTerminalCommand("Building...") { out, done ->
                        if (isFlutter) TerminalExecutor.flutterBuild(project, out, done)
                        else TerminalExecutor.buildDebug(project, out, done)
                    }
                })
                // Lint button → run and auto-invoke AI on failure
                add(createTerminalBtn("🔍 Lint", "Run lint / analyze (auto-fix on error)") {
                    runTerminalCommand("Analyzing...") { out, done ->
                        if (isFlutter) TerminalExecutor.flutterAnalyze(project, out, done)
                        else TerminalExecutor.runLint(project, out, done)
                    }
                })
                add(createIconBtn(AllIcons.Actions.Refresh, "New conversation") { resetSession() })
                add(createIconBtn(AllIcons.General.Settings, "Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AppSettingsConfigurable::class.java)
                })
            }
            add(titleRow, BorderLayout.WEST); add(actions, BorderLayout.EAST)
        }

        val scroll = JBScrollPane(messagePanel).apply {
            border = null; viewport.background = BG_DEEP; verticalScrollBar.unitIncrement = 16
        }

        val footer = JPanel(BorderLayout()).apply {
            background = BG_DEEP; border = JBUI.Borders.empty(10, 14)
            val inputContainer = JPanel(BorderLayout()).apply {
                background = BG_SURFACE; border = BorderFactory.createLineBorder(BG_BORDER, 1)

                // Top: pills row (tags + images)
                val pillsRow = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(taggedPillsPanel, BorderLayout.WEST)
                    add(imagePillsPanel, BorderLayout.CENTER)
                }
                add(pillsRow, BorderLayout.NORTH)

                // Middle: auto-expanding textarea wrapped in scroll
                val inputScroll = JBScrollPane(inputArea).apply {
                    border = null; viewport.background = BG_SURFACE
                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    preferredSize = Dimension(0, 80); maximumSize = Dimension(Int.MAX_VALUE, 200)
                }
                add(inputScroll, BorderLayout.CENTER)

                // Bottom: status + actions
                val controls = JPanel(BorderLayout()).apply {
                    background = BG_SURFACE; border = JBUI.Borders.empty(4, 8, 6, 8)
                    val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                        isOpaque = false; add(statusLabel); add(intentLabel); add(stopBtn)
                    }
                    val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                        isOpaque = false
                        add(createIconBtn(AllIcons.General.Add, "Attach image (or paste)") { openImagePicker() })
                        add(createIconBtn(AllIcons.Actions.MoveUp, "Send (Enter)") { sendMessage() }.apply {
                            isOpaque = true; background = Color(35, 134, 54)
                        })
                    }
                    add(left, BorderLayout.WEST); add(right, BorderLayout.EAST)
                }
                add(controls, BorderLayout.SOUTH)
            }
            add(inputContainer, BorderLayout.CENTER)
        }

        // Center area: messages + collapsible terminal panel
        val centerStack = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(scroll, BorderLayout.CENTER)
            add(terminalPanel, BorderLayout.SOUTH)
        }

        chatPanel.add(header, BorderLayout.NORTH)
        chatPanel.add(centerStack, BorderLayout.CENTER)
        chatPanel.add(footer, BorderLayout.SOUTH)
    }

    // ─── Terminal command runner ───────────────────────────────────────────────

    /**
     * Runs a terminal command and streams output to the terminal panel.
     * On failure, automatically invokes the AI FIX chain — no button click needed.
     * autoFix=false for commands where we only want to show output (e.g. successful builds).
     */
    private fun runTerminalCommand(
        label: String,
        autoFix: Boolean = true,
        executor: (onOutput: (String) -> Unit, onDone: (TerminalExecutor.CommandResult) -> Unit) -> Unit
    ) {
        SwingUtilities.invokeLater {
            terminalOutput.text = "$ $label\n"
            terminalPanel.isVisible = true
            terminalPanel.revalidate()
            switchToChat()
        }
        executor(
            { line -> SwingUtilities.invokeLater { terminalOutput.append(line) } },
            { result ->
                SwingUtilities.invokeLater {
                    terminalOutput.append("\n${result.summary}\n")
                    if (!result.isSuccess && autoFix) {
                        val errors = result.output.lines()
                            .filter { it.contains("error", ignoreCase = true) || it.contains("FAILED", ignoreCase = true) }
                            .take(30).joinToString("\n")
                        if (errors.isNotBlank()) {
                            // Auto-invoke AI — no button needed
                            invokeAutoFix("Fix these build errors:\n```\n$errors\n```")
                        }
                    }
                }
            }
        )
    }

    /**
     * Directly invokes the AI FIX chain without touching the input area.
     * Used by auto-fix paths (terminal failures, build errors).
     * The response is streamed into the chat panel like a normal agent response.
     */
    private fun invokeAutoFix(prompt: String) {
        if (!inputArea.isEnabled) return   // already processing another request
        if (conversationHistory.size >= 100) conversationHistory.subList(0, 40).clear()
        conversationHistory.add(Message("user", prompt))
        appendBubble("You", prompt)
        setInputEnabled(false); stopBtn.isVisible = true
        statusLabel.text = "Auto-fixing..."

        val context = try { EditorContext.getSmartContext(project, taggedFiles.toList()) } catch (_: Exception) { "" }
        val (streamArea, reasoningArea) = appendAgentBubble()

        AiService.callAgentChain(
            userMessage = prompt,
            history     = conversationHistory.dropLast(1),
            context     = context,
            project     = project,
            onStatus    = { s   -> SwingUtilities.invokeLater { statusLabel.text = s } },
            onReasoning = { r   -> SwingUtilities.invokeLater {
                reasoningArea.text = r
                reasoningArea.parent?.isVisible = true
                reasoningArea.parent?.revalidate()
            }},
            onToken     = { tok -> SwingUtilities.invokeLater { streamArea.append(tok); scrollToBottom() } },
            onComplete  = { full -> SwingUtilities.invokeLater {
                conversationHistory.add(Message("assistant", full))
                val bodyPanel = streamArea.parent as? JPanel
                if (bodyPanel != null && full.isNotBlank()) {
                    bodyPanel.removeAll()
                    parseMessageParts(full).forEach { bodyPanel.add(it) }
                    val allChanges = parseAllFileChanges(full)
                    if (allChanges.size >= 2) bodyPanel.add(createApplyAllButton(allChanges))
                    bodyPanel.revalidate(); bodyPanel.repaint()
                }
                statusLabel.text = "Ready"; intentLabel.isVisible = false
                stopBtn.isVisible = false; setInputEnabled(true); scrollToBottom()
            }}
        )
    }

    private fun createTerminalBtn(text: String, tooltip: String, action: () -> Unit) = JButton(text).apply {
        toolTipText = tooltip
        background = Color(33, 38, 45); foreground = Color(139, 148, 158)
        font = font.deriveFont(Font.BOLD, 10f); border = JBUI.Borders.empty(4, 8)
        isFocusPainted = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    // ─── @-tagging ────────────────────────────────────────────────────────────

    private fun setupTaggingSupport() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) { if (e.keyChar == '@') showFilePicker() }
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); sendMessage() }
            }
        })
    }

    private fun showFilePicker() {
        val files = EditorContext.getAllProjectFiles(project)
        val names = files.map { it.name }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("Tag a file")
            .setItemChosenCallback { name ->
                val f = files.find { it.name == name } ?: return@setItemChosenCallback
                addTagPill(f)
                val t = inputArea.text
                val at = t.lastIndexOf('@')
                if (at >= 0) inputArea.text = t.substring(0, at)
            }
            .createPopup()
            .showUnderneathOf(inputArea)
    }

    private fun addTagPill(file: VirtualFile) {
        if (!taggedFiles.add(file)) return
        val pill = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
            background = Color(33, 38, 45); border = BorderFactory.createLineBorder(BG_BORDER, 1)
            add(JBLabel(file.name).apply { foreground = ACCENT_BLUE; font = font.deriveFont(10f) })
            add(JBLabel(AllIcons.Actions.Close).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        taggedFiles.remove(file); taggedPillsPanel.remove(parent)
                        taggedPillsPanel.revalidate(); taggedPillsPanel.repaint()
                    }
                })
            })
        }
        taggedPillsPanel.add(pill); taggedPillsPanel.revalidate(); taggedPillsPanel.repaint()
    }

    // ─── Image support (paste, drag-drop, file picker) ────────────────────────

    private fun setupImageSupport() {
        // Ctrl+V paste — intercept images from clipboard
        val originalPasteAction = inputArea.actionMap.get("paste")
        inputArea.actionMap.put("paste", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val contents = clipboard.getContents(null)
                when {
                    contents?.isDataFlavorSupported(DataFlavor.imageFlavor) == true -> {
                        val image = contents.getTransferData(DataFlavor.imageFlavor) as? BufferedImage
                        if (image != null) addImageAttachment(image, "Pasted Image")
                    }
                    else -> originalPasteAction?.actionPerformed(e) // normal text paste
                }
            }
        })

        // Drag-and-drop image files onto the input area
        inputArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                       support.isDataFlavorSupported(DataFlavor.imageFlavor)
            }
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                return try {
                    when {
                        support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                            @Suppress("UNCHECKED_CAST")
                            val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                            files.filter { it.isImageFile() }.forEach { f ->
                                ImageIO.read(f)?.let { addImageAttachment(it, f.name) }
                            }; true
                        }
                        support.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                            val img = support.transferable.getTransferData(DataFlavor.imageFlavor) as? BufferedImage
                            img?.let { addImageAttachment(it, "Dropped Image") }; true
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
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "webp")
            isMultiSelectionEnabled = true
        }
        if (fc.showOpenDialog(contentPanel) == JFileChooser.APPROVE_OPTION) {
            fc.selectedFiles.forEach { f -> ImageIO.read(f)?.let { addImageAttachment(it, f.name) } }
        }
    }

    private fun addImageAttachment(image: BufferedImage, name: String) {
        val resized = resizeImage(image, maxDim = 1024)
        val base64  = encodeToBase64(resized)
        val thumb   = ImageIcon(resized.getScaledInstance(48, 48, Image.SCALE_SMOOTH))
        val idx     = ++imageIndex

        pendingImages.add(Triple(base64, thumb, idx))

        val pill = JPanel(BorderLayout()).apply {
            background = Color(33, 38, 45)
            border = BorderFactory.createLineBorder(BG_BORDER, 1)
            preferredSize = Dimension(64, 64); maximumSize = Dimension(64, 64)
            add(JBLabel(thumb), BorderLayout.CENTER)

            // Close button overlay
            val close = JBLabel(AllIcons.Actions.Close).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        pendingImages.removeIf { it.third == idx }
                        imagePillsPanel.remove(parent); imagePillsPanel.revalidate(); imagePillsPanel.repaint()
                    }
                })
            }
            add(close, BorderLayout.NORTH)
            toolTipText = name
        }
        imagePillsPanel.add(pill); imagePillsPanel.revalidate(); imagePillsPanel.repaint()
        switchToChat()
    }

    private fun resizeImage(src: BufferedImage, maxDim: Int): BufferedImage {
        val w = src.width; val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val scale = maxDim.toDouble() / maxOf(w, h)
        val nw = (w * scale).toInt(); val nh = (h * scale).toInt()
        val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().also { g ->
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, nw, nh, null); g.dispose()
        }
        return out
    }

    private fun encodeToBase64(image: BufferedImage): String {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    private fun File.isImageFile() = extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    // ─── Send ─────────────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = inputArea.text.trim()
        val images = pendingImages.map { it.first }
        if (text.isEmpty() && images.isEmpty()) return

        switchToChat(); inputArea.text = ""; setInputEnabled(false); stopBtn.isVisible = true

        // Clear image pills
        val imageCount = pendingImages.size
        pendingImages.clear(); imagePillsPanel.removeAll()
        imagePillsPanel.revalidate(); imagePillsPanel.repaint()

        val intent = AiService.detectIntent(text)
        intentLabel.text = when (intent) {
            Intent.EXPLAIN      -> "⚡ FAST"
            Intent.FIX          -> "🔧 FIX"
            Intent.CODE_SIMPLE  -> "✏️ EDIT"
            Intent.CODE_COMPLEX -> "🏗️ FULL"
        }
        intentLabel.isVisible = true

        // Keep history bounded — older messages are still summarized in the context window
        if (conversationHistory.size >= 100) conversationHistory.subList(0, 40).clear()
        conversationHistory.add(Message("user", text + if (imageCount > 0) " [+$imageCount image(s)]" else ""))
        appendBubble("You", text, imageCount = imageCount)

        val context = EditorContext.getSmartContext(project, taggedFiles.toList())
        val (streamArea, reasoningArea) = appendAgentBubble()

        AiService.callAgentChain(
            userMessage     = text,
            history         = conversationHistory.dropLast(1),
            context         = context,
            images          = images,
            project         = project,
            onStatus        = { s   -> SwingUtilities.invokeLater { statusLabel.text = s } },
            onReasoning     = { r   -> SwingUtilities.invokeLater {
                reasoningArea.text = r
                reasoningArea.parent?.isVisible = true
                reasoningArea.parent?.revalidate()
            }},
            onToken         = { tok -> SwingUtilities.invokeLater { streamArea.append(tok); scrollToBottom() } },
            onComplete      = { full -> SwingUtilities.invokeLater {
                conversationHistory.add(Message("assistant", full))
                val bodyPanel = streamArea.parent as? JPanel
                if (bodyPanel != null && full.isNotBlank()) {
                    bodyPanel.removeAll()
                    parseMessageParts(full).forEach { bodyPanel.add(it) }
                    // "Apply All Changes" button — appears when AI proposes 2+ file changes
                    val allChanges = parseAllFileChanges(full)
                    if (allChanges.size >= 2) {
                        bodyPanel.add(createApplyAllButton(allChanges))
                    }
                    bodyPanel.revalidate(); bodyPanel.repaint()
                }
                statusLabel.text = "Ready"; intentLabel.isVisible = false
                stopBtn.isVisible = false; setInputEnabled(true); scrollToBottom()
            }}
        )
    }

    // ─── Bubble rendering ─────────────────────────────────────────────────────

    private fun appendBubble(sender: String, text: String, imageCount: Int = 0) {
        val isUser = sender == "You"
        val bubble = JPanel().apply {
            layout = BorderLayout(); isOpaque = false; border = JBUI.Borders.emptyBottom(14)
            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                add(JBLabel(sender).apply {
                    foreground = Color(139, 148, 158); font = font.deriveFont(Font.BOLD, 10f)
                    alignmentX = if (isUser) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(3)
                })
                val body = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = if (isUser) Color(35, 134, 54, 50) else BG_SURFACE
                    border = JBUI.Borders.empty(10, 14)
                    if (text.isNotBlank()) parseMessageParts(text).forEach { add(it) }
                    if (imageCount > 0) add(JBLabel("📎 $imageCount image(s) attached").apply {
                        foreground = ACCENT_BLUE; font = font.deriveFont(10f); border = JBUI.Borders.emptyTop(4)
                    })
                }
                add(body)
            }
            if (isUser) { add(Box.createHorizontalStrut(60), BorderLayout.WEST); add(inner, BorderLayout.CENTER) }
            else        { add(inner, BorderLayout.CENTER); add(Box.createHorizontalStrut(60), BorderLayout.EAST) }
        }
        messagePanel.add(bubble); messagePanel.revalidate(); scrollToBottom()
    }

    private fun appendAgentBubble(): Pair<JTextArea, JTextArea> {
        val streamArea = JTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            background = BG_SURFACE; foreground = Color.WHITE
            font = Font("Inter", Font.PLAIN, 13); border = BorderFactory.createEmptyBorder()
        }
        val reasoningContent = JTextArea().apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            background = Color(22, 27, 34); foreground = Color(139, 148, 158)
            font = Font("JetBrains Mono", Font.PLAIN, 10); border = JBUI.Borders.empty(6, 8)
            isVisible = false
        }
        val reasoningPanel = JPanel(BorderLayout()).apply {
            background = Color(22, 27, 34)
            border = BorderFactory.createLineBorder(BG_BORDER, 1)
            isVisible = false
            val toggleBtn = JButton("▶ Thinking").apply {
                isContentAreaFilled = false; isBorderPainted = false
                foreground = Color(139, 148, 158); font = font.deriveFont(Font.BOLD, 10f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    reasoningContent.isVisible = !reasoningContent.isVisible
                    text = if (reasoningContent.isVisible) "▼ Thinking" else "▶ Thinking"
                    revalidate()
                }
            }
            add(toggleBtn, BorderLayout.NORTH); add(reasoningContent, BorderLayout.CENTER)
        }

        val bodyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = BG_SURFACE; border = JBUI.Borders.empty(10, 14)
            alignmentX = Component.LEFT_ALIGNMENT; add(streamArea)
        }
        val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(createTextBtn("Copy") {
                val c = conversationHistory.lastOrNull { it.role == "assistant" }?.content ?: ""
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c), null)
            })
            add(createTextBtn("Retry") {
                val lastUser = conversationHistory.lastOrNull { it.role == "user" }?.content ?: return@createTextBtn
                conversationHistory.removeLastOrNull(); conversationHistory.removeLastOrNull()
                inputArea.text = lastUser.removeSuffix(RE_IMAGE_SUFFIX.find(lastUser)?.value ?: "")
                sendMessage()
            })
        }

        val bubble = JPanel().apply {
            layout = BorderLayout(); isOpaque = false; border = JBUI.Borders.emptyBottom(14)
            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                add(JBLabel("Coforge AI").apply {
                    foreground = Color(139, 148, 158); font = font.deriveFont(Font.BOLD, 10f)
                    alignmentX = Component.LEFT_ALIGNMENT; border = JBUI.Borders.emptyBottom(3)
                })
                add(reasoningPanel); add(Box.createVerticalStrut(6)); add(bodyPanel); add(actionBar)
            }
            add(inner, BorderLayout.CENTER); add(Box.createHorizontalStrut(60), BorderLayout.EAST)
        }
        messagePanel.add(bubble); messagePanel.revalidate()
        return streamArea to reasoningContent
    }

    // ─── Message parsing ──────────────────────────────────────────────────────

    private fun parseMessageParts(message: String): List<Component> {
        val components = mutableListOf<Component>()
        val combined = Pattern.compile(
            "(```[\\w]*\\n[\\s\\S]+?\\n```|<(?:file_change|new_file) path=\".+?\">[\\s\\S]+?</(?:file_change|new_file)>)"
        )
        val codePattern   = Pattern.compile("```(\\w*)\\n([\\s\\S]+?)\\n```")
        val changePattern = Pattern.compile("<(file_change|new_file) path=\"(.+?)\">([\\s\\S]+?)</(file_change|new_file)>")

        var lastIdx = 0
        val matcher = combined.matcher(message)
        while (matcher.find()) {
            val prose = message.substring(lastIdx, matcher.start()).trim()
            if (prose.isNotEmpty()) components.add(createMarkdownBlock(prose))

            val block = matcher.group()
            when {
                block.startsWith("```") -> {
                    val m = codePattern.matcher(block)
                    if (m.find()) components.add(createCodeBlock(m.group(2), m.group(1).ifEmpty { "kotlin" }))
                }
                else -> {
                    val m = changePattern.matcher(block)
                    if (m.find()) components.add(createChangeProposal(m.group(2), m.group(3), m.group(1) == "new_file"))
                }
            }
            lastIdx = matcher.end()
        }
        val tail = message.substring(lastIdx).trim()
        if (tail.isNotEmpty()) components.add(createMarkdownBlock(tail))
        return components
    }

    private fun createMarkdownBlock(text: String) =
        MarkdownRenderer.createComponent(text, BG_SURFACE).apply {
            alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

    private fun createCodeBlock(code: String, lang: String) = JPanel(BorderLayout()).apply {
        background = Color(13, 17, 23); border = JBUI.Borders.emptyBottom(10)
        alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 280)
        val header = JPanel(BorderLayout()).apply {
            background = Color(33, 38, 45); border = JBUI.Borders.empty(4, 10)
            add(JBLabel(lang.uppercase()).apply { foreground = Color(139, 148, 158); font = font.deriveFont(Font.BOLD, 10f) }, BorderLayout.WEST)
            add(createIconBtn(AllIcons.Actions.Copy, "Copy") {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
            }, BorderLayout.EAST)
        }
        val area = JTextArea(code).apply {
            background = Color(13, 17, 23); foreground = Color(173, 186, 199)
            font = Font("JetBrains Mono", Font.PLAIN, 12); isEditable = false; margin = JBUI.insets(10)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(area).apply { border = null }, BorderLayout.CENTER)
    }

    private fun createChangeProposal(path: String, code: String, isNew: Boolean) = JPanel(BorderLayout()).apply {
        background = Color(22, 38, 22)
        border = JBUI.Borders.emptyBottom(8)
        alignmentX = Component.LEFT_ALIGNMENT
        val inner = JPanel(BorderLayout()).apply {
            background = Color(22, 38, 22)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(35, 134, 54), 1), JBUI.Borders.empty(10, 14))
            add(JBLabel("${if (isNew) "NEW FILE" else "MODIFY"}: $path").apply {
                foreground = Color(63, 185, 80); font = font.deriveFont(Font.BOLD, 12f)
            }, BorderLayout.WEST)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(createTextBtn("Skip") { this@apply.isVisible = false; this@apply.revalidate() })
                add(JButton("Preview & Apply").apply {
                    background = Color(35, 134, 54); foreground = Color.WHITE
                    border = JBUI.Borders.empty(6, 14); isFocusPainted = false
                    addActionListener { showDiffAndApply(path, code, isNew) }
                })
            }
            add(btns, BorderLayout.EAST)
        }
        add(inner, BorderLayout.CENTER)
    }

    // ─── Diff & apply ─────────────────────────────────────────────────────────

    private fun showDiffAndApply(path: String, newCode: String, isNew: Boolean) {
        val base = project.basePath ?: return
        val oldContent = if (isNew) "" else try { File("$base/$path").readText() } catch (_: Exception) { "" }
        val dialog = DiffPreviewDialog(project, path, oldContent, newCode, isNew)
        if (dialog.showAndGet()) {
            WriteCommandAction.runWriteCommandAction(project) { applyChangeInternal(path, newCode, isNew) }
            Messages.showInfoMessage(project, "Applied → $path", "Done")
        }
    }

    // Called both from single-file apply and "Apply All" batch (already inside WriteCommandAction)
    private fun applyChangeInternal(path: String, code: String, isNew: Boolean) {
        val base = project.basePath ?: run {
            Messages.showErrorDialog(project, "Project base path is not set.", "Error")
            return
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun createIconBtn(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip; isContentAreaFilled = false; isBorderPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); addActionListener { action() }
    }

    private fun createTextBtn(text: String, action: () -> Unit) = JButton(text).apply {
        isContentAreaFilled = false; foreground = Color(139, 148, 158); font = font.deriveFont(10f)
        border = JBUI.Borders.empty(2, 6); isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); addActionListener { action() }
    }

    // ─── Multi-file change helpers ────────────────────────────────────────────

    private data class FileChange(val path: String, val content: String, val isNew: Boolean)

    private fun parseAllFileChanges(response: String): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        RE_FILE_CHANGE.findAll(response).forEach { m ->
            changes.add(FileChange(m.groupValues[1].trim(), m.groupValues[2].trim(), false))
        }
        RE_NEW_FILE.findAll(response).forEach { m ->
            changes.add(FileChange(m.groupValues[1].trim(), m.groupValues[2].trim(), true))
        }
        return changes
    }

    private fun createApplyAllButton(changes: List<FileChange>) = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        isOpaque = false; border = JBUI.Borders.emptyTop(8)
        alignmentX = Component.LEFT_ALIGNMENT

        add(JButton("📦 Apply All ${changes.size} Files").apply {
            background = Color(35, 134, 54); foreground = Color.WHITE
            border = JBUI.Borders.empty(8, 16); isFocusPainted = false; font = font.deriveFont(Font.BOLD, 12f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                // Show confirmation list dialog
                showApplyAllDialog(changes)
            }
        })
        add(JBLabel("${changes.size} files ready to apply").apply {
            foreground = Color(63, 185, 80); font = font.deriveFont(11f)
        })
    }

    private fun showApplyAllDialog(changes: List<FileChange>) {
        val checks = changes.map { JCheckBox(it.path, true) }

        val panel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(640, minOf(420, 80 + changes.size * 46))
            border = JBUI.Borders.empty(16)
            add(JBLabel("<html><b>Review all proposed changes. Uncheck files to skip.</b></html>"), BorderLayout.NORTH)
        }

        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        checks.forEachIndexed { i, check ->
            val change = changes[i]
            val row = JPanel(BorderLayout(8, 0)).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 42); isOpaque = false
                border = JBUI.Borders.emptyBottom(2)
            }
            row.add(check, BorderLayout.CENTER)

            val tag = JLabel(if (change.isNew) "NEW" else "EDIT").apply {
                foreground = if (change.isNew) Color(63, 185, 80) else Color(88, 166, 255)
                font = font.deriveFont(Font.BOLD, 10f); border = JBUI.Borders.empty(0, 4)
            }
            val diffBtn = JButton("Diff").apply {
                border = JBUI.Borders.empty(2, 8); isFocusPainted = false
                addActionListener {
                    val old = if (change.isNew) "" else try { File("${project.basePath ?: ""}/${change.path}").readText() } catch (_: Exception) { "" }
                    DiffPreviewDialog(project, change.path, old, change.content, change.isNew).show()
                }
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false; add(tag); add(diffBtn) }
            row.add(right, BorderLayout.EAST)
            list.add(row); if (i < changes.size - 1) list.add(JSeparator())
        }
        panel.add(JBScrollPane(list).apply { border = BorderFactory.createEtchedBorder() }, BorderLayout.CENTER)

        val result = JOptionPane.showConfirmDialog(
            contentPanel, panel, "Apply Changes",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE
        )
        if (result == JOptionPane.OK_OPTION) {
            val selected = changes.filterIndexed { i, _ -> checks[i].isSelected }
            WriteCommandAction.runWriteCommandAction(project) {
                selected.forEach { change -> applyChangeInternal(change.path, change.content, change.isNew) }
            }
            val msg = if (selected.size == changes.size) "All ${selected.size} files applied."
                      else "${selected.size} of ${changes.size} files applied."
            Messages.showInfoMessage(project, msg, "Done")
            // Automatically run the agentic test loop — no opt-in needed
            startAgenticTestLoop(selected.map { it.path }, attempt = 1)
        }
    }

    // ─── Agentic test loop ────────────────────────────────────────────────────
    //
    // Runs tests → on failure, asks AI to fix → applies → re-runs. Up to 3 iterations.
    // Each attempt is shown in the terminal panel and the chat panel.

    private fun startAgenticTestLoop(changedFiles: List<String>, attempt: Int) {
        if (attempt > 3) {
            appendSystemMessage("⚠️ Auto-fix gave up after 3 attempts. Remaining failures need manual review.")
            return
        }

        switchToChat()
        appendSystemMessage("🤖 Attempt $attempt/3 — running tests...")
        SwingUtilities.invokeLater {
            terminalOutput.text = "=== Test run attempt $attempt/3 ===\n"
            terminalPanel.isVisible = true
            terminalPanel.revalidate()
        }

        val isFlutterProject = isFlutter
        val onOutput: (String) -> Unit = { line ->
            SwingUtilities.invokeLater { terminalOutput.append(line) }
        }
        val onDone: (TerminalExecutor.CommandResult) -> Unit = { result ->
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    appendSystemMessage("✅ All tests pass! (attempt $attempt)")
                } else {
                    appendSystemMessage("❌ Tests failed (attempt $attempt) — asking AI to fix...")
                    setInputEnabled(false); stopBtn.isVisible = true
                    val (streamArea, reasoningArea) = appendAgentBubble()

                    AiService.fixTestFailures(
                        failureOutput  = result.output.takeLast(4000),
                        changedFiles   = changedFiles,
                        project        = project,
                        onStatus       = { s -> SwingUtilities.invokeLater { statusLabel.text = s } },
                        onToken        = { tok -> SwingUtilities.invokeLater { streamArea.append(tok); scrollToBottom() } },
                        onComplete     = { full ->
                            SwingUtilities.invokeLater {
                                conversationHistory.add(Message("assistant", full))
                                val bodyPanel = streamArea.parent as? JPanel
                                if (bodyPanel != null && full.isNotBlank()) {
                                    bodyPanel.removeAll()
                                    parseMessageParts(full).forEach { bodyPanel.add(it) }
                                    bodyPanel.revalidate(); bodyPanel.repaint()
                                }
                                stopBtn.isVisible = false; setInputEnabled(true)

                                // Apply the fixes and loop
                                val fixes = parseAllFileChanges(full)
                                if (fixes.isEmpty()) {
                                    appendSystemMessage("⚠️ AI returned no file changes. Stopping loop.")
                                    return@invokeLater
                                }
                                WriteCommandAction.runWriteCommandAction(project) {
                                    fixes.forEach { fix -> applyChangeInternal(fix.path, fix.content, fix.isNew) }
                                }
                                val fixedPaths = fixes.map { it.path }
                                appendSystemMessage("Applied ${fixes.size} fix(es): ${fixedPaths.joinToString(", ")}")
                                // Recurse for next attempt
                                startAgenticTestLoop((changedFiles + fixedPaths).distinct(), attempt + 1)
                            }
                        }
                    )
                }
            }
        }

        if (isFlutterProject) TerminalExecutor.flutterTest(project, onOutput, onDone)
        else TerminalExecutor.runTests(project, onOutput, onDone)
    }

    /** Adds a non-interactive status line to the chat panel. */
    private fun appendSystemMessage(text: String) {
        SwingUtilities.invokeLater {
            val label = JBLabel(text).apply {
                foreground = Color(139, 148, 158); font = font.deriveFont(Font.ITALIC, 11f)
                border = JBUI.Borders.empty(4, 16)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            messagePanel.add(label)
            messagePanel.revalidate(); messagePanel.repaint()
            scrollToBottom()
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
    }
    private fun resetSession() {
        AiService.stop(); conversationHistory.clear(); messagePanel.removeAll()
        taggedFiles.clear(); taggedPillsPanel.removeAll()
        pendingImages.clear(); imagePillsPanel.removeAll()
        terminalPanel.isVisible = false; terminalOutput.text = ""
        ProjectTypeDetector.invalidate(project)
        ProjectDependencyAnalyzer.invalidate(project)
        ProjectIndexer.invalidate(project)
        CodebaseGraph.invalidate(project)
        updateProjectBadge()
        // Re-warm the index and graph in background
        ProjectIndexer.warmUp(project)
        cardLayout.show(contentPanel, "WELCOME")
        messagePanel.revalidate(); messagePanel.repaint()
    }

    companion object {
        /** Key used to store the ChatToolWindowContent instance on its root JPanel. */
        const val CLIENT_KEY = "CoforgeAiChatContent"

        val BG_DEEP     = Color(15, 17, 23)
        val BG_SURFACE  = Color(33, 38, 45)
        val BG_BORDER   = Color(48, 54, 61)
        val ACCENT_BLUE = Color(88, 166, 255)

        // Compiled once — not on every keystroke / message
        val RE_IMAGE_SUFFIX  = Regex(""" \[\+\d+ image\(s\)\]""")
        val RE_FILE_CHANGE   = Regex("""<file_change\s+path="([^"]+)">([\s\S]*?)</file_change>""")
        val RE_NEW_FILE      = Regex("""<new_file\s+path="([^"]+)">([\s\S]*?)</new_file>""")
    }
}
