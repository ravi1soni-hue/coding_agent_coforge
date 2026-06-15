package com.coforge.codingagent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Persists chat history per project to .idea/coforge-history.json.
 * History survives IDE restarts — previous conversation context is restored.
 * Capped at 60 messages to avoid unbounded growth.
 */
@Service(Service.Level.PROJECT)
class ProjectHistoryService {

    private val gson = Gson()

    private fun historyFile(project: Project): File? {
        val base = project.basePath ?: return null
        val dir = File("$base/.idea").also { it.mkdirs() }
        return File(dir, "coforge-history.json")
    }

    fun load(project: Project): MutableList<Message> {
        return try {
            val file = historyFile(project) ?: return mutableListOf()
            if (!file.exists() || file.length() == 0L) return mutableListOf()
            val type = object : TypeToken<List<Message>>() {}.type
            gson.fromJson<List<Message>>(file.readText(Charsets.UTF_8), type)
                ?.toMutableList() ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    }

    fun save(project: Project, history: List<Message>) {
        try {
            val file = historyFile(project) ?: return
            val toSave = if (history.size > 60) history.takeLast(60) else history
            file.writeText(gson.toJson(toSave), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun clear(project: Project) {
        try { historyFile(project)?.delete() } catch (_: Exception) {}
    }

    companion object {
        fun getInstance(project: Project): ProjectHistoryService =
            project.getService(ProjectHistoryService::class.java)
    }
}
