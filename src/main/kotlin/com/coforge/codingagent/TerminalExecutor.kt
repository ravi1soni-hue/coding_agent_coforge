package com.coforge.codingagent

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.nio.charset.Charset

/**
 * Runs Gradle and Flutter commands with streamed output.
 * Enables "Run Tests", "Build", "Analyze" from the chat panel.
 *
 * All callbacks fire on a background daemon thread — callers must
 * dispatch to EDT for any Swing updates (SwingUtilities.invokeLater).
 */
object TerminalExecutor {

    data class CommandResult(val exitCode: Int, val output: String) {
        val isSuccess get() = exitCode == 0
        val summary get() = if (isSuccess) "✓ Done (exit 0)" else "✗ Failed (exit $exitCode)"
    }

    // ─── Gradle ───────────────────────────────────────────────────────────────

    fun runTests(project: Project, onOutput: (String) -> Unit, onDone: (CommandResult) -> Unit) {
        val base = project.basePath ?: return onDone(CommandResult(-1, "No project path"))
        val gradlew = if (File("$base/gradlew").exists()) listOf("./gradlew") else listOf("gradle")
        run(base, gradlew + listOf("test", "--daemon", "--continue"), onOutput, onDone)
    }

    fun buildDebug(project: Project, onOutput: (String) -> Unit, onDone: (CommandResult) -> Unit) {
        val base = project.basePath ?: return onDone(CommandResult(-1, "No project path"))
        val gradlew = if (File("$base/gradlew").exists()) listOf("./gradlew") else listOf("gradle")
        run(base, gradlew + listOf("assembleDebug", "--daemon"), onOutput, onDone)
    }

    fun runLint(project: Project, onOutput: (String) -> Unit, onDone: (CommandResult) -> Unit) {
        val base = project.basePath ?: return onDone(CommandResult(-1, "No project path"))
        val gradlew = if (File("$base/gradlew").exists()) listOf("./gradlew") else listOf("gradle")
        run(base, gradlew + listOf("lint", "--daemon"), onOutput, onDone)
    }

    // ─── Flutter ──────────────────────────────────────────────────────────────

    fun flutterTest(project: Project, onOutput: (String) -> Unit, onDone: (CommandResult) -> Unit) {
        val base = project.basePath ?: return onDone(CommandResult(-1, "No project path"))
        run(base, listOf("flutter", "test", "--reporter=expanded"), onOutput, onDone)
    }

    fun flutterBuild(project: Project, onOutput: (String) -> Unit, onDone: (CommandResult) -> Unit) {
        val base = project.basePath ?: return onDone(CommandResult(-1, "No project path"))
        run(base, listOf("flutter", "build", "apk", "--debug"), onOutput, onDone)
    }

    fun flutterAnalyze(project: Project, onOutput: (String) -> Unit, onDone: (CommandResult) -> Unit) {
        val base = project.basePath ?: return onDone(CommandResult(-1, "No project path"))
        run(base, listOf("flutter", "analyze"), onOutput, onDone)
    }

    // ─── Git helpers ──────────────────────────────────────────────────────────

    /** Returns staged diff (git diff --staged) or HEAD diff if nothing staged. */
    fun gitDiff(project: Project, onOutput: (String) -> Unit, onDone: (CommandResult) -> Unit) {
        val base = project.basePath ?: return onDone(CommandResult(-1, "No project path"))
        run(base, listOf("git", "diff", "--staged"), onOutput) { result ->
            if (result.output.isBlank()) {
                // Nothing staged — fall back to diff against HEAD
                run(base, listOf("git", "diff", "HEAD"), onOutput, onDone)
            } else {
                onDone(result)
            }
        }
    }

    // ─── Generic runner ───────────────────────────────────────────────────────

    fun run(
        workDir: String,
        command: List<String>,
        onOutput: (String) -> Unit,
        onDone: (CommandResult) -> Unit,
        timeoutMs: Long = 180_000L
    ) {
        Thread {
            try {
                val cmd = GeneralCommandLine(command)
                    .withWorkDirectory(workDir)
                    .withCharset(Charset.forName("UTF-8"))
                    .withRedirectErrorStream(true)
                    .withEnvironment("TERM", "dumb")
                    .withEnvironment("CI", "true")

                val handler = OSProcessHandler(cmd)
                val buf = StringBuilder()

                handler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, key: Key<*>) {
                        buf.append(event.text)
                        onOutput(event.text)
                    }
                    override fun processTerminated(event: ProcessEvent) {
                        onDone(CommandResult(event.exitCode, buf.toString()))
                    }
                })

                handler.startNotify()
                handler.waitFor(timeoutMs)
                // Timeout
                if (!handler.isProcessTerminated) {
                    handler.destroyProcess()
                    onDone(CommandResult(-1, buf.append("\n[TIMEOUT after ${timeoutMs}ms]").toString()))
                }
            } catch (e: Exception) {
                onDone(CommandResult(-1, "Error: ${e.message}"))
            }
        }.apply { isDaemon = true; name = "CoforgeTerminal" }.start()
    }
}
