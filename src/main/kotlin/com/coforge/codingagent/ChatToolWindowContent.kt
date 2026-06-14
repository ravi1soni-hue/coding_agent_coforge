package com.coforge.codingagent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.plaf.basic.BasicScrollBarUI

class ChatToolWindowContent(private val project: Project) {

    val contentPanel: JComponent

    private val gson   = Gson()
    private val history = mutableListOf<Message>()
    private val isFlutter get() =
        ProjectTypeDetector.detect(project).type == ProjectTypeDetector.ProjectType.FLUTTER

    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?

    init {
        if (JBCefApp.isSupported()) {
            browser      = JBCefBrowser()
            jsQuery      = JBCefJSQuery.create(browser)
            contentPanel = browser.component

            setupBridge()
            loadUI()

            Thread { ProjectIndexer.warmUp(project); CodebaseGraph.build(project) }
                .apply { isDaemon = true; name = "CoforgeWarmup" }.start()
        } else {
            browser      = null
            jsQuery      = null
            contentPanel = buildSetupPanel()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JCEF bridge
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupBridge() {
        val q = jsQuery ?: return
        q.addHandler { raw ->
            try { handleJs(gson.fromJson(raw, JsonObject::class.java)) }
            catch (e: Exception) { pushRaw("""{"type":"status","data":"Bridge error: ${e.message}"}""") }
            null
        }
        browser?.jbCefClient?.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser, f: CefFrame, code: Int) {
                if (!f.isMain) return
                js("""window.__bridge = function(m){ ${q.inject("m")} }; window._bridgeReady(window.__bridge);""")
                SwingUtilities.invokeLater { sendFileList() }
            }
        }, browser!!.cefBrowser!!)
    }

    private fun loadUI() {
        val html = ChatToolWindowContent::class.java.getResource("/webview/index.html")?.readText()
            ?: error("webview/index.html missing from resources")
        browser?.loadHTML(html)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JS → Kotlin
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleJs(msg: JsonObject) {
        when (msg["type"]?.asString) {
            "send"          -> onSend(msg)
            "stop"          -> AiService.stop()
            "reset"         -> onReset()
            "retry"         -> onRetry()
            "settings"      -> openSettings()
            "pick_image"    -> pickImage()
            "file_list"     -> sendFileList()
            "copy"          -> copy(msg["text"]?.asString ?: "")
            "apply_file"    -> applyFile(msg)
            "apply_all"     -> applyAll(msg)
            "run_tests"     -> runTests()
            "run_build"     -> runBuild()
            "run_lint"      -> runLint()
        }
    }

    private fun onSend(msg: JsonObject) {
        val text   = msg["text"]?.asString?.trim() ?: return
        val images = msg.getAsJsonArray("images")?.map { it.asString } ?: emptyList()
        val tagged = msg.getAsJsonArray("tagged")?.map { it.asString } ?: emptyList()

        if (history.size >= 100) history.subList(0, 40).clear()
        history.add(Message("user", text))

        // Gather PSI context on a pooled thread with a read action.
        // PSI / ProjectFileIndex APIs throw if called without a read lock
        // (the CEF callback thread doesn't hold one).
        ApplicationManager.getApplication().executeOnPooledThread {
            val taggedVfs: List<VirtualFile> = try {
                ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
                    EditorContext.getAllProjectFiles(project).filter { it.name in tagged }
                }
            } catch (_: Exception) { emptyList() }

            val context: String = try {
                ApplicationManager.getApplication().runReadAction<String> {
                    EditorContext.getSmartContext(project, taggedVfs)
                }
            } catch (_: Exception) { "" }

            AiService.callAgentChain(
                userMessage = text,
                history     = history.dropLast(1),
                context     = context,
                images      = images,
                project     = project,
                onStatus    = { s   -> push("status",    s)   },
                onReasoning = { r   -> push("reasoning", r)   },
                onToken     = { tok -> push("token",     tok) },
                onComplete  = { full ->
                    history.add(Message("assistant", full))
                    push("complete", full)
                }
            )
        }
    }

    private fun onRetry() {
        val last = history.lastOrNull { it.role == "user" } ?: return
        history.removeLastOrNull(); history.removeLastOrNull()
        onSend(JsonObject().apply {
            addProperty("type", "send"); addProperty("text", last.content)
            add("images", JsonArray()); add("tagged", JsonArray())
        })
    }

    private fun onReset() {
        AiService.stop(); history.clear()
        ProjectIndexer.invalidate(project)
        ProjectDependencyAnalyzer.invalidate(project)
        ProjectTypeDetector.invalidate(project)
        CodebaseGraph.invalidate(project)
        pushRaw("""{"type":"reset"}""")
        Thread { ProjectIndexer.warmUp(project); CodebaseGraph.build(project) }
            .apply { isDaemon = true }.start()
    }

    private fun applyFile(msg: JsonObject) {
        val path    = msg["path"]?.asString    ?: return
        val content = msg["content"]?.asString ?: return
        val isNew   = msg["is_new"]?.asBoolean ?: false
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                writeFile(path, content, isNew)
                pushRaw("""{"type":"applied","path":${gson.toJson(path)}}""")
            } catch (e: Exception) {
                pushRaw("""{"type":"apply_err","path":${gson.toJson(path)},"msg":${gson.toJson(e.message)}}""")
            }
        }
    }

    private fun applyAll(msg: JsonObject) {
        val files = msg.getAsJsonArray("files") ?: return
        val applied = mutableListOf<String>()
        WriteCommandAction.runWriteCommandAction(project) {
            files.forEach { el ->
                val o = el.asJsonObject
                val path    = o["path"]?.asString    ?: return@forEach
                val content = o["content"]?.asString ?: return@forEach
                val isNew   = o["isNew"]?.asBoolean  ?: false
                try {
                    writeFile(path, content, isNew)
                    applied.add(path)
                    pushRaw("""{"type":"applied","path":${gson.toJson(path)}}""")
                } catch (e: Exception) {
                    pushRaw("""{"type":"apply_err","path":${gson.toJson(path)},"msg":${gson.toJson(e.message)}}""")
                }
            }
        }
        if (applied.isNotEmpty()) SwingUtilities.invokeLater { startTestLoop(applied, 1) }
    }

    private fun writeFile(path: String, content: String, isNew: Boolean) {
        val base = project.basePath ?: throw IllegalStateException("No project base path")
        val file = File("$base/$path")
        if (isNew) {
            file.parentFile?.mkdirs()
            FileUtil.writeToFile(file, content)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        } else {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (vf != null) FileDocumentManager.getInstance().getDocument(vf)?.setText(content)
            else { FileUtil.writeToFile(file, content); LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal commands
    // ─────────────────────────────────────────────────────────────────────────
    private fun runTests() {
        js("""App.openTerminal("Running tests...")""")
        startTestLoop(emptyList(), 1)
    }

    private fun runBuild() {
        js("""App.openTerminal("Building...")""")
        val exec = if (isFlutter)
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit -> TerminalExecutor.flutterBuild(project, onLine, onDone) }
        else
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit -> TerminalExecutor.buildDebug(project, onLine, onDone) }
        runCmd(exec) { ok, out -> if (!ok) autoFix(out, "build") }
    }

    private fun runLint() {
        js("""App.openTerminal("Linting...")""")
        val exec = if (isFlutter)
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit -> TerminalExecutor.flutterAnalyze(project, onLine, onDone) }
        else
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit -> TerminalExecutor.runLint(project, onLine, onDone) }
        runCmd(exec) { ok, out -> if (!ok) autoFix(out, "lint") }
    }

    private fun runCmd(
        exec: ((String)->Unit, (TerminalExecutor.CommandResult)->Unit) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        exec(
            { line -> termLine(line) },
            { r    -> SwingUtilities.invokeLater {
                pushRaw("""{"type":"term_done","ok":${r.isSuccess},"summary":${gson.toJson(r.summary)}}""")
                onDone(r.isSuccess, r.output)
            }}
        )
    }

    private fun startTestLoop(changed: List<String>, attempt: Int) {
        if (attempt > 3) { js("""App.sysLine("⚠️ Gave up after 3 attempts")"""); return }
        js("""App.openTerminal("Test run $attempt/3"); App.sysLine("🤖 Attempt $attempt/3 — running tests...")""")
        val exec = if (isFlutter)
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit -> TerminalExecutor.flutterTest(project, onLine, onDone) }
        else
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit -> TerminalExecutor.runTests(project, onLine, onDone) }
        exec(
            { line -> termLine(line) },
            { r    -> SwingUtilities.invokeLater {
                pushRaw("""{"type":"term_done","ok":${r.isSuccess},"summary":${gson.toJson(r.summary)}}""")
                if (r.isSuccess) {
                    js("""App.sysLine("✅ All tests pass! (attempt $attempt)")""")
                } else {
                    js("""App.sysLine("❌ Tests failed — AI fixing...")""")
                    js("""App.startStream()""")
                    AiService.fixTestFailures(
                        failureOutput = r.output.takeLast(4000),
                        changedFiles  = changed, project = project,
                        onStatus  = { s   -> push("status", s) },
                        onToken   = { tok -> push("token", tok) },
                        onComplete = { full ->
                            history.add(Message("assistant", full))
                            push("complete", full)
                            val fixes = parseChanges(full)
                            if (fixes.isEmpty()) { js("""App.sysLine("⚠️ No fix returned.")"""); return@fixTestFailures }
                            WriteCommandAction.runWriteCommandAction(project) {
                                fixes.forEach { (p, c, n) -> try { writeFile(p, c, n) } catch (_: Exception) {} }
                            }
                            startTestLoop((changed + fixes.map { it.first }).distinct(), attempt + 1)
                        }
                    )
                }
            }}
        )
    }

    private fun autoFix(output: String, label: String) {
        val errs = output.lines().filter { it.contains("error", true) || it.contains("FAILED") }.take(25).joinToString("\n")
        if (errs.isBlank()) return
        val prompt = "Fix these $label errors:\n```\n$errs\n```"
        history.add(Message("user", prompt))
        js("""App.sysLine("🔧 Auto-fixing $label errors..."); App.startStream()""")
        val ctx = try { EditorContext.getSmartContext(project, emptyList()) } catch (_: Exception) { "" }
        AiService.callAgentChain(
            userMessage = prompt, history = history.dropLast(1), context = ctx, project = project,
            onStatus    = { s   -> push("status", s) },
            onReasoning = { _   -> },
            onToken     = { tok -> push("token", tok) },
            onComplete  = { full -> history.add(Message("assistant", full)); push("complete", full) }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────
    private fun openSettings() = ApplicationManager.getApplication().invokeLater {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AppSettingsConfigurable::class.java)
    }

    private fun pickImage() = ApplicationManager.getApplication().invokeLater {
        val fc = javax.swing.JFileChooser().apply {
            dialogTitle = "Attach Image"
            fileFilter  = javax.swing.filechooser.FileNameExtensionFilter("Images","png","jpg","jpeg","gif","webp","bmp")
            isMultiSelectionEnabled = true
        }
        if (fc.showOpenDialog(contentPanel) == javax.swing.JFileChooser.APPROVE_OPTION) {
            fc.selectedFiles.forEach { f ->
                try {
                    val img  = ImageIO.read(f) ?: return@forEach
                    val b64  = encodeB64(resizeImg(img, 1024))
                    val name = gson.toJson(f.name)
                    SwingUtilities.invokeLater { js("App.addImage(${gson.toJson(b64)}, $name)") }
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendFileList() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val names: List<String> = try {
                ApplicationManager.getApplication().runReadAction<List<String>> {
                    EditorContext.getAllProjectFiles(project).map { it.name }
                }
            } catch (_: Exception) { emptyList() }
            pushRaw("""{"type":"file_list","data":${gson.toJson(names)}}""")
        }
    }

    private fun copy(text: String) =
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    // Kotlin→JS helpers
    private fun push(type: String, data: String) =
        pushRaw("""{"type":${gson.toJson(type)},"data":${gson.toJson(data)}}""")

    private fun pushRaw(json: String) {
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
        js("window.onK('$escaped')")
    }

    private fun termLine(line: String) {
        val cls = when {
            line.contains("error", true) || line.contains("FAILED") -> "err"
            line.contains("warn",  true)  -> "warn"
            line.contains("success", true)-> "ok"
            else -> ""
        }
        val escaped = line.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        js("App.termLine('$escaped','$cls')")
    }

    private fun js(script: String) = browser?.cefBrowser?.executeJavaScript(script, "", 0)

    // File change parsing
    private data class FC(val first: String, val second: String, val third: Boolean)
    private fun parseChanges(r: String): List<FC> {
        val list = mutableListOf<FC>()
        Regex("""<file_change\s+path="([^"]+)">([\s\S]*?)</file_change>""").findAll(r)
            .forEach { list.add(FC(it.groupValues[1].trim(), it.groupValues[2].trim(), false)) }
        Regex("""<new_file\s+path="([^"]+)">([\s\S]*?)</new_file>""").findAll(r)
            .forEach { list.add(FC(it.groupValues[1].trim(), it.groupValues[2].trim(), true)) }
        return list
    }

    // Image utilities
    private fun resizeImg(src: BufferedImage, max: Int): BufferedImage {
        if (src.width <= max && src.height <= max) return src
        val s = max.toDouble() / maxOf(src.width, src.height)
        val nw = (src.width * s).toInt(); val nh = (src.height * s).toInt()
        val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().also { g ->
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, nw, nh, null); g.dispose()
        }
        return out
    }
    private fun encodeB64(img: BufferedImage): String {
        val b = ByteArrayOutputStream(); ImageIO.write(img, "PNG", b)
        return java.util.Base64.getEncoder().encodeToString(b.toByteArray())
    }

    // Public API for QuickActionsGroup
    fun prefillAndSend(prompt: String) {
        val esc = prompt.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        js("App.prefill('$esc')")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup panel (shown when JCEF unavailable)
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildSetupPanel(): JPanel {
        val BG    = Color(13, 17, 23)
        val CARD  = Color(22, 27, 34)
        val BORD  = Color(48, 54, 61)
        val TEXT  = Color(230, 237, 243)
        val MUTE  = Color(125, 133, 144)
        val ACCN  = Color(193, 122, 94)
        val GREEN = Color(63, 185, 80)

        val root = object : JPanel(GridBagLayout()) {
            override fun paintComponent(g: Graphics) { g.color = BG; g.fillRect(0, 0, width, height) }
        }.apply { isOpaque = false }

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = GridBagConstraints.RELATIVE
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
            insets = Insets(8, 32, 8, 32)
        }

        // ── Logo ──
        root.add(object : JComponent() {
            init { preferredSize = Dimension(64, 64) }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.paint = GradientPaint(0f, 0f, ACCN, 64f, 64f, Color(139, 92, 246))
                g2.fillOval(0, 0, 64, 64)
                g2.paint = null; g2.color = Color.WHITE
                g2.font = Font("Inter", Font.BOLD, 30)
                val fm = g2.fontMetrics
                g2.drawString("C", (64 - fm.stringWidth("C")) / 2, 32 + fm.ascent / 2 - 2)
            }
        }, gbc)

        root.add(JLabel("Coforge AI — Browser Setup").apply {
            foreground = TEXT; font = Font("Inter", Font.BOLD, 18)
            horizontalAlignment = SwingConstants.CENTER
        }, gbc)

        root.add(JLabel("<html><center>Android Studio ships without the embedded browser (JCEF).<br>A one-time runtime switch is needed — takes 30 seconds.</center></html>").apply {
            foreground = MUTE; font = Font("Inter", Font.PLAIN, 12)
            horizontalAlignment = SwingConstants.CENTER
        }, gbc)

        // ── Steps card ──
        val steps = object : JPanel(GridLayout(3, 1, 0, 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = CARD
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f))
                g2.color = BORD
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 10f, 10f))
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(14, 18)
        }
        listOf(
            "1.  Press  Ctrl+Shift+A  (⌘⇧A on Mac)",
            "2.  Type:  Choose Boot Runtime for the IDE",
            "3.  Select any entry that says  \"with JCEF\"  → OK → Restart"
        ).forEachIndexed { i, text ->
            val lbl = JLabel("<html>$text</html>").apply {
                foreground = TEXT; font = Font("Inter", Font.PLAIN, 12)
                border = if (i < 2) BorderFactory.createMatteBorder(0, 0, 1, 0, BORD) else BorderFactory.createEmptyBorder()
                border = BorderFactory.createCompoundBorder(border, JBUI.Borders.empty(8, 0))
            }
            steps.add(lbl)
        }
        root.add(steps, gbc)

        // ── Open dialog button ──
        val btn = object : JButton("Open Boot Runtime Selector Now") {
            init {
                isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
                foreground = Color.WHITE; font = Font("Inter", Font.BOLD, 13)
                border = JBUI.Borders.empty(11, 20); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { tryOpenRuntimeDialog() }
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ACCN
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
                super.paintComponent(g)
            }
        }
        root.add(btn, gbc)

        root.add(JLabel("<html><center><span style='color:#606062'>After restarting, reinstall/re-enable the plugin.<br>JCEF is available on all JBR \"with JCEF\" runtimes — free download inside Android Studio.</span></center></html>").apply {
            foreground = MUTE; font = Font("Inter", Font.PLAIN, 11)
            horizontalAlignment = SwingConstants.CENTER
        }, gbc)

        return root
    }

    /** Tries several known action IDs for the Boot Runtime chooser dialog. */
    private fun tryOpenRuntimeDialog() {
        val ids = listOf(
            "ChooseBootRuntime",          // older IJ
            "choose.ide.runtime.v2",      // IJ 2022+
            "SwitchBootJdk"               // some builds
        )
        val mgr = ActionManager.getInstance()
        for (id in ids) {
            val action = mgr.getAction(id) ?: continue
            val event  = AnActionEvent.createFromDataContext("", null, DataContext.EMPTY_CONTEXT)
            ApplicationManager.getApplication().invokeLater { action.actionPerformed(event) }
            return
        }
        // None found — show manual instructions in a dialog
        javax.swing.JOptionPane.showMessageDialog(
            contentPanel,
            "Press Ctrl+Shift+A (⌘⇧A) → type \"Choose Boot Runtime\" → pick one with \"JCEF\" → restart.",
            "Open Manually",
            javax.swing.JOptionPane.INFORMATION_MESSAGE
        )
    }

    companion object { const val CLIENT_KEY = "CoforgeAiChatContent" }
}
