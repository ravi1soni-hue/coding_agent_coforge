package com.coforge.codingagent

import com.intellij.build.BuildProgressListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Wire BuildContextService into the build event bus so IDE errors reach AI context.
        // Must be done here (earliest project-open point) rather than in the service itself.
        val buildSvc = BuildContextService.getInstance(project)
        project.messageBus.connect().subscribe(BuildProgressListener.TOPIC, buildSvc)

        val chatContent = ChatToolWindowContent(project)
        // Wrap in JPanel so putClientProperty works even when contentPanel is a
        // JCEF heavyweight component (browser.component is AWT-backed on some JBR builds).
        val wrapper = JPanel(BorderLayout()).apply {
            add(chatContent.contentPanel, BorderLayout.CENTER)
            putClientProperty(ChatToolWindowContent.CLIENT_KEY, chatContent)
        }
        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
