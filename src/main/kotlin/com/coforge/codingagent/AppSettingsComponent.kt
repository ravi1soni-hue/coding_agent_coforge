package com.coforge.codingagent

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel
import com.intellij.ui.TitledSeparator

class AppSettingsComponent {
    val panel: JPanel

    private val kimiKeyField = JBTextField()
    private val kimiModelField = JBTextField()

    private val geminiKeyField = JBTextField()
    private val geminiModelField = JBTextField()

    private val gptKeyField = JBTextField()
    private val gptModelField = JBTextField()

    init {
        panel = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Kimi Settings (Reasoning)"))
            .addLabeledComponent(JBLabel("API Key: "), kimiKeyField, 1)
            .addLabeledComponent(JBLabel("Model ID: "), kimiModelField, 1)

            .addComponent(TitledSeparator("Gemini Settings (Review)"))
            .addLabeledComponent(JBLabel("API Key: "), geminiKeyField, 1)
            .addLabeledComponent(JBLabel("Model ID: "), geminiModelField, 1)

            .addComponent(TitledSeparator("GPT Settings (Implementation)"))
            .addLabeledComponent(JBLabel("API Key: "), gptKeyField, 1)
            .addLabeledComponent(JBLabel("Model ID: "), gptModelField, 1)

            .addComponentFillVertically(JPanel(), 8)
            .panel
    }

    var kimiKeyText: String
        get() = kimiKeyField.text.trim()
        set(v) { kimiKeyField.text = v }

    var kimiModelText: String
        get() = kimiModelField.text.trim()
        set(v) { kimiModelField.text = v }

    var geminiKeyText: String
        get() = geminiKeyField.text.trim()
        set(v) { geminiKeyField.text = v }

    var geminiModelText: String
        get() = geminiModelField.text.trim()
        set(v) { geminiModelField.text = v }

    var gptKeyText: String
        get() = gptKeyField.text.trim()
        set(v) { gptKeyField.text = v }

    var gptModelText: String
        get() = gptModelField.text.trim()
        set(v) { gptModelField.text = v }
}
