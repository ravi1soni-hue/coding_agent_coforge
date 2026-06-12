package com.coforge.codingagent

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import java.util.regex.Pattern

class ChatToolWindowContent(private val project: Project) {
    val contentPanel = JPanel(CardLayout())
    private val cardLayout = contentPanel.layout as CardLayout
    
    private val chatPanel = JPanel(BorderLayout())
    private val welcomePanel = JPanel(GridBagLayout())
    
    private val messagePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color(15, 17, 23)
        border = JBUI.Borders.empty(20)
    }

    private val inputArea = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        background = Color(22, 27, 34)
        foreground = Color.WHITE
        caretColor = Color(88, 166, 255)
        font = Font("Inter", Font.PLAIN, 13)
        margin = JBUI.insets(12)
        border = BorderFactory.createEmptyBorder()
    }

    private val statusLabel = JBLabel("Ready").apply {
        foreground = Color(139, 148, 158)
        font = font.deriveFont(11f)
    }

    private val taggedFiles = mutableSetOf<VirtualFile>()
    private val taggedPillsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
        isOpaque = false
    }

    init {
        setupWelcomeUI()
        setupChatUI()
        setupTaggingSupport()
        
        contentPanel.add(welcomePanel, "WELCOME")
        contentPanel.add(chatPanel, "CHAT")
        cardLayout.show(contentPanel, "WELCOME")
    }

    private fun setupWelcomeUI() {
        welcomePanel.background = Color(15, 17, 23)
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            insets = JBUI.insets(10)
            anchor = GridBagConstraints.CENTER
        }

        val logo = JBLabel(AllIcons.General.ContextHelp).apply { 
            foreground = Color(88, 166, 255)
            font = font.deriveFont(48f)
        }
        
        val title = JBLabel("Hello, Android Architect").apply {
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD, 24f)
        }
        
        val cardsPanel = JPanel(GridLayout(1, 2, 20, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(40, 20)
            add(createModeCard("Plan", "Draft requirements and architecture.", Color(48, 54, 61)) { switchToChat("I need to plan...") })
            add(createModeCard("Build", "Implement features and fix bugs.", Color(48, 54, 61)) { switchToChat("Let's build...") })
        }

        welcomePanel.add(logo, c)
        welcomePanel.add(title, c)
        welcomePanel.add(cardsPanel, c)
    }

    private fun createModeCard(title: String, desc: String, bg: Color, action: () -> Unit): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                background = bg
                border = JBUI.Borders.empty(20)
                preferredSize = Dimension(180, 160)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(JBLabel(title).apply { foreground = Color.WHITE; font = font.deriveFont(Font.BOLD, 16f) }, BorderLayout.NORTH)
                add(JBTextArea(desc).apply { foreground = Color(139, 148, 158); isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }, BorderLayout.CENTER)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = action()
                    override fun mouseEntered(e: MouseEvent) { background = bg.brighter() }
                    override fun mouseExited(e: MouseEvent) { background = bg }
                })
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f))
                g2.dispose()
            }
        }
    }

    private fun setupChatUI() {
        chatPanel.background = Color(15, 17, 23)
        val header = JPanel(BorderLayout()).apply {
            background = Color(22, 27, 34)
            border = JBUI.Borders.compound(BorderFactory.createMatteBorder(0, 0, 1, 0, Color(48, 54, 61)), JBUI.Borders.empty(12, 16))
            val title = JBLabel("COFORGE AI AGENT").apply { foreground = Color.WHITE; font = font.deriveFont(Font.BOLD, 12f) }
            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply { isOpaque = false }
            actions.add(createIconButton(AllIcons.Actions.Refresh, "Reset") { resetSession() })
            actions.add(createIconButton(AllIcons.General.Settings, "Settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, AppSettingsConfigurable::class.java) })
            add(title, BorderLayout.WEST); add(actions, BorderLayout.EAST)
        }

        val scrollPane = JBScrollPane(messagePanel).apply {
            border = null
            viewport.background = Color(15, 17, 23)
            verticalScrollBar.unitIncrement = 16
        }

        val footer = JPanel(BorderLayout()).apply {
            background = Color(15, 17, 23)
            border = JBUI.Borders.empty(16)
            val inputContainer = JPanel(BorderLayout()).apply {
                background = Color(22, 27, 34)
                border = BorderFactory.createLineBorder(Color(48, 54, 61), 1)
                add(taggedPillsPanel, BorderLayout.NORTH)
                add(inputArea, BorderLayout.CENTER)
                val controls = JPanel(BorderLayout()).apply { background = Color(22, 27, 34); border = JBUI.Borders.empty(4, 8, 8, 8) }
                val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
                left.add(statusLabel)
                val sendBtn = createIconButton(AllIcons.Actions.MoveUp, "Send") { sendMessage() }.apply { isOpaque = true; background = Color(35, 134, 54) }
                controls.add(left, BorderLayout.WEST); controls.add(sendBtn, BorderLayout.EAST)
                add(controls, BorderLayout.SOUTH)
            }
            add(inputContainer, BorderLayout.CENTER)
        }

        chatPanel.add(header, BorderLayout.NORTH)
        chatPanel.add(scrollPane, BorderLayout.CENTER)
        chatPanel.add(footer, BorderLayout.SOUTH)
    }

    private fun setupTaggingSupport() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) { if (e.keyChar == '@') showFilePickerPopup() }
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); sendMessage() } }
        })
    }

    private fun showFilePickerPopup() {
        val allFiles = EditorContext.getAllProjectFiles(project)
        val list = JBList(allFiles.map { it.name })
        JBPopupFactory.getInstance().createListPopupBuilder(list).setTitle("Tag Files").setItemChoosenCallback {
            val file = allFiles.find { it.name == list.selectedValue }
            if (file != null) { addTag(file); val text = inputArea.text; val lastAt = text.lastIndexOf('@'); if (lastAt >= 0) inputArea.text = text.substring(0, lastAt) }
        }.createPopup().showInBestPositionFor(inputArea)
    }

    private fun addTag(file: VirtualFile) {
        if (taggedFiles.add(file)) {
            val pill = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)).apply {
                background = Color(33, 38, 45); border = BorderFactory.createLineBorder(Color(48, 54, 61), 1)
                add(JBLabel(file.name).apply { foreground = Color(88, 166, 255); font = font.deriveFont(11f) })
                add(JBLabel(AllIcons.Actions.Close).apply { cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { taggedFiles.remove(file); taggedPillsPanel.remove(this@apply.parent); taggedPillsPanel.revalidate(); taggedPillsPanel.repaint() }
                })})
            }
            taggedPillsPanel.add(pill); taggedPillsPanel.revalidate(); taggedPillsPanel.repaint()
        }
    }

    private fun sendMessage() {
        val text = inputArea.text.trim()
        if (text.isEmpty() && taggedFiles.isEmpty()) return
        if (welcomePanel.isVisible) switchToChat()
        appendBubble("User", text)
        inputArea.text = ""
        val context = EditorContext.getCurrentFileContext(project) + "\n" + EditorContext.getFilesContext(project, taggedFiles.toList())
        AiService.callAgentChain(text, context, { status -> SwingUtilities.invokeLater { statusLabel.text = status } }, { result -> SwingUtilities.invokeLater { appendBubble("Agent", result); statusLabel.text = "Ready" } })
    }

    private fun appendBubble(sender: String, message: String) {
        val isUser = sender == "User"
        val bubble = JPanel().apply {
            layout = BorderLayout(); isOpaque = false; border = JBUI.Borders.emptyBottom(20)
            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                val header = JPanel(BorderLayout()).apply {
                    isOpaque = false; border = JBUI.Borders.emptyBottom(6)
                    add(JBLabel(if (isUser) "You" else "Coforge AI").apply { foreground = Color(139, 148, 158); font = font.deriveFont(Font.BOLD, 10f) }, if (isUser) BorderLayout.EAST else BorderLayout.WEST)
                }
                add(header)
                
                val body = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS); background = if (isUser) Color(35, 134, 54, 40) else Color(33, 38, 45); border = JBUI.Borders.empty(12, 16)
                    parseMessageParts(message).forEach { add(it) }
                }
                add(body)
            }
            if (isUser) { add(Box.createHorizontalStrut(40), BorderLayout.WEST); add(inner, BorderLayout.CENTER) }
            else { add(inner, BorderLayout.CENTER); add(Box.createHorizontalStrut(40), BorderLayout.EAST) }
        }
        messagePanel.add(bubble); messagePanel.revalidate()
        SwingUtilities.invokeLater { val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, messagePanel) as? JScrollPane; sp?.verticalScrollBar?.value = sp?.verticalScrollBar?.maximum ?: 0 }
    }

    private fun parseMessageParts(message: String): List<Component> {
        val components = mutableListOf<Component>()
        val codePattern = Pattern.compile("```(\\w+)?\\n([\\s\\S]+?)\\n```")
        val changePattern = Pattern.compile("<(file_change|new_file) path=\"(.+?)\">([\\s\\S]+?)</(file_change|new_file)>")
        
        var lastIdx = 0
        val combinedMatcher = Pattern.compile("(```[\\s\\S]+?```|<(?:file_change|new_file)[\\s\\S]+?</(?:file_change|new_file)>)").matcher(message)
        
        while (combinedMatcher.find()) {
            val textPart = message.substring(lastIdx, combinedMatcher.start()).trim()
            if (textPart.isNotEmpty()) components.add(createTextComponent(textPart))
            
            val match = combinedMatcher.group()
            if (match.startsWith("```")) {
                val m = codePattern.matcher(match); if (m.find()) components.add(createCodeBlock(m.group(2), m.group(1) ?: "kotlin"))
            } else {
                val m = changePattern.matcher(match); if (m.find()) components.add(createChangeProposal(m.group(2), m.group(3), m.group(1) == "new_file"))
            }
            lastIdx = combinedMatcher.end()
        }
        
        val remaining = message.substring(lastIdx).trim()
        if (remaining.isNotEmpty()) components.add(createTextComponent(remaining))
        
        return components
    }

    private fun createTextComponent(text: String) = JTextArea(text).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; background = Color(0,0,0,0); foreground = Color.WHITE; font = Font("Inter", Font.PLAIN, 13); border = JBUI.Borders.emptyBottom(10)
    }

    private fun createCodeBlock(code: String, lang: String) = JPanel(BorderLayout()).apply {
        background = Color(22, 27, 34); border = JBUI.Borders.empty(8)
        val header = JPanel(BorderLayout()).apply { background = Color(48, 54, 61); border = JBUI.Borders.empty(4, 8)
            add(JBLabel(lang.uppercase()).apply { foreground = Color.WHITE; font = font.deriveFont(Font.BOLD, 10f) }, BorderLayout.WEST)
            add(createIconButton(AllIcons.Actions.Copy, "Copy") { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null) }, BorderLayout.EAST)
        }
        val area = JTextArea(code).apply { background = Color(13, 17, 23); foreground = Color(173, 186, 199); font = Font("JetBrains Mono", Font.PLAIN, 12); isEditable = false; margin = JBUI.insets(10) }
        add(header, BorderLayout.NORTH); add(JBScrollPane(area).apply { border = null }, BorderLayout.CENTER)
        preferredSize = Dimension(400, 200)
    }

    private fun createChangeProposal(path: String, code: String, isNew: Boolean) = JPanel(BorderLayout()).apply {
        background = Color(48, 54, 61, 100); border = JBUI.Borders.compound(BorderFactory.createLineBorder(Color(88, 166, 255), 1), JBUI.Borders.empty(12))
        val title = JBLabel("${if (isNew) "NEW FILE" else "CHANGE"}: $path").apply { foreground = Color(88, 166, 255); font = font.deriveFont(Font.BOLD, 12f) }
        val applyBtn = JButton("Approve & Apply").apply {
            background = Color(35, 134, 54); foreground = Color.WHITE; isOpaque = true; border = JBUI.Borders.empty(8, 16)
            addActionListener { applyChange(path, code, isNew) }
        }
        add(title, BorderLayout.NORTH); add(applyBtn, BorderLayout.SOUTH)
        border = JBUI.Borders.empty(10)
    }

    private fun applyChange(path: String, code: String, isNew: Boolean) {
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                val fullPath = project.basePath + "/" + path
                val file = File(fullPath)
                if (isNew) {
                    FileUtil.writeToFile(file, code)
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                } else {
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                    if (virtualFile != null) {
                        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                        document?.setText(code)
                    } else {
                        FileUtil.writeToFile(file, code)
                    }
                }
                Messages.showInfoMessage(project, "Successfully applied change to $path", "Success")
            } catch (e: Exception) {
                Messages.showErrorDialog(project, "Failed to apply change: ${e.message}", "Error")
            }
        }
    }

    private fun createIconButton(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip; isContentAreaFilled = false; isBorderPainted = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); addActionListener { action() }
    }

    private fun switchToChat(initial: String? = null) { cardLayout.show(contentPanel, "CHAT"); if (initial != null) inputArea.text = initial }
    private fun resetSession() { messagePanel.removeAll(); taggedFiles.clear(); taggedPillsPanel.removeAll(); cardLayout.show(contentPanel, "WELCOME"); messagePanel.revalidate(); messagePanel.repaint() }
}
