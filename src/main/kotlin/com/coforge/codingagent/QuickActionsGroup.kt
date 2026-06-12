package com.coforge.codingagent

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Right-click context menu inside the editor.
 * Available only when text is selected.
 * Actions are context-aware: Flutter projects get Dart/widget actions,
 * Android projects get Kotlin/Compose actions.
 */
class QuickActionsGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.isVisible = hasSelection
        e.presentation.text = "Coforge AI"
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project
        val info = project?.let { ProjectTypeDetector.detect(it) }
        val isFlutter = info?.type == ProjectTypeDetector.ProjectType.FLUTTER

        val common = arrayOf(
            quick("Explain Selected Code",        "explain this code in detail, including how it works and why:\n\n"),
            quick("Find & Fix Issues",             "identify and fix all bugs, edge cases, and issues in:\n\n"),
            quick("Refactor for Clarity",          "refactor this code for readability, maintainability, and best practices:\n\n"),
            quick("Optimize Performance",          "identify and fix performance issues and anti-patterns in:\n\n"),
            quick("Add Documentation",             "add complete inline documentation to:\n\n"),
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
        return common + platformSpecific
    }

    private fun quick(label: String, prefix: String) = object : AnAction(label), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: return
            openChatWithPrompt(project, "$prefix```\n$selection\n```")
        }
    }
}

fun openChatWithPrompt(project: Project, prompt: String) {
    val tw = ToolWindowManager.getInstance(project).getToolWindow("Coforge AI Agent") ?: return
    tw.show {
        val content = tw.contentManager.getContent(0) ?: return@show
        findChatContent(content.component)?.prefillAndSend(prompt)
    }
}

private fun findChatContent(c: java.awt.Component): ChatToolWindowContent? {
    if (c is ChatToolWindowContent) return c
    if (c is java.awt.Container) for (child in c.components) findChatContent(child)?.let { return it }
    return null
}
