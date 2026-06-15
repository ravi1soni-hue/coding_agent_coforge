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

    private val gson    = Gson()
    private val history = ProjectHistoryService.getInstance(project).load(project)
    private val isFlutter get() =
        ProjectTypeDetector.detect(project).type == ProjectTypeDetector.ProjectType.FLUTTER

    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?

    // ── Undo stack: path → original content before last write ─────────────────
    private val undoStack = ArrayDeque<Map<String, String>>()  // each entry = one apply batch

    // ── In-flight AI request tracking ─────────────────────────────────────────
    @Volatile private var activeThread: Thread? = null

    init {
        if (JBCefApp.isSupported()) {
            val b        = JBCefBrowser()
            browser      = b
            jsQuery      = JBCefJSQuery.create(b)
            contentPanel = b.component

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
            "stop"          -> { AiService.stop(); activeThread?.interrupt() }
            "reset"         -> onReset()
            "retry"         -> onRetry()
            "settings"      -> openSettings()
            "pick_image"    -> pickImage()
            "file_list"     -> sendFileList()
            "copy"          -> copy(msg["text"]?.asString ?: "")
            "apply_file"    -> applyFile(msg)
            "apply_all"     -> applyAll(msg)
            "undo"          -> undoLastApply()
            "run_tests"     -> runTests()
            "run_build"     -> runBuild()
            "run_lint"      -> runLint()
        }
    }

    private fun onSend(msg: JsonObject) {
        val text   = msg["text"]?.asString?.trim() ?: return
        val images = msg.getAsJsonArray("images")?.map { it.asString } ?: emptyList()
        val tagged = msg.getAsJsonArray("tagged")?.map { it.asString } ?: emptyList()

        // Cancel any in-flight AI request
        AiService.stop()
        activeThread?.interrupt()

        // Smart history: keep all recent turns, drop oldest pair when > 80 messages
        if (history.size >= 80) {
            // Remove the oldest user+assistant pair
            val firstUser = history.indexOfFirst { it.role == "user" }
            if (firstUser >= 0) {
                history.removeAt(firstUser)
                val nextAss = history.indexOfFirst { it.role == "assistant" }
                if (nextAss >= 0) history.removeAt(nextAss)
            }
        }
        history.add(Message("user", text))

        // Gather PSI context on a pooled thread with a read action.
        // PSI / ProjectFileIndex APIs throw if called without a read lock
        // (the CEF callback thread doesn't hold one).
        ApplicationManager.getApplication().executeOnPooledThread {
            activeThread = Thread.currentThread()
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

            // Semantic graph search — runs on pool thread, no read lock needed
            val graphCtx: String = try {
                EditorContext.getIndexedContext(project, text)
            } catch (_: Exception) { "" }

            val fullContext = if (graphCtx.isNotBlank()) "$context\n\n---\n\n$graphCtx" else context
            warnIfHugeContext(fullContext)

            AiService.callAgentChain(
                userMessage = text,
                history     = history.dropLast(1),
                context     = fullContext,
                images      = images,
                project     = project,
                onStatus    = { s   -> push("status",    s)   },
                onReasoning = { r   -> push("reasoning", r)   },
                onToken     = { tok -> push("token",     tok) },
                onComplete  = { full ->
                    history.add(Message("assistant", full))
                    ProjectHistoryService.getInstance(project).save(project, history)
                    push("complete", full)
                    activeThread = null
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
        ProjectHistoryService.getInstance(project).clear(project)
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
        val search  = msg["search"]?.asString?.takeIf { it.isNotBlank() }

        // Show diff preview before writing
        ApplicationManager.getApplication().invokeAndWait {
            val base = project.basePath ?: return@invokeAndWait
            val file = java.io.File("$base/$path")
            val oldContent = if (file.exists()) file.readText(Charsets.UTF_8) else ""
            val newContent = if (search != null) oldContent.let { cur ->
                val norm = cur.lines().joinToString("\n") { it.trimEnd() }
                val normSearch = search.lines().joinToString("\n") { it.trimEnd() }
                val idx = norm.indexOf(normSearch)
                if (idx >= 0) cur.substring(0, idx) + content + cur.substring(idx + normSearch.length)
                else cur.replaceFirst(search, content)
            } else content

            val dlg = DiffPreviewDialog(project, path, oldContent, newContent, isNew)
            if (!dlg.showAndGet()) {
                pushRaw("""{"type":"apply_skip","path":${gson.toJson(path)}}""")
                return@invokeAndWait
            }

            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val snap = if (file.exists()) mapOf(path to oldContent) else mapOf(path to "")
                    writeFile(path, content, isNew, search)
                    undoStack.addLast(snap)
                    if (undoStack.size > 20) undoStack.removeFirst()
                    pushRaw("""{"type":"applied","path":${gson.toJson(path)},"can_undo":true}""")
                } catch (e: Exception) {
                    pushRaw("""{"type":"apply_err","path":${gson.toJson(path)},"msg":${gson.toJson(e.message)}}""")
                }
            }
        }
    }

    private fun applyAll(msg: JsonObject) {
        val files = msg.getAsJsonArray("files") ?: return
        val applied = mutableListOf<String>()
        val batchSnap = mutableMapOf<String, String>()  // undo snapshot for entire batch

        // Collect diffs on EDT before writing so user can preview every file
        val fileList = files.map { it.asJsonObject }.filter {
            it["path"]?.asString != null && it["content"]?.asString != null
        }

        ApplicationManager.getApplication().invokeAndWait {
            val base = project.basePath ?: return@invokeAndWait
            fileList.forEach { o ->
                val path    = o["path"].asString.trim()
                val content = o["content"].asString
                val isNew   = o["isNew"]?.asBoolean ?: false
                val search  = o["search"]?.asString?.takeIf { it.isNotBlank() }
                val existing = java.io.File("$base/$path")
                val oldContent = if (existing.exists()) existing.readText(Charsets.UTF_8) else ""
                val newContent = if (search != null) oldContent.let { cur ->
                    val norm = cur.lines().joinToString("\n") { it.trimEnd() }
                    val normS = search.lines().joinToString("\n") { it.trimEnd() }
                    val idx = norm.indexOf(normS)
                    if (idx >= 0) cur.substring(0, idx) + content + cur.substring(idx + normS.length)
                    else cur.replaceFirst(search, content)
                } else content
                val dlg = DiffPreviewDialog(project, path, oldContent, newContent, isNew)
                if (!dlg.showAndGet()) {
                    pushRaw("""{"type":"apply_skip","path":${gson.toJson(path)}}""")
                    return@forEach
                }
                // Write approved file immediately
                try {
                    if (existing.exists()) batchSnap[path] = oldContent else batchSnap[path] = ""
                    WriteCommandAction.runWriteCommandAction(project) {
                        writeFile(path, content, isNew, search)
                    }
                    applied.add(path)
                    pushRaw("""{"type":"applied","path":${gson.toJson(path)}}""")
                } catch (e: Exception) {
                    pushRaw("""{"type":"apply_err","path":${gson.toJson(path)},"msg":${gson.toJson(e.message)}}""")
                }
            }
        }

        if (applied.isNotEmpty()) {
            undoStack.addLast(batchSnap)
            if (undoStack.size > 10) undoStack.removeFirst()
            pushRaw("""{"type":"batch_applied","count":${applied.size},"can_undo":true}""")
            // Run verify only after batch apply — not after single file
            SwingUtilities.invokeLater { autoVerify(applied) }
        }
    }

    /**
     * Undo the last apply (or batch apply). Restores each file to its pre-apply content.
     */
    private fun undoLastApply() {
        val snap = undoStack.removeLastOrNull() ?: run {
            push("status", "Nothing to undo")
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            snap.forEach { (path, oldContent) ->
                try {
                    val base = project.basePath ?: return@forEach
                    val file = java.io.File("$base/$path")
                    if (oldContent.isEmpty()) {
                        // File was new — delete it
                        file.delete()
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                            .refreshAndFindFileByIoFile(file)
                    } else {
                        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                            .refreshAndFindFileByIoFile(file)
                        val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
                        doc?.setText(oldContent)
                    }
                } catch (_: Exception) {}
            }
        }
        push("status", "Undone ✓")
        js("""App.sysLine("↩️ Last apply undone (${snap.size} file(s) restored)")""")
    }

    /**
     * Auto-verify — runs analyze/lint after BATCH apply only (never single-file).
     * If errors found, triggers AI auto-fix. Does NOT add to conversation history.
     */
    private fun autoVerify(changedPaths: List<String>) {
        push("status", "Verifying changes...")
        js("""App.openTerminal("Auto-verify...")""")
        val exec = if (isFlutter)
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit ->
                TerminalExecutor.flutterAnalyze(project, onLine, onDone) }
        else
            { onLine: (String)->Unit, onDone: (TerminalExecutor.CommandResult)->Unit ->
                TerminalExecutor.runLint(project, onLine, onDone) }
        runCmd(exec) { ok, out ->
            if (ok) {
                push("status", "All checks pass ✓")
                js("""App.sysLine("✅ Auto-verify passed")""")
                startTestLoop(changedPaths, 1)
            } else {
                js("""App.sysLine("⚠️ Issues found — AI fixing..."); App.startStream()""")
                // Do NOT add to history — this is an internal system action, not user turn
                AiService.fixLintErrors(
                    lintOutput  = out,
                    project     = project,
                    onStatus    = { s   -> push("status", s) },
                    onReasoning = { r   -> push("reasoning", r) },
                    onToken     = { tok -> push("token", tok) },
                    onComplete  = { full ->
                        if (full.isNotBlank()) {
                            push("complete", full)
                        }
                    }
                )
            }
        }
    }

    private fun writeFile(path: String, content: String, isNew: Boolean, search: String? = null) {
        val base = project.basePath ?: throw IllegalStateException("No project base path")
        val file = File("$base/$path")

        if (isNew) {
            // New file — write complete content
            file.parentFile?.mkdirs()
            FileUtil.writeToFile(file, content)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            return
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: run {
                // File doesn't exist yet even though isNew=false — create it
                file.parentFile?.mkdirs()
                FileUtil.writeToFile(file, content)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                return
            }
        val doc = FileDocumentManager.getInstance().getDocument(vf)
            ?: throw IllegalStateException("Cannot open document for: $path")

        if (search != null) {
            // Surgical search-replace — only the targeted block changes, nothing else is touched
            val current = doc.text

            // Try exact match first
            if (current.contains(search)) {
                doc.setText(current.replaceFirst(search, content))
                return
            }

            // Whitespace-normalized fallback: handles minor indentation/line-ending differences
            val normCurrent = current.lines().joinToString("\n") { it.trimEnd() }
            val normSearch  = search.lines().joinToString("\n") { it.trimEnd() }
            val idx = normCurrent.indexOf(normSearch)
            if (idx >= 0) {
                // Map the normalized index back to the original string
                val before = current.substring(0, idx)
                val after  = current.substring(idx + normSearch.length)
                doc.setText(before + content + after)
                return
            }

            throw IllegalStateException(
                "Could not find the search block in $path.\n" +
                "The file may have changed since the AI read it. Please retry."
            )
        } else {
            // No search block — full replacement (legacy fallback, should not normally happen)
            doc.setText(content)
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
                        onStatus    = { s   -> push("status", s) },
                        onReasoning = { r2  -> push("reasoning", r2) },
                        onToken     = { tok -> push("token", tok) },
                        onComplete = { full ->
                            history.add(Message("assistant", full))
                            ProjectHistoryService.getInstance(project).save(project, history)
                            push("complete", full)
                            val fixes = parseChanges(full)
                            if (fixes.isEmpty()) { js("""App.sysLine("⚠️ No fix returned.")"""); return@fixTestFailures }
                            WriteCommandAction.runWriteCommandAction(project) {
                                fixes.forEach { fc -> try { writeFile(fc.path, fc.content, fc.isNew, fc.search) } catch (_: Exception) {} }
                            }
                            startTestLoop((changed + fixes.map { it.path }).distinct(), attempt + 1)
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
        // PSI access requires a read lock — must be on pooled thread, not EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val ctx = try {
                ApplicationManager.getApplication().runReadAction<String> {
                    EditorContext.getSmartContext(project, emptyList())
                }
            } catch (_: Exception) { "" }
            AiService.callAgentChain(
                userMessage = prompt, history = history.dropLast(1), context = ctx, project = project,
                onStatus    = { s   -> push("status", s) },
                onReasoning = { _   -> },
                onToken     = { tok -> push("token", tok) },
                onComplete  = { full ->
                    history.add(Message("assistant", full))
                    ProjectHistoryService.getInstance(project).save(project, history)
                    push("complete", full)
                }
            )
        }
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
        // Embed JSON as a JS string literal — escape all control chars + quotes + backslashes
        val escaped = buildString {
            for (c in json) when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        js("window.onK('$escaped')")
    }

    private fun termLine(line: String) {
        val cls = when {
            line.contains("error", true) || line.contains("FAILED") -> "err"
            line.contains("warn",  true)  -> "warn"
            line.contains("success", true)-> "ok"
            else -> ""
        }
        val escaped = buildString {
            for (c in line) when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        js("App.termLine('$escaped','$cls')")
    }

    private fun js(script: String) = browser?.cefBrowser?.executeJavaScript(script, "", 0)

    // File change parsing — supports <search>/<replace> surgical edits
    private data class FC(val path: String, val content: String, val isNew: Boolean, val search: String? = null)

    // Strip only leading/trailing newlines — preserve internal indentation
    private fun String.trimNewlines(): String {
        var s = this
        while (s.startsWith("\n") || s.startsWith("\r")) s = s.drop(1)
        while (s.endsWith("\n") || s.endsWith("\r")) s = s.dropLast(1)
        return s
    }

    private fun parseChanges(r: String): List<FC> {
        val list = mutableListOf<FC>()
        Regex("""<file_change\s+path="([^"]+)">([\s\S]*?)</file_change>""").findAll(r)
            .forEach { m ->
                val path = m.groupValues[1].trim()
                val body = m.groupValues[2]
                val sm = Regex("""<search>([\s\S]*?)</search>""").find(body)
                val rm = Regex("""<replace>([\s\S]*?)</replace>""").find(body)
                if (sm != null && rm != null) {
                    // trimNewlines only — preserve indentation inside blocks
                    list.add(FC(path, rm.groupValues[1].trimNewlines(), false, sm.groupValues[1].trimNewlines()))
                } else {
                    list.add(FC(path, body.trimNewlines(), false, null))
                }
            }
        Regex("""<new_file\s+path="([^"]+)">([\s\S]*?)</new_file>""").findAll(r)
            .forEach { m -> list.add(FC(m.groupValues[1].trim(), m.groupValues[2].trimNewlines(), true, null)) }
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
        val esc = buildString {
            for (c in prompt) when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        js("App.prefill('$esc')")
    }

    /** Reads git diff (staged or HEAD), then prefills the AI chat for commit message generation. */
    fun triggerCommitMessage() {
        push("status", "Reading git changes...")
        js("""App.sysLine("📝 Reading git changes...")""")
        val diff = StringBuilder()
        TerminalExecutor.gitDiff(
            project,
            { line -> diff.append(line) },
            { _ ->
                val diffText = diff.toString().trim()
                if (diffText.isBlank()) {
                    push("status", "No changes found")
                    js("""App.sysLine("⚠️ No changes found. Make some edits first.")""")
                    return@gitDiff
                }
                val prompt = "Generate a git commit message (Conventional Commits format: feat/fix/refactor/docs/test/chore) for these changes. Output ONLY the commit message (subject ≤72 chars + optional body), nothing else.\n\n```diff\n${diffText.take(6000)}\n```"
                ApplicationManager.getApplication().invokeLater {
                    val promptEsc = buildString {
                        for (c in prompt) when (c) {
                            '\\' -> append("\\\\")
                            '\'' -> append("\\'")
                            '\n' -> append("\\n")
                            '\r' -> {}
                            else -> append(c)
                        }
                    }
                    js("App.prefill('$promptEsc')")
                }
            }
        )
    }

    /** Approximate token count warning (1 token ≈ 4 chars). */
    private fun warnIfHugeContext(context: String) {
        val approxTokens = context.length / 4
        if (approxTokens > 80_000) {
            push("status", "⚠️ Context ~${approxTokens / 1000}k tokens — may hit model limit")
        }
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
