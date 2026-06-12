package com.coforge.codingagent

import com.intellij.openapi.options.Configurable
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

class AppSettingsConfigurable : Configurable {
    private var settingsComponent: AppSettingsComponent? = null

    @Nls(capitalization = Nls.Capitalization.Title)
    override fun getDisplayName(): String = "Coforge AI Agent Settings"

    override fun createComponent(): JComponent? {
        settingsComponent = AppSettingsComponent()
        return settingsComponent?.panel
    }

    override fun isModified(): Boolean {
        val settings = AppSettingsState.instance
        val component = settingsComponent ?: return false
        return component.claudeKeyText != settings.claudeApiKey ||
               component.claudeModelText != settings.claudeModel ||
               component.kimiKeyText != settings.kimiApiKey ||
               component.kimiModelText != settings.kimiModel ||
               component.geminiKeyText != settings.geminiApiKey ||
               component.geminiModelText != settings.geminiModel ||
               component.gptKeyText != settings.gptApiKey ||
               component.gptModelText != settings.gptModel
    }

    override fun apply() {
        val settings = AppSettingsState.instance
        val component = settingsComponent ?: return
        settings.claudeApiKey = component.claudeKeyText
        settings.claudeModel = component.claudeModelText
        settings.kimiApiKey = component.kimiKeyText
        settings.kimiModel = component.kimiModelText
        settings.geminiApiKey = component.geminiKeyText
        settings.geminiModel = component.geminiModelText
        settings.gptApiKey = component.gptKeyText
        settings.gptModel = component.gptModelText
    }

    override fun reset() {
        val settings = AppSettingsState.instance
        val component = settingsComponent ?: return
        component.claudeKeyText = settings.claudeApiKey
        component.claudeModelText = settings.claudeModel
        component.kimiKeyText = settings.kimiApiKey
        component.kimiModelText = settings.kimiModel
        component.geminiKeyText = settings.geminiApiKey
        component.geminiModelText = settings.geminiModel
        component.gptKeyText = settings.gptApiKey
        component.gptModelText = settings.gptModel
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }
}
