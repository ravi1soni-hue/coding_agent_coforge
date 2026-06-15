package com.coforge.codingagent

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Lazily invalidates the ProjectIndexer and CodebaseGraph caches when source files
 * are created, modified, or deleted. The next query triggers a fresh background rebuild.
 *
 * Registered as an asyncFileListener in plugin.xml — runs after every VFS commit batch.
 */
class FileChangeListener : AsyncFileListener {

    private val SOURCE_EXTS = setOf("kt", "kts", "java", "dart", "xml", "gradle", "yaml", "toml")

    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        val affectedPaths = events.mapNotNull { event ->
            val path = when (event) {
                is VFileContentChangeEvent -> event.path
                is VFileCreateEvent        -> event.path
                is VFileDeleteEvent        -> event.path
                else                       -> null
            }
            path?.takeIf { it.substringAfterLast(".", "").lowercase() in SOURCE_EXTS }
        }
        if (affectedPaths.isEmpty()) return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                ProjectManager.getInstance().openProjects.forEach { project ->
                    val base = project.basePath ?: return@forEach
                    val affects = affectedPaths.any { it.startsWith(base) }
                    if (affects) {
                        // Invalidate only — rebuild happens lazily on next query
                        ProjectIndexer.invalidate(project)
                        CodebaseGraph.invalidate(project)
                        // Kick off background warm-up so next query is fast
                        Thread {
                            ProjectIndexer.warmUp(project)
                            CodebaseGraph.build(project)
                        }.apply { isDaemon = true; name = "CoforgeReindex" }.start()
                    }
                }
            }
        }
    }
}
