package com.coforge.codingagent

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

object EditorContext {

    private const val MAX_CONTEXT_CHARS = 24_000
    private const val CURSOR_LINES = 80

    /**
     * Assembles the full context sent to AI models:
     *   1. PSI-aware active file context (cursor position, class/method, diagnostics)
     *   2. Tagged files
     *   3. Recent build errors
     *   4. Uncommitted git changes (summary)
     *   5. Project structure
     */
    fun getSmartContext(project: Project, taggedFiles: List<VirtualFile>): String {
        val parts = mutableListOf<String>()

        getActiveFileContext(project)?.let { parts.add(it) }
        taggedFiles.forEach { file -> getFileContext(project, file)?.let { parts.add(it) } }
        getBuildErrors(project)?.let { parts.add(it) }
        getGitContext(project)?.let { parts.add(it) }
        parts.add(getProjectStructure(project))

        return parts.joinToString("\n\n---\n\n").take(MAX_CONTEXT_CHARS)
    }

    // ─── 1. Active file: PSI + cursor + diagnostics ───────────────────────────

    private fun getActiveFileContext(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val doc = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return null
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null

        val selection = editor.selectionModel.selectedText
        val caretOffset = editor.caretModel.offset

        val sb = StringBuilder()
        sb.append("Active file: ${file.name}")

        // PSI context: enclosing class and method
        getPsiContext(psiFile, caretOffset)?.let { sb.append(" ($it)") }
        sb.append("\n")

        // Diagnostics at cursor
        getDiagnostics(doc, editor)?.let { sb.append("$it\n") }

        // Code snippet
        if (!selection.isNullOrBlank()) {
            sb.append("Selected code:\n```${file.extension ?: ""}\n$selection\n```")
        } else {
            val caretLine = doc.getLineNumber(caretOffset.coerceIn(0, doc.textLength - 1))
            val startLine = maxOf(0, caretLine - CURSOR_LINES)
            val endLine   = minOf(doc.lineCount - 1, caretLine + CURSOR_LINES)
            val snippet   = doc.getText(TextRange(doc.getLineStartOffset(startLine), doc.getLineEndOffset(endLine)))
            sb.append("Code (lines ${startLine + 1}–${endLine + 1}, cursor at ${caretLine + 1}):\n```${file.extension ?: ""}\n$snippet\n```")
        }

        return sb.toString()
    }

    // ─── PSI: class name, method name, imports ────────────────────────────────

    private fun getPsiContext(psiFile: PsiFile, offset: Int): String? {
        val element = psiFile.findElementAt(offset) ?: return null

        val parts = mutableListOf<String>()

        // Enclosing class
        PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.let { cls ->
            val superClause = cls.superClass?.name?.let { " : $it" } ?: ""
            parts.add("class ${cls.name}$superClause")
        }

        // Enclosing method
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)?.let { method ->
            val params = method.parameterList.parameters.joinToString(", ") {
                "${it.name}: ${it.type.presentableText}"
            }
            parts.add("fun ${method.name}($params)")
        }

        // Top-level imports (condensed)
        val imports = (psiFile as? PsiJavaFile)?.importList?.importStatements
            ?.mapNotNull { it.qualifiedName }
            ?.filter { it.contains("android") || it.contains("kotlinx") || it.contains("hilt") }
            ?.take(8)
        if (!imports.isNullOrEmpty()) parts.add("imports: ${imports.joinToString(", ")}")

        return if (parts.isEmpty()) null else parts.joinToString(" | ")
    }

    // ─── Diagnostics: errors and warnings at cursor region ───────────────────

    private fun getDiagnostics(doc: Document, editor: com.intellij.openapi.editor.Editor): String? {
        val markupModel = editor.markupModel
        val highlights = markupModel.allHighlighters
            .filter { it.errorStripeTooltip is HighlightInfo }
            .map { it.errorStripeTooltip as HighlightInfo }
            .filter {
                it.severity >= HighlightSeverity.WARNING &&
                it.actualStartOffset in 0 until doc.textLength
            }
            .take(5)

        if (highlights.isEmpty()) return null
        val lines = highlights.joinToString("\n") { h ->
            val line = doc.getLineNumber(h.actualStartOffset) + 1
            "  ${h.severity.name} line $line: ${h.description}"
        }
        return "IDE diagnostics:\n$lines"
    }

    // ─── 2. Tagged file context ───────────────────────────────────────────────

    private fun getFileContext(project: Project, file: VirtualFile): String? {
        val content = PsiManager.getInstance(project).findFile(file)?.text ?: return null
        return "Tagged file: ${file.path.removePrefix(project.basePath ?: "")}\n```${file.extension ?: ""}\n${content.take(8000)}\n```"
    }

    // ─── 3. Build errors from BuildContextService ─────────────────────────────

    private fun getBuildErrors(project: Project): String? {
        return try {
            val svc = BuildContextService.getInstance(project)
            val errors = svc.getRecentIssues()
            errors.ifBlank { null }
        } catch (_: Exception) { null }
    }

    // ─── 4. Git context: uncommitted changes summary ─────────────────────────

    private fun getGitContext(project: Project): String? {
        return try {
            val clm = ChangeListManager.getInstance(project)
            val changes = clm.allChanges.take(10)
            if (changes.isEmpty()) return null
            val summary = changes.joinToString("\n") { change ->
                val type = change.type.name.lowercase()
                "  $type: ${change.virtualFile?.path?.removePrefix(project.basePath ?: "") ?: "?"}"
            }
            "Uncommitted changes (${changes.size}):\n$summary"
        } catch (_: Exception) { null }
    }

    // ─── 5. Project structure ─────────────────────────────────────────────────

    private fun getProjectStructure(project: Project): String {
        val files = mutableListOf<String>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && isSourceFile(file))
                files.add(file.path.removePrefix(project.basePath ?: ""))
            true
        }
        val listing = files.take(80).joinToString("\n")
        val extra = if (files.size > 80) "\n  … ${files.size - 80} more files" else ""
        return "Project source files:\n$listing$extra"
    }

    // ─── Indexed codebase context (called from background thread, no PSI) ──────

    /**
     * Uses ProjectIndexer to find the most relevant files for the given query
     * and returns their content as a context string.
     *
     * This is the key capability that gives the agent full codebase awareness:
     * if the user mentions UserRepository, it finds and includes that file even
     * if it's not currently open.
     */
    fun getIndexedContext(project: Project, query: String): String {
        val relevant = ProjectIndexer.findRelevant(project, query, topN = 5)
        if (relevant.isEmpty()) return ""
        return buildString {
            append("RELEVANT CODEBASE FILES (found by indexing your project):\n")
            relevant.forEach { file ->
                append("\n--- ${file.relativePath} ---\n")
                append("```${file.relativePath.substringAfterLast('.').ifBlank { "kotlin" }}\n")
                append(file.content.take(3000))
                append("\n```\n")
            }
        }
    }

    // ─── Public helpers ───────────────────────────────────────────────────────

    fun getAllProjectFiles(project: Project): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && isSourceFile(file)) files.add(file)
            true
        }
        return files
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        return ext in setOf(
            // Android Native
            "kt", "java", "xml", "gradle", "kts", "pro",
            // Flutter / Dart
            "dart", "yaml", "arb",
            // Shared
            "json", "toml", "md"
        )
    }
}
