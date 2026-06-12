package com.coforge.codingagent

import com.intellij.build.BuildProgressListener
import com.intellij.build.events.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service that listens to Gradle/compiler build events
 * and keeps a rolling buffer of recent errors and warnings.
 * Injected into EditorContext so the AI always knows about build failures.
 */
@Service(Service.Level.PROJECT)
class BuildContextService : BuildProgressListener {

    data class BuildIssue(val severity: String, val message: String, val file: String?)

    private val issues = ArrayDeque<BuildIssue>(maxOf = 30)

    override fun onEvent(buildId: Any, event: BuildEvent) {
        when (event) {
            is FailureResult -> {
                event.failures.forEach { failure ->
                    addIssue(BuildIssue("ERROR", failure.message ?: "Build failure", null))
                }
            }
            is FileMessageEvent -> {
                val severity = when (event.kind) {
                    MessageEvent.Kind.ERROR -> "ERROR"
                    MessageEvent.Kind.WARNING -> "WARNING"
                    else -> return
                }
                addIssue(BuildIssue(severity, event.message, event.filePosition?.file?.path))
            }
            is MessageEvent -> {
                val severity = when (event.kind) {
                    MessageEvent.Kind.ERROR -> "ERROR"
                    MessageEvent.Kind.WARNING -> "WARNING"
                    else -> return
                }
                addIssue(BuildIssue(severity, event.message, null))
            }
            is FinishBuildEvent -> {
                if (event.result is SuccessResult) {
                    // Clear errors on successful build
                    issues.removeAll { it.severity == "ERROR" }
                }
            }
        }
    }

    @Synchronized
    private fun addIssue(issue: BuildIssue) {
        if (issues.size >= 30) issues.removeFirst()
        issues.addLast(issue)
    }

    @Synchronized
    fun getRecentIssues(maxErrors: Int = 10): String {
        if (issues.isEmpty()) return ""
        val relevant = issues.filter { it.severity == "ERROR" }.takeLast(maxErrors)
        if (relevant.isEmpty()) return ""
        return "Recent build errors:\n" + relevant.joinToString("\n") { issue ->
            val loc = if (issue.file != null) " [${issue.file.substringAfterLast('/')}]" else ""
            "  ${issue.severity}$loc: ${issue.message}"
        }
    }

    companion object {
        fun getInstance(project: Project): BuildContextService =
            project.getService(BuildContextService::class.java)
    }
}

private fun ArrayDeque<*>.removeAll(predicate: (Any?) -> Boolean) {
    val iter = this.iterator()
    while (iter.hasNext()) {
        if (predicate(iter.next())) iter.remove()
    }
}
