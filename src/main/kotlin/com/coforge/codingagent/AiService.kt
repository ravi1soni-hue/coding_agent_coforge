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

/**
 * Kimi's structured output parsed from its CONTEXT PLAN section.
 * Kimi decides what context it needs — we fetch exactly that.
 */
data class KimiPlan(
    val action: String,            // EXPLAIN_ONLY | CREATE_FILES | MODIFY_FILES | FIX_BUG | MIXED
    val requestedFiles: List<String>,  // relative paths Kimi wants to read in full
    val searchQueries: List<String>,   // web queries Kimi wants run
    val rawText: String
) {
    val isExplainOnly get() = action.equals("EXPLAIN_ONLY", ignoreCase = true)
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
                try { c.javaClass.getMethod("close").invoke(c) } catch (_: Exception) { }
            }) }
    }

    // ─── System prompts ───────────────────────────────────────────────────────

    /**
     * Kimi is the brain of the whole pipeline.
     *
     * It receives: conversation history + active file/editor context + project
     * file listing (paths + class/function names) + the current user message.
     *
     * It outputs a structured CONTEXT PLAN + ACTION PLAN in one shot:
     * - Which files it needs to read (by path) to understand the codebase
     * - Whether web search is useful and exactly what to search for
     * - What action is needed (create files, modify, explain, fix, mixed)
     * - A full implementation plan
     *
     * We then fetch exactly what Kimi asks for and pass it downstream.
     * Nothing else in the pipeline makes decisions about what context to load.
     */
    private fun kimiPlannerSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter / Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android / ${info.mainLanguage}"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are a senior $platform developer and AI coding agent brain.

            You receive:
            1. The full conversation history — so you know what was asked before and what was/wasn't delivered
            2. The currently open file and editor context
            3. A listing of all project files (paths + class/function names) — so you know what exists
            4. The user's current request

            YOUR JOB — produce TWO things in a SINGLE response:

            ════════════════════════════════════════════════════════
            PART 1: CONTEXT PLAN (first, before the action plan)
            ════════════════════════════════════════════════════════
            Tell the system exactly what additional context you need.

            FILES_NEEDED: [comma-separated relative file paths you want to read in full]
            SEARCH_QUERIES: [comma-separated web search queries, or NONE if not needed]

            Rules for FILES_NEEDED:
            - List only files that are DIRECTLY relevant to this task
            - Use exact relative paths from the project listing (e.g. lib/main.dart)
            - If the active file is already included in context, do not list it again
            - 0 to 6 files max — be precise, not exhaustive

            Rules for SEARCH_QUERIES:
            - Only request search if the task involves a library version, an API you might not know precisely,
              or documentation that would change the implementation
            - Write specific, targeted queries (e.g. "flutter go_router named routes parameters 2024")
            - NONE if search is not useful for this task
            - 0 to 3 queries max

            ════════════════════════════════════════════════════════
            PART 2: SITUATION ANALYSIS AND ACTION PLAN
            ════════════════════════════════════════════════════════
            ACTION: [one of: EXPLAIN_ONLY | CREATE_FILES | MODIFY_FILES | FIX_BUG | MIXED]

            WHAT WAS ALREADY DELIVERED:
            (What did previous assistant turns actually produce? Files? Explanations? Nothing?)

            WHAT IS STILL NEEDED:
            (What is the user actually asking for, considering history? What is missing?)

            PLAN:
            1. (specific step — which file, exact class/method, what change and why)
            2. ...

            CRITICAL SITUATION AWARENESS:
            - If the previous turn gave only an explanation/tutorial but NO files, and user wanted implementation
              → WHAT IS STILL NEEDED must say so and ACTION must be CREATE_FILES or MODIFY_FILES
            - If the user says "you didn't create it", "where is the page", "what did you do"
              → they want files, not more explanations — ACTION is CREATE_FILES
            - A question about the codebase that requires reading + explaining is EXPLAIN_ONLY
            - A task that requires BOTH explaining AND creating is MIXED
            - Use ONLY the project's actual dependencies listed below
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nPROJECT DEPENDENCIES:\n")
            append(deps.toPromptContext())
        }
    }

    /**
     * Gemini is the quality gate. It receives Kimi's plan PLUS the full context
     * that was fetched based on Kimi's requests. It validates and improves.
     */
    private fun geminiReviewSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are a rigorous $platform senior code reviewer and quality gate.

            You receive:
            - Kimi's situation analysis and implementation plan
            - The actual file contents and web search results that were fetched based on that plan
            - The full conversation history

            Your job: validate and improve the plan with the full context now available.

            Check:
            - Does the plan actually solve what the user needs (including things not yet delivered)?
            - Do the APIs/methods/classes referenced in the plan actually exist in the fetched files?
            - Are there threading issues, lifecycle bugs, missing null checks, memory leaks?
            - Are there missing route registrations, missing imports, missing pubspec entries?
            - Does the plan account for the project's actual code patterns (not generic patterns)?
            - If web search results are in context, does the plan match the current documented API?

            IMPORTANT: For every file edit in the plan, note the EXACT existing code block that will
            be replaced (by quoting a key line or two from the fetched file content). GPT will need
            this to produce a correct <search> block. If you cannot identify the exact existing code,
            flag it in the plan so GPT knows to locate it carefully.

            Output: the validated and improved plan only — no code, no markdown code blocks.
            Keep the same structure as Kimi's plan (ACTION, WHAT IS STILL NEEDED, PLAN steps).
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nProject dependencies:\n")
            append(deps.toPromptContext())
        }
    }

    /**
     * GPT-5 is the executor. It receives Gemini's validated plan + all context
     * and produces the actual output — files, explanations, or both.
     */
    private fun gptExecuteSystem(info: ProjectTypeDetector.ProjectInfo, deps: ProjectDependencyAnalyzer.ProjectDeps) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Mobile"
        }
        append("""
            You are an elite $platform developer executing a fully validated implementation plan.

            The plan's ACTION field tells you exactly what to produce. Follow it precisely.

            ── IF ACTION is EXPLAIN_ONLY ────────────────────────────────────────
            Write a clear, direct, expert-level explanation with markdown formatting.
            Use code snippets where helpful. Be concrete, never vague.
            ────────────────────────────────────────────────────────────────────

            ── IF ACTION is CREATE_FILES, MODIFY_FILES, FIX_BUG, or MIXED ──────

            NEW FILE — use this when the file does not yet exist:
            <new_file path="relative/path/to/NewFile.ext">
            COMPLETE FILE CONTENTS — every line of the new file
            </new_file>

            EXISTING FILE EDIT — use this for every change to an existing file:
            <file_change path="relative/path/to/ExistingFile.ext">
            <search>
            EXACT VERBATIM COPY of the existing code block you are replacing.
            Must match the file character-for-character. Copy it from the context — do not paraphrase.
            Include enough surrounding lines (the full function, class, or block) so it is unambiguous.
            </search>
            <replace>
            THE NEW CODE that replaces the search block above.
            Only this section changes — everything else in the file is untouched.
            </replace>
            </file_change>

            One <file_change> block per distinct edit location. If you need to change two separate
            sections of the same file, use two <file_change> blocks with the same path.

            ABSOLUTE RULES:
            ❌ NEVER write markdown code blocks (``` dart, ``` kotlin, etc.)
            ❌ NEVER write Step-by-step tutorial instructions
            ❌ NEVER write "// ... rest of file", "// existing code", "// TODO"
            ❌ NEVER put the entire file in <search> or <replace> — only the changed block
            ❌ NEVER make up code for the <search> block — copy it exactly from the provided file context
            ❌ NEVER tell the user what to do — you do it inside the file tags

            After all file tags: 1-2 sentences max describing what was created/changed.
            For MIXED: write explanation first, then all file tags.
            ────────────────────────────────────────────────────────────────────

            File extensions: .kt=Kotlin  .dart=Dart  .xml=XML  .yaml=YAML/pubspec
            If adding a new package: also output the updated pubspec.yaml or build.gradle.
            Use web search results if present — they contain current API documentation.
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

        // ── Step 1: Kimi decides what it needs — no pre-fetching ─────────────
        onStatus("Analyzing request...")

        // Give Kimi the project file listing so it can ask for specific files
        val projectListing = try {
            ApplicationManager.getApplication().runReadAction<String> {
                buildProjectListing(project)
            }
        } catch (_: Exception) { "" }

        // Fetch any URLs the user pasted — these are explicit, not rule-decided
        val urlContext = CompletableFuture.supplyAsync { UrlContentFetcher.fetchAll(userMessage) }

        callModelBlocking(
            settings.kimiModel, settings.kimiApiKey,
            kimiPlannerSystem(info, deps),
            buildPlannerPrompt(userMessage, history, context, projectListing)
        ).thenCompose { kimiRaw ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture(kimiRaw)
            onReasoning(kimiRaw)

            val plan = parseKimiPlan(kimiRaw)

            // ── Step 2: Fetch exactly what Kimi asked for, in parallel ────────
            onStatus("Gathering context...")

            val fileFuture = CompletableFuture.supplyAsync {
                fetchRequestedFiles(project, plan.requestedFiles)
            }
            val searchFuture = CompletableFuture.supplyAsync {
                runSearchQueries(plan.searchQueries)
            }

            CompletableFuture.allOf(fileFuture, searchFuture, urlContext)
                .thenApply {
                    val fileCtx    = try { fileFuture.get() } catch (_: Exception) { "" }
                    val searchCtx  = try { searchFuture.get() } catch (_: Exception) { "" }
                    val urlCtx     = try { urlContext.get() } catch (_: Exception) { "" }
                    val fullCtx = listOfNotNull(
                        context.takeIf { it.isNotBlank() && it != "No file currently open." },
                        fileCtx.takeIf { it.isNotBlank() },
                        searchCtx.takeIf { it.isNotBlank() },
                        urlCtx.takeIf { it.isNotBlank() }
                    ).joinToString("\n\n===\n\n")
                    plan to fullCtx
                }
        }.thenCompose { (plan, fullCtx) ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture(plan.rawText to fullCtx)

            // ── Step 3: Gemini validates with full context ────────────────────
            // Skip for pure explanations — no files involved, no compatibility to check
            if (plan.isExplainOnly) {
                return@thenCompose CompletableFuture.completedFuture(plan.rawText to fullCtx)
            }

            onStatus("Validating plan...")
            callModelBlocking(
                settings.geminiModel, settings.geminiApiKey,
                geminiReviewSystem(info, deps),
                buildGeminiPrompt(userMessage, plan.rawText, fullCtx, history)
            ).thenApply { review ->
                if (review.isNotBlank()) {
                    onReasoning("GEMINI REVIEW:\n$review")
                    review to fullCtx
                } else {
                    plan.rawText to fullCtx
                }
            }
        }.thenCompose { (validatedPlan, fullCtx) ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")

            // ── Step 4: GPT executes the validated plan ───────────────────────
            onStatus("Working...")
            callModelStreaming(
                settings.gptModel, settings.gptApiKey,
                gptExecuteSystem(info, deps),
                buildExecutePrompt(userMessage, validatedPlan, fullCtx, history),
                images, onToken
            )
        }
        .thenAccept { onComplete(it) }
        .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
    }

    // ─── Kimi plan parser ─────────────────────────────────────────────────────

    /**
     * Reads Kimi's structured output to extract what context it needs.
     * Kimi makes the decisions — this just reads them.
     */
    private fun parseKimiPlan(raw: String): KimiPlan {
        fun extractLine(key: String): String {
            val line = raw.lines().firstOrNull {
                it.trim().startsWith(key, ignoreCase = true)
            } ?: return ""
            return line.substringAfter(":").trim()
        }

        val action = extractLine("ACTION").takeIf { it.isNotBlank() } ?: "CREATE_FILES"

        val filesLine = extractLine("FILES_NEEDED")
        val requestedFiles = if (filesLine.isBlank() || filesLine.equals("none", ignoreCase = true)) {
            emptyList()
        } else {
            filesLine.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }

        val searchLine = extractLine("SEARCH_QUERIES")
        val searchQueries = if (searchLine.isBlank() || searchLine.equals("none", ignoreCase = true)) {
            emptyList()
        } else {
            searchLine.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }

        return KimiPlan(action, requestedFiles, searchQueries, raw)
    }

    // ─── Context fetchers (driven by Kimi's output) ───────────────────────────

    /** Read exactly the files Kimi asked for — not a TF-IDF ranking, Kimi's explicit request. */
    private fun fetchRequestedFiles(project: Project?, paths: List<String>): String {
        if (project == null || paths.isEmpty()) return ""
        val base = project.basePath ?: return ""
        val sb = StringBuilder()
        paths.forEach { relativePath ->
            try {
                val file = java.io.File("$base/$relativePath")
                if (file.exists() && file.isFile && file.length() < 200_000L) {
                    val content = file.readText(Charsets.UTF_8)
                    val ext = relativePath.substringAfterLast('.', "")
                    sb.append("─── $relativePath ───\n```$ext\n${content.take(8000)}\n```\n\n")
                }
            } catch (_: Exception) {}
        }
        return sb.toString().trim()
    }

    /** Run exactly the search queries Kimi specified — no rules about when to search. */
    private fun runSearchQueries(queries: List<String>): String {
        if (queries.isEmpty()) return ""
        val results = queries.mapNotNull { query ->
            try { WebSearchService.search(query) } catch (_: Exception) { null }
        }
        return results.joinToString("\n\n---\n\n").trim()
    }

    // ─── Context helpers ──────────────────────────────────────────────────────

    /** Project file listing: paths + top symbols, no file contents. Kimi uses this to decide what to read. */
    private fun buildProjectListing(project: Project?): String {
        if (project == null) return ""
        return try {
            val entries = ProjectIndexer.index(project).take(300)
            if (entries.isEmpty()) return ""
            buildString {
                append("PROJECT FILES (${entries.size} source files):\n")
                entries.forEach { f ->
                    val syms = if (f.symbols.isNotEmpty()) " [${f.symbols.take(4).joinToString(", ")}]" else ""
                    append("  ${f.relativePath}$syms\n")
                }
            }
        } catch (_: Exception) { "" }
    }

    // ─── Agentic test-fix loop ────────────────────────────────────────────────

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
        val fileList = changedFiles.joinToString(", ")
        val fileContents = fetchRequestedFiles(project, changedFiles)

        onStatus("Analyzing failures...")
        callModelBlocking(settings.kimiModel, settings.kimiApiKey,
            kimiPlannerSystem(info, deps),
            """
            ═══ SITUATION ═══
            Test failures occurred after changes to: $fileList

            ACTION: FIX_BUG
            FILES_NEEDED: $fileList
            SEARCH_QUERIES: NONE

            ═══ CHANGED FILES ═══
            $fileContents

            ═══ TEST OUTPUT ═══
            ${failureOutput.take(4000)}

            Analyze the failures and produce a precise plan to fix ALL of them.
            """.trimIndent()
        ).thenCompose { plan ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")
            onReasoning(plan)
            onStatus("Fixing...")
            callModelStreaming(settings.gptModel, settings.gptApiKey,
                gptExecuteSystem(info, deps),
                buildExecutePrompt("Fix the test failures in $fileList", plan, fileContents, emptyList()),
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
            callModelSync(settings.gptModel, settings.gptApiKey, gptInlineSystem,
                "LANGUAGE: $language\nPREFIX:\n$prefix\nSUFFIX:\n$suffix\nCOMPLETION:",
                timeoutSec = 5, maxTokens = 80)
                .take(400)
        } catch (_: Exception) { "" }
    }

    // ─── Prompt builders ──────────────────────────────────────────────────────

    private fun historyBlock(history: List<Message>) =
        history.takeLast(10).joinToString("\n\n") { msg ->
            val label = if (msg.role == "user") "USER" else "ASSISTANT (previously delivered)"
            "[$label]:\n${msg.content.take(1500)}"
        }

    /**
     * Kimi's planner prompt: history + current editor context + project file listing + request.
     * Kimi sees the full picture and decides what else it needs.
     */
    private fun buildPlannerPrompt(msg: String, history: List<Message>, editorCtx: String, projectListing: String) = buildString {
        val h = historyBlock(history)
        if (h.isNotEmpty()) append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        editorCtx.takeIf { it.isNotBlank() }?.let { append("═══ ACTIVE EDITOR CONTEXT ═══\n$it\n\n") }
        projectListing.takeIf { it.isNotBlank() }?.let { append("═══ PROJECT FILE LISTING ═══\n$it\n\n") }
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("Produce your CONTEXT PLAN (FILES_NEEDED, SEARCH_QUERIES) followed by your SITUATION ANALYSIS and ACTION PLAN.")
    }

    /**
     * Gemini's review prompt: Kimi's plan + full fetched context + history.
     */
    private fun buildGeminiPrompt(msg: String, kimiPlan: String, fullCtx: String, history: List<Message>) = buildString {
        val h = historyBlock(history)
        if (h.isNotEmpty()) append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        fullCtx.takeIf { it.isNotBlank() }?.let { append("═══ FETCHED CONTEXT (files + search) ═══\n$it\n\n") }
        append("═══ KIMI'S PLAN ═══\n$kimiPlan\n\n")
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("Validate and improve Kimi's plan using the fetched file contents and search results above.")
    }

    /**
     * GPT's execution prompt: validated plan + full context + history + request.
     */
    private fun buildExecutePrompt(msg: String, plan: String, fullCtx: String, history: List<Message>) = buildString {
        val h = historyBlock(history)
        if (h.isNotEmpty()) append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        fullCtx.takeIf { it.isNotBlank() }?.let { append("═══ PROJECT CONTEXT ═══\n$it\n\n") }
        append("═══ VALIDATED PLAN ═══\n$plan\n\n")
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("Execute the plan completely. Follow the ACTION field. Output every file in <new_file> or <file_change> tags with COMPLETE contents. Start now.")
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
            if (choices == null || choices.size() == 0) { LOG.warn("Empty choices: ${body.take(200)}"); return body }
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
