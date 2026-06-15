package com.coforge.codingagent

import com.intellij.openapi.options.Configurable
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

class AppSettingsConfigurable : Configurable {
    private var component: AppSettingsComponent? = null

    @Nls(capitalization = Nls.Capitalization.Title)
    override fun getDisplayName() = "Coforge AI Agent Settings"

    override fun createComponent(): JComponent? {
        component = AppSettingsComponent()
        return component?.panel
    }

    override fun isModified(): Boolean {
        val s = AppSettingsState.instance
        val c = component ?: return false
        return c.kimiKeyText    != s.kimiApiKey    || c.kimiModelText    != s.kimiModel    ||
               c.geminiKeyText  != s.geminiApiKey  || c.geminiModelText  != s.geminiModel  ||
               c.gptKeyText     != s.gptApiKey     || c.gptModelText     != s.gptModel     ||
               c.inlineEnabled  != s.inlineCompletionsEnabled
    }

    override fun apply() {
        val s = AppSettingsState.instance
        val c = component ?: return
        s.kimiApiKey   = c.kimiKeyText;    s.kimiModel   = c.kimiModelText
        s.geminiApiKey = c.geminiKeyText;  s.geminiModel = c.geminiModelText
        s.gptApiKey    = c.gptKeyText;     s.gptModel    = c.gptModelText
        s.inlineCompletionsEnabled = c.inlineEnabled
    }

    override fun reset() {
        val s = AppSettingsState.instance
        val c = component ?: return
        c.kimiKeyText    = s.kimiApiKey;    c.kimiModelText    = s.kimiModel
        c.geminiKeyText  = s.geminiApiKey;  c.geminiModelText  = s.geminiModel
        c.gptKeyText     = s.gptApiKey;     c.gptModelText     = s.gptModel
        c.inlineEnabled  = s.inlineCompletionsEnabled
    }

    override fun disposeUIResources() { component = null }
}
