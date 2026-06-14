package com.coforge.codingagent

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatContent = ChatToolWindowContent(project)
        // JCEF component is an AWT heavyweight — wrap in JPanel to support putClientProperty
        // so QuickActionsGroup can retrieve the ChatToolWindowContent instance
        val wrapper = JPanel(BorderLayout())
        wrapper.add(chatContent.contentPanel, BorderLayout.CENTER)
        wrapper.putClientProperty(ChatToolWindowContent.CLIENT_KEY, chatContent)
        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
