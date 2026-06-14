package com.coforge.codingagent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ChatToolWindowContent(private val project: Project) {

    // ── Public panel exposed to ChatToolWindowFactory ──────────────────────────
    val contentPanel: JComponent

    private val gson = Gson()
    private val isFlutter get() =
        ProjectTypeDetector.detect(project).type == ProjectTypeDetector.ProjectType.FLUTTER

    // ── JCEF ──────────────────────────────────────────────────────────────────
    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?

    // ── Conversation history ───────────────────────────────────────────────────
    private val history = mutableListOf<Message>()

    init {
        if (JBCefApp.isSupported()) {
            browser  = JBCefBrowser()
            jsQuery  = JBCefJSQuery.create(browser)
            contentPanel = browser.component

            setupBridge()
            loadUI()

            // Background warm-up
            Thread { ProjectIndexer.warmUp(project); CodebaseGraph.build(project) }
                .apply { isDaemon = true; name = "CoforgeWarmup" }.start()
        } else {
            // Fallback for environments without JCEF
            browser  = null
            jsQuery  = null
            contentPanel = JPanel(BorderLayout()).apply {
                add(JLabel("JCEF is not supported in this environment. " +
                    "Run with a JetBrains Runtime JDK.", JLabel.CENTER))
            }
        }
    }

    // ── Bridge setup ──────────────────────────────────────────────────────────
    private fun setupBridge() {
        val b = browser ?: return
        val q = jsQuery ?: return

        q.addHandler { raw ->
            try {
                val obj = gson.fromJson(raw, JsonObject::class.java)
                handleJsMessage(obj)
            } catch (e: Exception) {
                pushError("Parse error: ${e.message}")
            }
            null
        }

        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                // Inject the bridge function so JS can call Kotlin
                val bridgeJs = """
                    window.__kotlinBridge = function(msg) { ${q.inject("msg")} };
                    window.injectBridge(window.__kotlinBridge);
                """.trimIndent()
                b.cefBrowser.executeJavaScript(bridgeJs, b.cefBrowser.url, 0)
                // Send initial file list
                SwingUtilities.invokeLater { pushFileList() }
            }
        }, b.cefBrowser)
    }

    private fun loadUI() {
        val html = ChatToolWindowContent::class.java
            .getResource("/webview/index.html")
            ?.readText()
            ?: error("webview/index.html not found in resources")
        browser?.loadHTML(html)
    }

    // ── JS → Kotlin message router ────────────────────────────────────────────
    private fun handleJsMessage(msg: JsonObject) {
        when (msg.get("type")?.asString) {
            "send"          -> onSend(msg)
            "stop"          -> AiService.stop()
            "reset"         -> onReset()
            "retry"         -> onRetry()
            "open_settings" -> openSettings()
            "pick_image"    -> pickImage()
            "tag_file"      -> { /* file already tagged client-side; context built from name at send time */ }
            "get_file_list" -> pushFileList()
            "copy"          -> copyToClipboard(msg.get("text")?.asString ?: "")
            "apply_file"    -> onApplyFile(msg)
            "apply_all"     -> onApplyAll(msg)
            "run_tests"     -> runTests()
            "run_build"     -> runBuild()
            "run_lint"      -> runLint()
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────
    private fun onSend(msg: JsonObject) {
        val text        = msg.get("text")?.asString?.trim() ?: return
        val images      = msg.getAsJsonArray("images")?.map { it.asString } ?: emptyList()
        val taggedNames = msg.getAsJsonArray("tagged_files")?.map { it.asString } ?: emptyList()

        if (history.size >= 100) history.subList(0, 40).clear()
        history.add(Message("user", text))

        val taggedVfs = EditorContext.getAllProjectFiles(project)
            .filter { it.name in taggedNames }

        val context = EditorContext.getSmartContext(project, taggedVfs)

        AiService.callAgentChain(
            userMessage = text,
            history     = history.dropLast(1),
            context     = context,
            images      = images,
            project     = project,
            onStatus    = { s   -> push("status",  s)        },
            onReasoning = { r   -> push("reasoning", r)      },
            onToken     = { tok -> push("token",   tok)      },
            onComplete  = { full ->
                history.add(Message("assistant", full))
                push("complete", full)
            }
        )
    }

    // ── Retry (resend last user message) ──────────────────────────────────────
    private fun onRetry() {
        val last = history.lastOrNull { it.role == "user" } ?: return
        history.removeLastOrNull(); history.removeLastOrNull()
        val fakeMsg = JsonObject().apply {
            addProperty("type", "send")
            addProperty("text", last.content)
            add("images", JsonArray())
            add("tagged_files", JsonArray())
        }
        onSend(fakeMsg)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────
    private fun onReset() {
        AiService.stop(); history.clear()
        ProjectIndexer.invalidate(project)
        ProjectDependencyAnalyzer.invalidate(project)
        ProjectTypeDetector.invalidate(project)
        CodebaseGraph.invalidate(project)
        pushRaw("""{"type":"session_reset"}""")
        Thread { ProjectIndexer.warmUp(project); CodebaseGraph.build(project) }
            .apply { isDaemon = true }.start()
    }

    // ── Apply single file ──────────────────────────────────────────────────────
    private fun onApplyFile(msg: JsonObject) {
        val path    = msg.get("path")?.asString    ?: return
        val content = msg.get("content")?.asString ?: return
        val isNew   = msg.get("is_new")?.asBoolean ?: false
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                applyFile(path, content, isNew)
                pushRaw("""{"type":"apply_success","path":${gson.toJson(path)}}""")
            } catch (e: Exception) {
                pushRaw("""{"type":"apply_error","path":${gson.toJson(path)},"error":${gson.toJson(e.message)}}""")
            }
        }
    }

    // ── Apply all files ────────────────────────────────────────────────────────
    private fun onApplyAll(msg: JsonObject) {
        val files = msg.getAsJsonArray("files") ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val applied = mutableListOf<String>()
            files.forEach { el ->
                val obj = el.asJsonObject
                val path    = obj.get("path")?.asString    ?: return@forEach
                val content = obj.get("content")?.asString ?: return@forEach
                val isNew   = obj.get("isNew")?.asBoolean  ?: false
                try {
                    applyFile(path, content, isNew)
                    applied.add(path)
                    pushRaw("""{"type":"apply_success","path":${gson.toJson(path)}}""")
                } catch (e: Exception) {
                    pushRaw("""{"type":"apply_error","path":${gson.toJson(path)},"error":${gson.toJson(e.message)}}""")
                }
            }
            // Trigger agentic test loop after applying
            if (applied.isNotEmpty()) SwingUtilities.invokeLater { startAgenticTestLoop(applied, 1) }
        }
    }

    // ── File write logic ──────────────────────────────────────────────────────
    private fun applyFile(path: String, content: String, isNew: Boolean) {
        val base = project.basePath
            ?: throw IllegalStateException("Project base path is null")
        val file = File("$base/$path")
        if (isNew) {
            file.parentFile?.mkdirs()
            FileUtil.writeToFile(file, content)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        } else {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (vf != null) {
                FileDocumentManager.getInstance().getDocument(vf)?.setText(content)
            } else {
                FileUtil.writeToFile(file, content)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            }
        }
    }

    // ── Terminal commands ─────────────────────────────────────────────────────
    private fun runTests() {
        jsEval("""App.openTerminal("Running tests...")""")
        startAgenticTestLoop(emptyList(), 1)
    }

    private fun runBuild() {
        jsEval("""App.openTerminal("Building...")""")
        val onOutput: (String) -> Unit = { line -> termLine(line) }
        val onDone: (TerminalExecutor.CommandResult) -> Unit = { result ->
            SwingUtilities.invokeLater {
                pushRaw("""{"type":"terminal_done","success":${result.isSuccess},"summary":${gson.toJson(result.summary)}}""")
                if (!result.isSuccess) autoFix(result.output, "Build failed")
            }
        }
        if (isFlutter) TerminalExecutor.flutterBuild(project, onOutput, onDone)
        else           TerminalExecutor.buildDebug(project, onOutput, onDone)
    }

    private fun runLint() {
        jsEval("""App.openTerminal("Analyzing...")""")
        val onOutput: (String) -> Unit = { line -> termLine(line) }
        val onDone: (TerminalExecutor.CommandResult) -> Unit = { result ->
            SwingUtilities.invokeLater {
                pushRaw("""{"type":"terminal_done","success":${result.isSuccess},"summary":${gson.toJson(result.summary)}}""")
                if (!result.isSuccess) autoFix(result.output, "Lint errors")
            }
        }
        if (isFlutter) TerminalExecutor.flutterAnalyze(project, onOutput, onDone)
        else           TerminalExecutor.runLint(project, onOutput, onDone)
    }

    // ── Agentic test loop ──────────────────────────────────────────────────────
    private fun startAgenticTestLoop(changedFiles: List<String>, attempt: Int) {
        if (attempt > 3) {
            jsEval("""App.addSystemLine("⚠️ Auto-fix gave up after 3 attempts — manual review needed.", true)""")
            return
        }
        jsEval("""App.openTerminal("Test run $attempt/3")""")
        jsEval("""App.addSystemLine("🤖 Attempt $attempt/3 — running tests...")""")

        val onOutput: (String) -> Unit = { line -> termLine(line) }
        val onDone: (TerminalExecutor.CommandResult) -> Unit = { result ->
            SwingUtilities.invokeLater {
                pushRaw("""{"type":"terminal_done","success":${result.isSuccess},"summary":${gson.toJson(result.summary)}}""")
                if (result.isSuccess) {
                    jsEval("""App.addSystemLine("✅ All tests pass! (attempt $attempt)")""")
                } else {
                    jsEval("""App.addSystemLine("❌ Tests failed — AI is fixing... ($attempt/3)")""")
                    jsEval("""App.showTyping()""")
                    AiService.fixTestFailures(
                        failureOutput = result.output.takeLast(4000),
                        changedFiles  = changedFiles,
                        project       = project,
                        onStatus  = { s   -> push("status", s) },
                        onToken   = { tok -> push("token",  tok) },
                        onComplete = { full ->
                            history.add(Message("assistant", full))
                            push("complete", full)
                            // Parse and auto-apply fixes
                            val fixes = parseFileChanges(full)
                            if (fixes.isEmpty()) {
                                jsEval("""App.addSystemLine("⚠️ No fixes returned. Stopping.", true)""")
                                return@fixTestFailures
                            }
                            WriteCommandAction.runWriteCommandAction(project) {
                                fixes.forEach { (path, content, isNew) ->
                                    try { applyFile(path, content, isNew) } catch (_: Exception) {}
                                }
                            }
                            val newFiles = (changedFiles + fixes.map { it.path }).distinct()
                            startAgenticTestLoop(newFiles, attempt + 1)
                        }
                    )
                }
            }
        }
        if (isFlutter) TerminalExecutor.flutterTest(project, onOutput, onDone)
        else           TerminalExecutor.runTests(project, onOutput, onDone)
    }

    // ── Auto-fix on build/lint failure ────────────────────────────────────────
    private fun autoFix(output: String, label: String) {
        val errors = output.lines()
            .filter { it.contains("error", true) || it.contains("FAILED") }
            .take(30).joinToString("\n")
        if (errors.isBlank()) return

        val prompt = "Fix these $label errors:\n```\n$errors\n```"
        history.add(Message("user", prompt))

        val userJson = gson.toJson(mapOf("type" to "show_system", "text" to "Auto-fixing $label..."))
        jsEval("""App.addSystemLine("🔧 Auto-fixing $label...")""")
        jsEval("""App.showTyping()""")

        val ctx = try { EditorContext.getSmartContext(project, emptyList()) } catch (_: Exception) { "" }
        AiService.callAgentChain(
            userMessage = prompt,
            history     = history.dropLast(1),
            context     = ctx,
            project     = project,
            onStatus    = { s   -> push("status", s) },
            onReasoning = { r   -> push("reasoning", r) },
            onToken     = { tok -> push("token", tok) },
            onComplete  = { full ->
                history.add(Message("assistant", full))
                push("complete", full)
            }
        )
    }

    // ── Settings & image picker ────────────────────────────────────────────────
    private fun openSettings() {
        ApplicationManager.getApplication().invokeLater {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, AppSettingsConfigurable::class.java)
        }
    }

    private fun pickImage() {
        ApplicationManager.getApplication().invokeLater {
            val fc = javax.swing.JFileChooser().apply {
                dialogTitle = "Attach Image"
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                    "Images", "png", "jpg", "jpeg", "gif", "webp", "bmp")
                isMultiSelectionEnabled = true
            }
            if (fc.showOpenDialog(contentPanel) == javax.swing.JFileChooser.APPROVE_OPTION) {
                fc.selectedFiles.forEach { f ->
                    try {
                        val img = ImageIO.read(f) ?: return@forEach
                        val resized = resizeImage(img, 1024)
                        val b64 = encodeBase64(resized)
                        val nameJson = gson.toJson(f.name)
                        SwingUtilities.invokeLater {
                            jsEval("App.addImagePill(${gson.toJson(b64)}, $nameJson)")
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // ── File list ──────────────────────────────────────────────────────────────
    private fun pushFileList() {
        val names = EditorContext.getAllProjectFiles(project).map { it.name }
        val json  = gson.toJson(names)
        pushRaw("""{"type":"file_list","data":$json}""")
    }

    // ── Kotlin → JS helpers ───────────────────────────────────────────────────
    private fun push(type: String, data: String) {
        val json = """{"type":${gson.toJson(type)},"data":${gson.toJson(data)}}"""
        pushRaw(json)
    }

    private fun pushRaw(json: String) {
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
        jsEval("window.onKotlinMessage('$escaped')")
    }

    private fun pushError(msg: String) = push("status", "Error: $msg")

    private fun termLine(line: String) {
        val cls = when {
            line.contains("error", true) || line.contains("FAILED") -> "term-err"
            line.contains("warn", true)  -> "term-warn"
            line.contains("success", true) || line.startsWith("BUILD SUCCESSFUL") -> "term-ok"
            else -> ""
        }
        val escaped = line.replace("\\", "\\\\").replace("'", "\\'")
        jsEval("App.appendTerminal('$escaped', '$cls')")
    }

    private fun jsEval(script: String) {
        browser?.cefBrowser?.executeJavaScript(script, "", 0)
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    // ── Parse file changes from AI response ───────────────────────────────────
    private data class FileChange(val path: String, val content: String, val isNew: Boolean)

    private fun parseFileChanges(response: String): List<FileChange> {
        val list = mutableListOf<FileChange>()
        val fc = Regex("""<file_change\s+path="([^"]+)">([\s\S]*?)</file_change>""")
        val nf = Regex("""<new_file\s+path="([^"]+)">([\s\S]*?)</new_file>""")
        fc.findAll(response).forEach { list.add(FileChange(it.groupValues[1].trim(), it.groupValues[2].trim(), false)) }
        nf.findAll(response).forEach { list.add(FileChange(it.groupValues[1].trim(), it.groupValues[2].trim(), true)) }
        return list
    }

    // ── Image utilities ───────────────────────────────────────────────────────
    private fun resizeImage(src: BufferedImage, max: Int): BufferedImage {
        if (src.width <= max && src.height <= max) return src
        val scale = max.toDouble() / maxOf(src.width, src.height)
        val nw = (src.width * scale).toInt(); val nh = (src.height * scale).toInt()
        val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().also { g ->
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, nw, nh, null); g.dispose()
        }
        return out
    }

    private fun encodeBase64(img: BufferedImage): String {
        val b = ByteArrayOutputStream(); ImageIO.write(img, "PNG", b)
        return java.util.Base64.getEncoder().encodeToString(b.toByteArray())
    }

    // ── Public API for QuickActionsGroup ──────────────────────────────────────
    fun prefillAndSend(prompt: String) {
        val escaped = prompt.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        jsEval("App.prefill('$escaped')")
    }

    companion object {
        const val CLIENT_KEY = "CoforgeAiChatContent"
    }
}
