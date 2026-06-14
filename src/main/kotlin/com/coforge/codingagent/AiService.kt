package com.coforge.codingagent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

data class Message(val role: String, val content: String)

object AiService {
    private val LOG = Logger.getInstance(AiService::class.java)
    private const val API_URL = "https://quasarmarket.coforge.com/qag/llmrouter-api/v2/chat/completions"

    @Volatile private var stopRequested = false
    fun stop() { stopRequested = true }

    private val gson by lazy { Gson() }
    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
            .also { c -> Runtime.getRuntime().addShutdownHook(Thread {
                // close() exists only on Java 21+; call reflectively so we compile on Java 17
                try { c.javaClass.getMethod("close").invoke(c) } catch (_: Exception) { }
            }) }
    }

    // ─── System prompts ───────────────────────────────────────────────────────

    /**
     * Kimi is the brain. It reads the full conversation, understands what was
     * previously delivered vs what is still needed, and produces a structured
     * analysis + action plan. No regex — Kimi decides everything.
     */
    private fun kimiAnalysisSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter / Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android / ${info.mainLanguage}"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are an expert $platform senior developer and AI coding agent.

            You receive the FULL conversation history so you can see:
            - What the user asked for in previous turns
            - What was already delivered (files created, explanations given, etc.)
            - What the user complained about or said was wrong
            - What is STILL missing or needs fixing

            YOUR JOB: Produce a structured analysis of the current situation and a precise action plan.

            ── ANALYSIS FORMAT (always follow this structure) ──────────────────
            ACTION: [one of: EXPLAIN_ONLY | CREATE_FILES | MODIFY_FILES | FIX_BUG | MIXED]

            WHAT WAS ALREADY DELIVERED:
            (Summarize what previous assistant turns actually gave the user — files, explanations, etc.)

            WHAT IS STILL NEEDED:
            (Based on conversation history and current request, what is missing or wrong)

            PLAN:
            1. (specific step — which file, what change, why)
            2. ...
            ────────────────────────────────────────────────────────────────────

            CRITICAL RULES FOR ACTION:
            - If the user is asking a pure conceptual question with no code task → ACTION: EXPLAIN_ONLY
            - If the user asks to create something, add a feature, or write code → ACTION: CREATE_FILES or MODIFY_FILES
            - If the previous assistant turn gave explanations but NO files, and user wanted files → mark WHAT IS STILL NEEDED accordingly and action CREATE_FILES
            - If the user says "you didn't create it", "where is the page", "what did you do" → they want files, not more explanations
            - If the request is a mix (e.g., explain AND create) → ACTION: MIXED
            - Always use the project's actual dependencies — listed below
            - Never suggest alternatives for no reason; match existing project patterns
            - If web search docs are in context, treat them as authoritative
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nPROJECT DEPENDENCIES (use these; only add new ones if clearly necessary):\n")
            append(deps.toPromptContext())
        }
    }

    /**
     * Gemini is the quality gate. It reads Kimi's analysis and the full context,
     * checks correctness and compatibility, and returns a validated action plan.
     */
    private fun geminiReviewSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are a rigorous $platform code reviewer and quality gate.

            You receive Kimi's analysis of the situation and a proposed action plan.
            Your job: validate and improve the plan before implementation.

            Check:
            - Does the plan actually address what the user needs (including undelivered prior requests)?
            - Do all APIs and methods exist in the versions this project uses?
            - Are there threading/async bugs, lifecycle issues, memory leaks?
            - Are there missing files, missing route registrations, missing imports?
            - Does the plan match the project's actual dependency versions?
            - If web search docs are in context, does the plan use the documented API?

            Return: the improved and validated plan only. No code.
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nProject dependencies:\n")
            append(deps.toPromptContext())
        }
    }

    /**
     * GPT-5 is the executor. It receives the validated plan and implements it.
     * Unified prompt — handles both explanations and file creation based on the plan's ACTION field.
     */
    private fun gptExecuteSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are an elite $platform developer executing a validated implementation plan.

            The plan tells you what ACTION to take. Follow the ACTION exactly:

            ── IF ACTION is EXPLAIN_ONLY ────────────────────────────────────────
            Write a clear, direct explanation with markdown formatting.
            Use code examples where helpful. Be concrete, never vague.
            ────────────────────────────────────────────────────────────────────

            ── IF ACTION is CREATE_FILES, MODIFY_FILES, FIX_BUG, or MIXED ──────
            You MUST output every file using these XML tags:

            NEW FILE → <new_file path="relative/path/to/File.ext">COMPLETE FILE CONTENTS</new_file>
            MODIFIED FILE → <file_change path="relative/path/to/File.ext">COMPLETE FILE CONTENTS</file_change>

            ABSOLUTE RULES for file output:
            ❌ NEVER write markdown code blocks (```dart, ```kotlin, etc.)
            ❌ NEVER write Step 1/Step 2 tutorial instructions
            ❌ NEVER truncate — output the COMPLETE file every time, no "// rest of file"
            ❌ NEVER write placeholder or TODO code — everything must be real and working
            ❌ NEVER tell the user what to do manually — you do it in the file tags

            After all file tags: 1-2 sentences max describing what was created/changed.

            For MIXED action: write the explanation first, then all file tags.
            ────────────────────────────────────────────────────────────────────

            File extension rules: .kt=Kotlin  .dart=Dart  .xml=XML  .yaml=YAML/pubspec
            If adding a new package: also output the updated pubspec.yaml or build.gradle.
            Use only existing project libraries unless the plan explicitly adds a new one.
            If web docs are in context, use the documented API — not training memory.
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nProject dependencies:\n")
            append(deps.toPromptContext())
        }
    }

    private val gptInlineSystem = """
        Complete the code. OUTPUT ONLY the completion text — no explanation, no markdown.
        1-5 lines max. Match existing code style exactly.
    """.trimIndent()

    // ─── Main entry point ─────────────────────────────────────────────────────

    fun callAgentChain(
        userMessage: String,
        history: List<Message>,
        context: String,
        images: List<String> = emptyList(),
        project: Project? = null,
        onStatus: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        stopRequested = false
        val settings = AppSettingsState.instance

        val info = project?.let { ProjectTypeDetector.detect(it) }
            ?: ProjectTypeDetector.ProjectInfo(ProjectTypeDetector.ProjectType.ANDROID_NATIVE, null, null, null, "Kotlin")
        val deps = project?.let { ProjectDependencyAnalyzer.analyze(it) }
            ?: ProjectDependencyAnalyzer.ProjectDeps(emptyList(), null, null, null, null, null, null, null)

        onStatus("Gathering context...")
        val urlFuture    = CompletableFuture.supplyAsync { UrlContentFetcher.fetchAll(userMessage) }
        val searchFuture = CompletableFuture.supplyAsync { WebSearchService.fetchContextIfNeeded(userMessage, info.type) }
        val indexFuture  = CompletableFuture.supplyAsync {
            project?.let { p ->
                try {
                    ApplicationManager.getApplication().runReadAction<String> {
                        EditorContext.getIndexedContext(p, userMessage)
                    }
                } catch (_: Exception) { "" }
            } ?: ""
        }

        CompletableFuture.allOf(urlFuture, searchFuture, indexFuture).thenRun {
            if (stopRequested) return@thenRun

            val urlContext    = try { urlFuture.get() } catch (_: Exception) { "" }
            val searchContext = try { searchFuture.get() } catch (_: Exception) { "" }
            val indexContext  = try { indexFuture.get() } catch (_: Exception) { "" }

            val enrichedContext = listOfNotNull(
                context.takeIf { it.isNotBlank() && it != "No file currently open." },
                indexContext.takeIf { it.isNotBlank() },
                urlContext.takeIf { it.isNotBlank() },
                searchContext.takeIf { it.isNotBlank() }
            ).joinToString("\n\n===\n\n")

            // ── Step 1: Kimi analyzes the full situation (always runs) ────────
            onStatus("Analyzing your request...")
            callModelBlocking(
                settings.kimiModel, settings.kimiApiKey,
                kimiAnalysisSystem(info, deps),
                buildKimiPrompt(userMessage, enrichedContext, history)
            ).thenCompose { kimiAnalysis ->
                if (stopRequested) return@thenCompose CompletableFuture.completedFuture(kimiAnalysis to "")
                onReasoning(kimiAnalysis)

                // Pure explain → skip Gemini review for speed
                val isExplainOnly = kimiAnalysis.contains("ACTION: EXPLAIN_ONLY", ignoreCase = true)
                if (isExplainOnly) {
                    return@thenCompose CompletableFuture.completedFuture(kimiAnalysis to "")
                }

                // ── Step 2: Gemini validates the plan ────────────────────────
                onStatus("Validating plan...")
                callModelBlocking(
                    settings.geminiModel, settings.geminiApiKey,
                    geminiReviewSystem(info, deps),
                    "Conversation summary and request:\n${buildKimiPrompt(userMessage, enrichedContext, history).take(2000)}\n\nKimi's analysis:\n$kimiAnalysis\n\nValidate and improve this plan."
                ).thenApply { review -> kimiAnalysis to review }

            }.thenCompose { (kimiAnalysis, geminiReview) ->
                if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")

                val validatedPlan = if (geminiReview.isNotBlank()) {
                    onReasoning("VALIDATED:\n$geminiReview")
                    geminiReview
                } else {
                    kimiAnalysis
                }

                // ── Step 3: GPT executes ──────────────────────────────────────
                onStatus("Working...")
                callModelStreaming(
                    settings.gptModel, settings.gptApiKey,
                    gptExecuteSystem(info, deps),
                    buildExecutePrompt(userMessage, validatedPlan, enrichedContext, history),
                    images, onToken
                )
            }
            .thenAccept { onComplete(it) }
            .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
        }
    }

    // ─── Agentic test-fix loop ────────────────────────────────────────────────

    /**
     * Called by the agentic loop after test failures.
     * Sends the failures + the files that were just changed to the FIX chain.
     * Streams the fix back just like a normal agent call.
     */
    fun fixTestFailures(
        failureOutput: String,
        changedFiles: List<String>,
        project: Project,
        onStatus: (String) -> Unit,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        stopRequested = false
        val settings = AppSettingsState.instance
        val info = ProjectTypeDetector.detect(project)
        val deps = ProjectDependencyAnalyzer.analyze(project)

        val indexContext = try { EditorContext.getIndexedContext(project, failureOutput.take(500)) } catch (_: Exception) { "" }
        val fileList = changedFiles.joinToString(", ")

        onStatus("Analyzing failures...")
        callModelBlocking(settings.kimiModel, settings.kimiApiKey,
            kimiAnalysisSystem(info, deps),
            """
            ACTION: FIX_BUG

            WHAT IS STILL NEEDED:
            Fix all failing tests after changes were applied to: $fileList

            TEST OUTPUT:
            $failureOutput

            CODEBASE CONTEXT:
            $indexContext

            PLAN:
            Produce a precise numbered plan to fix ALL failing tests.
            """.trimIndent()
        ).thenCompose { plan ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")
            onStatus("Fixing...")
            callModelStreaming(settings.gptModel, settings.gptApiKey,
                gptExecuteSystem(info, deps),
                """
                ═══ VALIDATED PLAN ═══
                $plan

                ═══ CURRENT REQUEST ═══
                Fix failing tests in: $fileList
                Test output: ${failureOutput.take(3000)}

                Execute the plan. Output corrected files using <file_change> tags with COMPLETE file contents.
                """.trimIndent(),
                emptyList(), onToken)
        }
        .thenAccept { onComplete(it) }
        .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
    }

    // ─── Inline ghost-text completion ─────────────────────────────────────────

    fun getInlineCompletion(prefix: String, suffix: String, language: String): String {
        if (stopRequested) return ""
        val settings = AppSettingsState.instance
        if (settings.gptApiKey.isBlank()) return ""
        return try {
            // maxTokens = 80: keeps latency low; 1-2 lines is all ghost-text needs
            callModelSync(settings.gptModel, settings.gptApiKey, gptInlineSystem,
                "LANGUAGE: $language\nPREFIX:\n$prefix\nSUFFIX:\n$suffix\nCOMPLETION:",
                timeoutSec = 5, maxTokens = 80)
                .take(400)
        } catch (_: Exception) { "" }
    }

    // ─── Prompt builders ──────────────────────────────────────────────────────

    /** Full conversation history for Kimi — more turns, longer content, so it sees what was/wasn't delivered. */
    private fun historyBlock(history: List<Message>) =
        history.takeLast(10).joinToString("\n\n") { msg ->
            val label = if (msg.role == "user") "USER" else "ASSISTANT (previously delivered)"
            "[$label]:\n${msg.content.take(1200)}"
        }

    /** Kimi receives the full context: history, codebase, web docs, current request. */
    private fun buildKimiPrompt(msg: String, ctx: String, history: List<Message>) = buildString {
        val h = historyBlock(history)
        if (h.isNotEmpty()) {
            append("═══ CONVERSATION HISTORY ═══\n")
            append(h)
            append("\n\n")
        }
        ctx.takeIf { it.isNotBlank() }?.let {
            append("═══ PROJECT CODE & WEB CONTEXT ═══\n$it\n\n")
        }
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("Analyze the full situation above. Identify what was already delivered vs. what is still needed. Produce your structured analysis and action plan.")
    }

    /** GPT receives the validated plan + full context and executes. */
    private fun buildExecutePrompt(msg: String, plan: String, ctx: String, history: List<Message>) = buildString {
        val h = historyBlock(history)
        if (h.isNotEmpty()) {
            append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        }
        ctx.takeIf { it.isNotBlank() }?.let {
            append("═══ PROJECT CODE & WEB CONTEXT ═══\n$it\n\n")
        }
        append("═══ VALIDATED PLAN ═══\n$plan\n\n")
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("Execute the plan above completely. Follow the ACTION field — if it requires files, output EVERY file inside <new_file> or <file_change> tags with COMPLETE contents. Start immediately.")
    }

    // ─── Model callers ────────────────────────────────────────────────────────

    private fun callModelBlocking(model: String, apiKey: String, system: String, user: String): CompletableFuture<String> {
        if (apiKey.isBlank()) return CompletableFuture.failedFuture(Exception("API key missing for $model"))
        val request = buildHttpRequest(apiKey, buildBody(model, system, user, stream = false))
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { r ->
                if (r.statusCode() == 200) parseBlocking(r.body())
                else throw Exception("HTTP ${r.statusCode()}: ${r.body().take(400)}")
            }
    }

    private fun callModelStreaming(
        model: String, apiKey: String, system: String, user: String,
        images: List<String> = emptyList(), onToken: (String) -> Unit
    ): CompletableFuture<String> {
        if (apiKey.isBlank()) return CompletableFuture.failedFuture(Exception("API key missing for $model"))
        val request = buildHttpRequest(apiKey, buildBody(model, system, user, stream = true, images = images), timeoutSec = 180)
        return CompletableFuture.supplyAsync {
            val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
            val full = StringBuilder()
            response.body().forEach { line ->
                if (stopRequested) return@forEach
                if (line.startsWith("data: ") && line != "data: [DONE]") {
                    val delta = parseStreamDelta(line.removePrefix("data: "))
                    if (delta.isNotEmpty()) { full.append(delta); onToken(delta) }
                }
            }
            if (full.isEmpty() && !stopRequested) {
                val fb = callModelSync(model, apiKey, system, user, images = images)
                onToken(fb); full.append(fb)
            }
            full.toString()
        }
    }

    private fun callModelSync(
        model: String, apiKey: String, system: String, user: String,
        timeoutSec: Long = 30, images: List<String> = emptyList(), maxTokens: Int? = null
    ): String {
        if (apiKey.isBlank()) return ""
        val request = buildHttpRequest(apiKey, buildBody(model, system, user, stream = false, images = images, maxTokens = maxTokens), timeoutSec = timeoutSec)
        val r = client.send(request, HttpResponse.BodyHandlers.ofString())
        return if (r.statusCode() == 200) parseBlocking(r.body()) else ""
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private fun buildBody(model: String, system: String, user: String, stream: Boolean, images: List<String> = emptyList(), maxTokens: Int? = null): String {
        val userContent = if (images.isEmpty()) user else JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", user) })
            images.forEach { b64 ->
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:image/png;base64,$b64")
                        addProperty("detail", "high")
                    })
                })
            }
        }
        return gson.toJson(JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.2)
            if (stream) addProperty("stream", true)
            if (maxTokens != null) addProperty("max_tokens", maxTokens)
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    when (userContent) {
                        is String    -> addProperty("content", userContent)
                        is JsonArray -> add("content", userContent)
                        else         -> addProperty("content", user)
                    }
                })
            })
        })
    }

    private fun buildHttpRequest(apiKey: String, body: String, timeoutSec: Long = 120): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("X-API-KEY", apiKey)
            .timeout(Duration.ofSeconds(timeoutSec))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun parseBlocking(body: String): String {
        return try {
            val choices = gson.fromJson(body, JsonObject::class.java).getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                LOG.warn("Empty choices: ${body.take(200)}")
                return body
            }
            choices[0].asJsonObject.getAsJsonObject("message")?.get("content")?.asString ?: body
        } catch (e: Exception) {
            LOG.warn("Parse error: ${body.take(200)}")
            body
        }
    }

    private fun parseStreamDelta(json: String): String {
        return try {
            val choices = gson.fromJson(json, JsonObject::class.java).getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return ""
            choices[0].asJsonObject.getAsJsonObject("delta")?.get("content")?.asString ?: ""
        } catch (_: Exception) { "" }
    }
}
