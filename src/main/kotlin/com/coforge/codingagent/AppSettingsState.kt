package com.coforge.codingagent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "com.coforge.codingagent.AppSettingsState",
    storages = [Storage("CoforgeAiCodingAgentSettings.xml")]
)
class AppSettingsState : PersistentStateComponent<AppSettingsState> {
    var kimiApiKey: String = ""
    var geminiApiKey: String = ""
    var gptApiKey: String = ""

    var kimiModel: String = "kimi-k2-thinking"
    var geminiModel: String = "gemini-2-5-flash"
    var gptModel: String = "gpt-5-2-chat"

    var inlineCompletionsEnabled: Boolean = true

    override fun getState(): AppSettingsState = this

    override fun loadState(state: AppSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: AppSettingsState
            get() = ApplicationManager.getApplication().getService(AppSettingsState::class.java)
    }
}
