package com.coforge.codingagent

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.roots.ProjectFileIndex

/**
 * EditorContext handles deep analysis of project files and active editor context.
 */
object EditorContext {

    /**
     * Gets context from the currently open editor.
     */
    fun getCurrentFileContext(project: Project): String {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val document = editor?.document
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        if (editor == null || document == null || file == null) {
            return "No file currently open."
        }

        val fileName = file.name
        val fullText = document.text
        val selection = editor.selectionModel.selectedText

        return """
            Active File: $fileName
            ---
            ${if (selection != null) "Selected Code:\n$selection" else "Full Content:\n$fullText"}
        """.trimIndent()
    }

    /**
     * Gets context from a list of tagged files.
     */
    fun getFilesContext(project: Project, files: List<VirtualFile>): String {
        val contextBuilder = StringBuilder()
        files.forEach { file ->
            val psiFile = PsiManager.getInstance(project).findFile(file)
            val content = psiFile?.text ?: "Could not read content."
            contextBuilder.append("File: ${file.path}\nContent:\n$content\n---\n")
        }
        return contextBuilder.toString()
    }

    /**
     * Returns all source files in the project for tagging.
     */
    fun getAllProjectFiles(project: Project): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && isSourceFile(file)) {
                files.add(file)
            }
            true
        }
        return files
    }

    /**
     * Provides a high-level summary of the project structure.
     */
    fun getProjectSummary(project: Project): String {
        val summary = StringBuilder("Project Structure Summary:\n")
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && isSourceFile(file)) {
                summary.append("- ${file.path}\n")
            }
            true
        }
        return summary.toString()
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        return ext == "kt" || ext == "java" || ext == "xml" || ext == "gradle" || ext == "kts"
    }
}
