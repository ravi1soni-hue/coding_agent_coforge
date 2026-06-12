package com.coforge.codingagent

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel
import com.intellij.ui.TitledSeparator

/**
 * UI Component for the Coforge AI Agent Settings page.
 */
class AppSettingsComponent {
    val panel: JPanel
    
    private val claudeKeyField = JBTextField()
    private val claudeModelField = JBTextField()
    
    private val kimiKeyField = JBTextField()
    private val kimiModelField = JBTextField()
    
    private val geminiKeyField = JBTextField()
    private val geminiModelField = JBTextField()
    
    private val gptKeyField = JBTextField()
    private val gptModelField = JBTextField()

    init {
        panel = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Claude Settings"))
            .addLabeledComponent(JBLabel("API Key: "), claudeKeyField, 1)
            .addLabeledComponent(JBLabel("Model ID: "), claudeModelField, 1)
            
            .addComponent(TitledSeparator("Kimi Settings"))
            .addLabeledComponent(JBLabel("API Key: "), kimiKeyField, 1)
            .addLabeledComponent(JBLabel("Model ID: "), kimiModelField, 1)
            
            .addComponent(TitledSeparator("Gemini Settings"))
            .addLabeledComponent(JBLabel("API Key: "), geminiKeyField, 1)
            .addLabeledComponent(JBLabel("Model ID: "), geminiModelField, 1)
            
            .addComponent(TitledSeparator("GPT Settings"))
            .addLabeledComponent(JBLabel("API Key: "), gptKeyField, 1)
            .addLabeledComponent(JBLabel("Model ID: "), gptModelField, 1)
            
            .addComponentFillVertically(JPanel(), 8)
            .panel
    }

    var claudeKeyText: String
        get() = claudeKeyField.text.trim()
        set(newText) { claudeKeyField.text = newText }

    var claudeModelText: String
        get() = claudeModelField.text.trim()
        set(newText) { claudeModelField.text = newText }

    var kimiKeyText: String
        get() = kimiKeyField.text.trim()
        set(newText) { kimiKeyField.text = newText }

    var kimiModelText: String
        get() = kimiModelField.text.trim()
        set(newText) { kimiModelField.text = newText }

    var geminiKeyText: String
        get() = geminiKeyField.text.trim()
        set(newText) { geminiKeyField.text = newText }

    var geminiModelText: String
        get() = geminiModelField.text.trim()
        set(newText) { geminiModelField.text = newText }

    var gptKeyText: String
        get() = gptKeyField.text.trim()
        set(newText) { gptKeyField.text = newText }

    var gptModelText: String
        get() = gptModelField.text.trim()
        set(newText) { gptModelField.text = newText }
}
