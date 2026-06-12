package com.coforge.codingagent

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatToolWindow = ChatToolWindowContent(project)
        val content = ContentFactory.getInstance().createContent(chatToolWindow.contentPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
