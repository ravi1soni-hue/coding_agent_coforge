package com.coforge.codingagent

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Right-click context menu inside the editor.
 * Available when text is selected (selection actions) or always (file-level actions).
 * Context-aware: Flutter projects get Dart/widget actions, Android projects get Kotlin/Compose actions.
 */
class QuickActionsGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.text = "Coforge AI"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project
        val info = project?.let { ProjectTypeDetector.detect(it) }
        val isFlutter = info?.type == ProjectTypeDetector.ProjectType.FLUTTER

        val selectionActions: Array<AnAction> = arrayOf(
            quick("Explain Selected Code",        "explain this code in detail, including how it works and why:\n\n"),
            quick("Find & Fix Issues",             "identify and fix all bugs, edge cases, and issues in:\n\n"),
            quick("Refactor for Clarity",          "refactor this code for readability, maintainability, and best practices:\n\n"),
            quick("Optimize Performance",          "identify and fix performance issues and anti-patterns in:\n\n"),
            quick("Add Documentation",             "add complete inline documentation to:\n\n"),
            quick("Security Review",               "perform a security review of this code — identify vulnerabilities, injection risks, and auth issues:\n\n"),
        )

        val platformSpecific: Array<AnAction> = if (isFlutter) arrayOf(
            quick("Generate Widget Tests",         "write comprehensive widget tests using flutter_test for:\n\n"),
            quick("Convert to Stateless Widget",   "refactor this StatefulWidget to StatelessWidget with hooks or Riverpod:\n\n"),
            quick("Extract to Reusable Widget",    "extract this into a reusable, configurable Flutter widget:\n\n"),
            quick("Add Riverpod Provider",         "add a Riverpod provider/notifier for the state management of:\n\n"),
        ) else arrayOf(
            quick("Generate Unit Tests",           "generate comprehensive unit tests using JUnit5 and MockK for:\n\n"),
            quick("Convert to Jetpack Compose",    "convert this XML layout / View-based code to Jetpack Compose:\n\n"),
            quick("Extract to ViewModel",          "move business logic from this code into a ViewModel with StateFlow:\n\n"),
            quick("Add KDoc Documentation",        "add complete KDoc documentation with @param and @return tags to:\n\n"),
        )

        val fileActions: Array<AnAction> = arrayOf(
            quickFile("Write Tests for This File", if (isFlutter)
                "write comprehensive tests (flutter_test) covering all functions and edge cases in this file:\n\n"
            else
                "write comprehensive unit tests (JUnit5 + MockK) covering all functions and edge cases in this file:\n\n"),
            Separator.create(),
            object : AnAction("Generate Commit Message"), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    val proj = e.project ?: return
                    openChat(proj) { chat -> chat.triggerCommitMessage() }
                }
            },
            Separator.create(),
            RenameSymbolAction()
        )

        return selectionActions + platformSpecific + fileActions
    }

    private fun quick(label: String, prefix: String): AnAction = object : AnAction(label), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        }
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: return
            openChatWithPrompt(project, "$prefix```\n$selection\n```")
        }
    }

    private fun quickFile(label: String, prefix: String): AnAction = object : AnAction(label), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            // Use selection if present, otherwise full file
            val text = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
                ?: editor.document.text
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            val ext = file?.extension ?: ""
            openChatWithPrompt(project, "$prefix```$ext\n${text.take(8000)}\n```")
        }
    }
}

fun openChatWithPrompt(project: Project, prompt: String) {
    openChat(project) { chat -> chat.prefillAndSend(prompt) }
}

fun openChat(project: Project, action: (ChatToolWindowContent) -> Unit) {
    val tw = ToolWindowManager.getInstance(project).getToolWindow("Coforge AI Agent") ?: return
    tw.show {
        val content = tw.contentManager.getContent(0) ?: return@show
        findChatContent(content.component)?.let { action(it) }
    }
}

private fun findChatContent(c: java.awt.Component): ChatToolWindowContent? {
    if (c is javax.swing.JComponent) {
        val stored = c.getClientProperty(ChatToolWindowContent.CLIENT_KEY)
        if (stored is ChatToolWindowContent) return stored
    }
    if (c is java.awt.Container) {
        for (child in c.components) findChatContent(child)?.let { return it }
    }
    return null
}
