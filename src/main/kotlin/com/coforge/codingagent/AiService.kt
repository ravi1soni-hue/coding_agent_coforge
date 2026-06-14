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

enum class Intent {
    EXPLAIN,      // question / explanation  → single GPT call  (~5s)
    FIX,          // bug / error / crash     → Kimi → GPT       (~15s)
    CODE_SIMPLE,  // small targeted edit     → Kimi → GPT       (~15s)
    CODE_COMPLEX  // new feature / architect → Kimi → Gemini → GPT (~40s)
}

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

    // ─── Open-ended system prompts (no hardcoded library lists) ──────────────

    /**
     * Kimi reasons about the problem using the ACTUAL project context.
     * No hardcoded frameworks — it reads what's in the project and adapts.
     */
    private fun kimiSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter / Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android / ${info.mainLanguage}"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are an expert $platform developer and architect.
            You work across ALL libraries, tools, and patterns in the $platform ecosystem —
            not just a fixed set. New libraries, SDKs, and patterns appear every week and you
            know them. When web search results or documentation are provided in the context,
            use them as authoritative source of truth.

            YOUR TASK: Given the developer's request, produce a STRUCTURED IMPLEMENTATION PLAN.
            No code — only a precise numbered plan. Cover:
            1. Which files change and why
            2. Exact implementation approach using the project's actual dependencies (listed below)
            3. Edge cases, async/threading concerns, memory management, lifecycle issues
            4. Any new packages/versions needed with exact coordinates

            IMPORTANT RULES:
            - Use ONLY the libraries already in this project unless a new one is clearly needed
            - If web search results are in context, use that documentation — it overrides your training
            - Never suggest an alternative library just because you prefer it
            - Match the existing code style and architecture patterns in this project
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nTHIS PROJECT'S ACTUAL DEPENDENCIES:\n")
            append(deps.toPromptContext())
        }
    }

    /**
     * Gemini reviews the plan against the actual project constraints.
     */
    private fun geminiReviewSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are a rigorous $platform code reviewer and quality gate.
            Review the implementation plan and return an IMPROVED & VALIDATED version.

            Check for:
            - Correctness: will this plan actually solve the stated problem?
            - Compatibility: do the suggested APIs/methods exist in the versions this project uses?
            - Missing edge cases, threading/async bugs, memory leaks, lifecycle issues
            - Anti-patterns or fragile approaches specific to $platform
            - Version conflicts with existing dependencies
            - If web search docs are in context, verify the plan matches the actual API

            Return only the improved plan. No code.
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nProject dependencies for version compatibility checks:\n")
            append(deps.toPromptContext())
        }
    }

    /**
     * GPT implements. Open-ended — can generate Kotlin, Dart, XML, YAML, anything.
     */
    private fun gptImplementSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are an elite $platform developer. Implement the validated plan completely.

            STRICT OUTPUT RULES:
            - For EVERY file you modify: <file_change path="relative/path/to/File.ext">COMPLETE FILE CONTENT</file_change>
            - For NEW files: <new_file path="relative/path/to/NewFile.ext">COMPLETE FILE CONTENT</new_file>
            - The file extension must match the language: .kt for Kotlin, .dart for Dart, .xml for XML, .yaml for YAML, etc.
            - NEVER truncate with "// ... rest" or "// TODO". Output the full, complete file every time.
            - NEVER write placeholder code. Everything must be real, working, production-quality code.
            - If web search docs are in context, use the DOCUMENTED API — not what you remember from training.
            - Use ONLY libraries from the project's dependency list unless adding a new one (and if so, also output the updated pubspec.yaml or build.gradle).
            - After all code: write 2-3 sentences explaining what changed and why.
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nProject dependencies (use ONLY these unless explicitly adding new ones):\n")
            append(deps.toPromptContext())
        }
    }

    /**
     * GPT explains — answers questions authoritatively using current web docs when available.
     */
    private fun gptExplainSystem(info: ProjectTypeDetector.ProjectInfo) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are an expert $platform developer and technical mentor.
            You are knowledgeable about the ENTIRE ecosystem — every library, framework, and tool,
            including ones released after your training cutoff if their documentation is in the context.

            Answer clearly, confidently, and directly with concrete examples.
            Use markdown: headers, bold, code blocks. Never hedge. Never say "I'm not sure."
            If web search results are provided, use them as authoritative current documentation.
        """.trimIndent())
    }

    private val gptInlineSystem = """
        Complete the code. OUTPUT ONLY the completion text — no explanation, no markdown.
        1-5 lines max. Match existing code style exactly.
    """.trimIndent()

    // ─── Intent detection ─────────────────────────────────────────────────────

    fun detectIntent(message: String): Intent {
        val lower = message.lowercase().trim()
        val tokens = lower.split(Regex("[\\s,?.!]+")).toSet()
        val explainWords = setOf("what","why","how","explain","describe","understand","clarify","when","which","where","is","does","can","should","difference","meaning","tell")
        val fixWords = setOf("fix","debug","error","bug","crash","exception","issue","problem","broken","fails","failing","wrong","incorrect","not working","doesn't work","solve")
        val simpleWords = setOf("rename","format","remove","delete","move","extract","inline","convert","change","update","modify","add import","sort")
        return when {
            lower.endsWith("?") || tokens.intersect(explainWords).size >= 2 && !tokens.any { it in setOf("create","implement","build","generate","write","add") } -> Intent.EXPLAIN
            tokens.any { it in fixWords } -> Intent.FIX
            tokens.any { it in simpleWords } && !lower.contains("create") && !lower.contains("implement") -> Intent.CODE_SIMPLE
            else -> Intent.CODE_COMPLEX
        }
    }

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

        // Run URL fetch, web search, and codebase index search in parallel
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

            val intent = detectIntent(userMessage)

            when (intent) {
                // ── Fast: single GPT call ─────────────────────────────────────
                Intent.EXPLAIN -> {
                    onStatus("Answering...")
                    callModelStreaming(settings.gptModel, settings.gptApiKey,
                        gptExplainSystem(info),
                        buildDirectPrompt(userMessage, enrichedContext, history), images, onToken)
                        .thenAccept { onComplete(it) }
                        .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
                }

                // ── Medium: Kimi plan → GPT ───────────────────────────────────
                Intent.FIX, Intent.CODE_SIMPLE -> {
                    onStatus("Analyzing...")
                    callModelBlocking(settings.kimiModel, settings.kimiApiKey,
                        kimiSystem(info, deps),
                        buildReasoningPrompt(userMessage, enrichedContext, history))
                        .thenCompose { plan ->
                            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")
                            onReasoning("PLAN:\n$plan")
                            onStatus("Implementing...")
                            callModelStreaming(settings.gptModel, settings.gptApiKey,
                                gptImplementSystem(info, deps),
                                buildImplPrompt(userMessage, plan, enrichedContext, history), images, onToken)
                        }
                        .thenAccept { onComplete(it) }
                        .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
                }

                // ── Full chain: Kimi → Gemini → GPT ──────────────────────────
                Intent.CODE_COMPLEX -> {
                    onStatus("Thinking...")
                    callModelBlocking(settings.kimiModel, settings.kimiApiKey,
                        kimiSystem(info, deps),
                        buildReasoningPrompt(userMessage, enrichedContext, history))
                        .thenCompose { kimiPlan ->
                            if (stopRequested) return@thenCompose CompletableFuture.completedFuture(kimiPlan to "")
                            onStatus("Reviewing plan...")
                            callModelBlocking(settings.geminiModel, settings.geminiApiKey,
                                geminiReviewSystem(info, deps),
                                "Request: $userMessage\n\nPlan:\n$kimiPlan\n\nContext:\n$enrichedContext")
                                .thenApply { review -> kimiPlan to review }
                        }
                        .thenCompose { (kimiPlan, review) ->
                            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")
                            onReasoning("INITIAL PLAN:\n$kimiPlan\n\nVALIDATED PLAN:\n$review")
                            onStatus("Implementing...")
                            callModelStreaming(settings.gptModel, settings.gptApiKey,
                                gptImplementSystem(info, deps),
                                buildImplPrompt(userMessage, review.ifBlank { kimiPlan }, enrichedContext, history), images, onToken)
                        }
                        .thenAccept { onComplete(it) }
                        .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
                }
            }
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
            kimiSystem(info, deps),
            """
            The following test failures occurred after applying changes to: $fileList

            TEST OUTPUT:
            $failureOutput

            CODEBASE CONTEXT:
            $indexContext

            Produce a precise plan to fix ALL failing tests.
            """.trimIndent()
        ).thenCompose { plan ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")
            onStatus("Fixing...")
            callModelStreaming(settings.gptModel, settings.gptApiKey,
                gptImplementSystem(info, deps),
                """
                Validated fix plan:
                $plan

                Files that were changed and now have test failures: $fileList
                Test output: ${failureOutput.take(3000)}

                Implement the fix. Output ONLY the corrected file(s) using <file_change> tags.
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

    private fun historyBlock(history: List<Message>) =
        history.takeLast(6).joinToString("\n") { "[${it.role.uppercase()}]: ${it.content.take(800)}" }

    private fun buildDirectPrompt(msg: String, ctx: String, history: List<Message>) = buildString {
        historyBlock(history).takeIf { it.isNotEmpty() }?.let { append("Conversation:\n$it\n\n") }
        ctx.takeIf { it.isNotBlank() }?.let { append("Context:\n$it\n\n") }
        append("Question: $msg")
    }

    private fun buildReasoningPrompt(msg: String, ctx: String, history: List<Message>) = buildString {
        historyBlock(history).takeIf { it.isNotEmpty() }?.let { append("Conversation:\n$it\n\n") }
        ctx.takeIf { it.isNotBlank() }?.let { append("Code and web context:\n$it\n\n") }
        append("Request: $msg\n\nProduce a structured implementation plan.")
    }

    private fun buildImplPrompt(msg: String, plan: String, ctx: String, history: List<Message>) = buildString {
        historyBlock(history).takeIf { it.isNotEmpty() }?.let { append("Conversation:\n$it\n\n") }
        ctx.takeIf { it.isNotBlank() }?.let { append("Code and web context:\n$it\n\n") }
        append("Validated plan:\n$plan\n\nOriginal request: $msg\n\nImplement completely.")
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
