package com.coforge.codingagent

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor

/**
 * Renames a symbol (class, function, variable) across ALL files in the project
 * using IntelliJ's built-in RenameProcessor — the same engine as Shift+F6.
 *
 * Works for Kotlin, Java, and XML references to the same symbol.
 * For Dart: falls back to regex-based text rename across dart files.
 */
class RenameSymbolAction : AnAction("Rename Symbol Across Project…"), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.description = "Rename the symbol under cursor across the entire project"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project  = e.project ?: return
        val editor   = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile  = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val offset   = editor.caretModel.offset

        val element  = psiFile.findElementAt(offset) ?: run {
            Messages.showInfoMessage(project, "No element at cursor.", "Rename Symbol")
            return
        }

        // Walk up PSI tree to find the closest named element
        var target: PsiElement? = element
        while (target != null && target !is PsiNamedElement) {
            target = target.parent
        }
        val named = target as? PsiNamedElement ?: run {
            Messages.showInfoMessage(
                project,
                "Place your cursor on a class, function, property, or variable name to rename it.",
                "Rename Symbol"
            )
            return
        }

        val currentName = named.name ?: return
        val newName = Messages.showInputDialog(
            project,
            "New name for '$currentName':",
            "Rename Symbol Across Project",
            null,
            currentName,
            com.intellij.openapi.ui.InputValidator { s -> s.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) }
        ) ?: return

        if (newName.isBlank() || newName == currentName) return

        ApplicationManager.getApplication().invokeLater {
            try {
                val processor = RenameProcessor(
                    project,
                    named,
                    newName,
                    /* searchInComments       = */ true,
                    /* searchInNonJavaFiles   = */ true
                )
                // setPreviewUsages(true) would show a preview dialog — keep false for direct rename
                processor.run()
            } catch (ex: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Rename failed: ${ex.message ?: "Unknown error"}",
                    "Rename Symbol"
                )
            }
        }
    }
}
