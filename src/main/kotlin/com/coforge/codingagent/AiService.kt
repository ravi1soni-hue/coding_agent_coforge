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
    val action: String,
    val requestedFiles: List<String>,
    val searchQueries: List<String>,
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
     * Gap 5: projectRules from COFORGE.md injected into every Kimi prompt.
     * Kimi is the brain: reads history + context + project listing, outputs a CONTEXT PLAN.
     */
    private fun kimiPlannerSystem(
        info: ProjectTypeDetector.ProjectInfo,
        deps: ProjectDependencyAnalyzer.ProjectDeps,
        projectRules: String = ""
    ) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER      -> "Flutter / Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android / ${info.mainLanguage}"
            ProjectTypeDetector.ProjectType.UNKNOWN      -> "Mobile"
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
            - List EVERY file that your plan will CREATE or MODIFY — this is mandatory, not optional
            - Also list files you need to READ to understand context
            - Use exact relative paths from the project listing (e.g. lib/main.dart)
            - If the active file is already included in context, do not list it again
            - 0 to 8 files max

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

        // Gap 5: project-specific rules always injected
        if (projectRules.isNotBlank()) {
            append("\n\n════════════════════════════════════════════════════════\n")
            append("PROJECT-SPECIFIC RULES (from COFORGE.md — must be followed for every response):\n")
            append(projectRules)
        }
    }

    /**
     * Gap 7: Gemini is now a PRE-WRITE VERIFIER, not a plan reviewer.
     *
     * Before GPT writes anything, Gemini checks:
     * 1. Are ALL files that the plan will modify present in the fetched context?
     *    If not → output MISSING_FILES so we can fetch them before GPT runs.
     * 2. For each planned edit, quote the EXACT existing code block that will be replaced.
     *    GPT needs this verbatim for its <search> sections.
     * 3. Are the APIs/classes referenced in the plan real and present in the fetched files?
     */
    private fun geminiVerifierSystem(
        info: ProjectTypeDetector.ProjectInfo,
        deps: ProjectDependencyAnalyzer.ProjectDeps,
        projectRules: String = ""
    ) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER      -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN      -> "Mobile"
        }
        append("""
            You are a $platform pre-write verifier for an AI coding agent pipeline.

            You receive:
            - Kimi's situation analysis and implementation plan
            - The actual file contents that were fetched based on that plan
            - The current user request

            YOUR JOB: Verify the plan is implementable before GPT writes anything.

            ── CHECK 1: FILES COVERAGE ──────────────────────────────────────────
            Every file the plan will EDIT must be in the fetched context.
            If any file the plan edits is NOT in the fetched context, output:
            MISSING_FILES: relative/path/file1.dart, relative/path/file2.kt
            (These will be auto-fetched before GPT runs.)

            ── CHECK 2: EXACT CODE QUOTES ───────────────────────────────────────
            For EACH planned edit to an existing file, find the exact code block
            that needs to change and quote it verbatim from the fetched file content.
            Format each one as:
            EDIT_ANCHOR [relative/path/file.dart]:
            ```
            exact verbatim code from the file that will be replaced
            ```
            GPT MUST use these exact quotes in its <search> sections.

            ── CHECK 3: API CORRECTNESS ─────────────────────────────────────────
            Verify that classes, methods, and widgets referenced in the plan actually
            exist in the fetched files. Flag anything that doesn't exist.

            ── CHECK 4: MISSING IMPORTS / ROUTES ────────────────────────────────
            Note any imports that will need to be added, route registrations, or
            pubspec entries. Flag them explicitly for GPT.

            Output format:
            VERIFICATION_STATUS: PASS | NEEDS_ADJUSTMENT

            [If MISSING_FILES exist, list them — the system will auto-fetch.]

            VERIFIED PLAN:
            [The same plan as Kimi's, but augmented with EDIT_ANCHOR quotes and any corrections.
             Keep ACTION, WHAT IS STILL NEEDED, and PLAN structure.]

            Do not write code blocks for the implementation. Only quote existing code for anchors.
        """.trimIndent())

        if (!deps.isEmpty()) {
            append("\n\nProject dependencies:\n")
            append(deps.toPromptContext())
        }

        if (projectRules.isNotBlank()) {
            append("\n\nPROJECT RULES:\n$projectRules")
        }
    }

    /**
     * GPT-5 is the executor. Receives verified plan + full context (with edit anchors)
     * and produces actual file output.
     *
     * Gap 5: projectRules injected here too so GPT follows project conventions.
     */
    private fun gptExecuteSystem(
        info: ProjectTypeDetector.ProjectInfo,
        deps: ProjectDependencyAnalyzer.ProjectDeps,
        projectRules: String = ""
    ) = buildString {
        val platform = when (info.type) {
            ProjectTypeDetector.ProjectType.FLUTTER      -> "Flutter/Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android/Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN      -> "Mobile"
        }
        append("""
            You are an elite $platform developer executing a fully verified implementation plan.

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
            Use the EDIT_ANCHOR quotes from the verified plan — they are already extracted for you.
            Must match the file character-for-character.
            Include the full function/class/block so it is unambiguous.
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
            ❌ NEVER make up code for the <search> block — use the EDIT_ANCHOR from the plan
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

        // Gap 5: project rules always injected into executor too
        if (projectRules.isNotBlank()) {
            append("\n\n════════════════════════════════════════════════════════\n")
            append("PROJECT RULES (from COFORGE.md — must be followed):\n")
            append(projectRules)
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

        // Gap 5: read persistent project rules from COFORGE.md
        val projectRules = readProjectRules(project)

        // Gap 6: pass file outlines (paths + all symbols) to Kimi for 2-pass context
        val projectListing = try {
            ApplicationManager.getApplication().runReadAction<String> {
                buildProjectListing(project)
            }
        } catch (_: Exception) { "" }

        // Fetch URLs the user pasted — explicit, not rule-decided
        val urlContext = CompletableFuture.supplyAsync { UrlContentFetcher.fetchAll(userMessage) }

        // ── Step 1: Kimi decides what it needs ───────────────────────────────
        onStatus("Analyzing request...")

        callModelBlocking(
            settings.kimiModel, settings.kimiApiKey,
            kimiPlannerSystem(info, deps, projectRules),
            buildPlannerPrompt(userMessage, history, context, projectListing)
        ).thenCompose { kimiRaw ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture(kimiRaw)
            onReasoning(kimiRaw)

            val plan = parseKimiPlan(kimiRaw)

            // Gap 2: auto-expand FILES_NEEDED with file paths found in Kimi's plan text
            val expandedFiles = expandFilesNeeded(plan, kimiRaw, project)

            // ── Step 2: Fetch exactly what Kimi asked for (expanded), in parallel ─
            onStatus("Gathering context...")

            val fileFuture   = CompletableFuture.supplyAsync { fetchRequestedFiles(project, expandedFiles) }
            val searchFuture = CompletableFuture.supplyAsync { runSearchQueries(plan.searchQueries) }

            CompletableFuture.allOf(fileFuture, searchFuture, urlContext)
                .thenApply {
                    val fileCtx   = try { fileFuture.get() } catch (_: Exception) { "" }
                    val searchCtx = try { searchFuture.get() } catch (_: Exception) { "" }
                    val urlCtx    = try { urlContext.get() } catch (_: Exception) { "" }
                    val fullCtx   = listOfNotNull(
                        context.takeIf { it.isNotBlank() && it != "No file currently open." },
                        fileCtx.takeIf { it.isNotBlank() },
                        searchCtx.takeIf { it.isNotBlank() },
                        urlCtx.takeIf { it.isNotBlank() }
                    ).joinToString("\n\n===\n\n")
                    plan to fullCtx
                }
        }.thenCompose { planAndCtx ->
            val plan    = planAndCtx.first
            val fullCtx = planAndCtx.second

            if (stopRequested) return@thenCompose CompletableFuture.completedFuture(plan.rawText to fullCtx)

            // Skip Gemini for pure explanations — no file edits involved
            if (plan.isExplainOnly) {
                return@thenCompose CompletableFuture.completedFuture(plan.rawText to fullCtx)
            }

            // ── Step 3: Gemini verifies plan and fetches any missing files ────
            // Gap 7: Gemini is now a pre-write verifier, not a plan reviewer
            onStatus("Verifying plan...")
            callModelBlocking(
                settings.geminiModel, settings.geminiApiKey,
                geminiVerifierSystem(info, deps, projectRules),
                buildGeminiVerifyPrompt(userMessage, plan.rawText, fullCtx, history)
            ).thenCompose { review ->
                if (review.isBlank()) return@thenCompose CompletableFuture.completedFuture(plan.rawText to fullCtx)
                onReasoning("GEMINI VERIFICATION:\n$review")

                // Gap 2: Gemini may flag MISSING_FILES — fetch them and augment context
                val missingFiles = parseMissingFiles(review)
                if (missingFiles.isEmpty()) {
                    CompletableFuture.completedFuture(review to fullCtx)
                } else {
                    onStatus("Fetching ${missingFiles.size} additional file(s)...")
                    CompletableFuture.supplyAsync {
                        val extra = fetchRequestedFiles(project, missingFiles)
                        val augmented = if (extra.isBlank()) fullCtx
                                        else "$fullCtx\n\n=== ADDITIONALLY FETCHED FILES ===\n$extra"
                        review to augmented
                    }
                }
            }
        }.thenCompose { verifiedAndCtx ->
            val verifiedPlan = verifiedAndCtx.first
            val fullCtx      = verifiedAndCtx.second

            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")

            // ── Step 4: GPT implements, with agentic retry on search failures ─
            // Gap 1: after GPT streams, verify search blocks → retry once if any fail
            onStatus("Implementing...")
            implementWithAgenticRetry(
                settings, info, deps, projectRules,
                userMessage, verifiedPlan, fullCtx, history, images, project,
                onStatus, onToken
            )
        }
        .thenAccept { onComplete(it) }
        .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
    }

    /**
     * Gap 1 (Agentic iteration): GPT implements, then we verify all <search> blocks
     * exist in the real files. If any fail, send GPT the actual file content and retry once.
     */
    private fun implementWithAgenticRetry(
        settings: AppSettingsState,
        info: ProjectTypeDetector.ProjectInfo,
        deps: ProjectDependencyAnalyzer.ProjectDeps,
        projectRules: String,
        userMessage: String,
        verifiedPlan: String,
        fullCtx: String,
        history: List<Message>,
        images: List<String>,
        project: Project?,
        onStatus: (String) -> Unit,
        onToken: (String) -> Unit
    ): CompletableFuture<String> {
        return callModelStreaming(
            settings.gptModel, settings.gptApiKey,
            gptExecuteSystem(info, deps, projectRules),
            buildExecutePrompt(userMessage, verifiedPlan, fullCtx, history),
            images, onToken
        ).thenCompose { output ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture(output)

            // Verify <search> blocks against actual files on disk
            val failures = verifySearchBlocks(output, project)
            if (failures.isEmpty()) return@thenCompose CompletableFuture.completedFuture(output)

            // One agentic retry: inject actual file content for failing blocks
            onStatus("Correcting ${failures.size} search block(s)...")
            callModelStreaming(
                settings.gptModel, settings.gptApiKey,
                gptExecuteSystem(info, deps, projectRules),
                buildSearchRetryPrompt(userMessage, verifiedPlan, fullCtx, history, output, failures),
                images, onToken
            )
        }
    }

    // ─── Gap 1: Search block verification ────────────────────────────────────

    data class SearchFailure(val path: String, val searchBlock: String, val actualContent: String)

    /**
     * After GPT generates output, check every <search> block against the actual file on disk.
     * Returns list of failures (where search text wasn't found in the file).
     */
    private fun verifySearchBlocks(gptOutput: String, project: Project?): List<SearchFailure> {
        val base = project?.basePath ?: return emptyList()
        val failures = mutableListOf<SearchFailure>()
        val re = Regex("""<file_change\s+path="([^"]+)">\s*<search>([\s\S]*?)</search>""")
        re.findAll(gptOutput).forEach { m ->
            val path   = m.groupValues[1].trim()
            val search = m.groupValues[2].trim()
            val file   = java.io.File("$base/$path")
            if (!file.exists()) {
                failures.add(SearchFailure(path, search, "[File does not exist: $path]"))
                return@forEach
            }
            val content = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return@forEach }
            // Exact match
            if (content.contains(search)) return@forEach
            // Whitespace-normalized match
            val normContent = content.lines().joinToString("\n") { it.trimEnd() }
            val normSearch  = search.lines().joinToString("\n") { it.trimEnd() }
            if (!normContent.contains(normSearch)) {
                failures.add(SearchFailure(path, search, content.take(6000)))
            }
        }
        return failures
    }

    // ─── Gap 2: FILES_NEEDED auto-expansion ──────────────────────────────────

    /**
     * Scans Kimi's raw plan text for file path patterns and adds any that exist on disk
     * to the files-to-fetch list. Catches cases where Kimi mentions a file in the plan
     * but forgets to list it under FILES_NEEDED.
     */
    private fun expandFilesNeeded(plan: KimiPlan, kimiRaw: String, project: Project?): List<String> {
        val base = project?.basePath ?: return plan.requestedFiles
        val expanded = plan.requestedFiles.toMutableSet()

        // Scan plan text for paths like lib/foo/bar.dart, app/src/main/.../Foo.kt
        val filePathRe = Regex("""(?:lib|app|src|android|ios|test|feature|data|domain|presentation)/[\w/\-]+\.(?:dart|kt|java|xml|yaml|gradle|kts)""")
        filePathRe.findAll(kimiRaw).forEach { m ->
            val path = m.value.trim()
            if (java.io.File("$base/$path").exists()) {
                expanded.add(path)
            }
        }
        return expanded.toList()
    }

    // ─── Gap 7: Parse MISSING_FILES from Gemini's verification output ─────────

    private fun parseMissingFiles(geminiOutput: String): List<String> {
        val line = geminiOutput.lines().firstOrNull {
            it.trim().startsWith("MISSING_FILES:", ignoreCase = true)
        } ?: return emptyList()
        val value = line.substringAfter(":").trim()
        return if (value.isBlank() || value.equals("none", ignoreCase = true)) emptyList()
               else value.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    // ─── Kimi plan parser ─────────────────────────────────────────────────────

    private fun parseKimiPlan(raw: String): KimiPlan {
        fun extractLine(key: String): String {
            val line = raw.lines().firstOrNull {
                it.trim().startsWith(key, ignoreCase = true)
            } ?: return ""
            return line.substringAfter(":").trim()
        }

        val action = extractLine("ACTION").takeIf { it.isNotBlank() } ?: "CREATE_FILES"

        val filesLine = extractLine("FILES_NEEDED")
        val requestedFiles = if (filesLine.isBlank() || filesLine.equals("none", ignoreCase = true)) emptyList()
                             else filesLine.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val searchLine = extractLine("SEARCH_QUERIES")
        val searchQueries = if (searchLine.isBlank() || searchLine.equals("none", ignoreCase = true)) emptyList()
                            else searchLine.split(",").map { it.trim() }.filter { it.isNotBlank() }

        return KimiPlan(action, requestedFiles, searchQueries, raw)
    }

    // ─── Context fetchers ─────────────────────────────────────────────────────

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

    private fun runSearchQueries(queries: List<String>): String {
        if (queries.isEmpty()) return ""
        return queries.mapNotNull { query ->
            try { WebSearchService.search(query) } catch (_: Exception) { null }
        }.joinToString("\n\n---\n\n").trim()
    }

    // ─── Gap 5: COFORGE.md / .coforge-rules reader ───────────────────────────

    fun readProjectRules(project: Project?): String {
        val base = project?.basePath ?: return ""
        return listOf("COFORGE.md", ".coforge-rules", "coforge.md", ".coforge")
            .firstNotNullOfOrNull { name ->
                runCatching {
                    val f = java.io.File("$base/$name")
                    if (f.exists() && f.isFile) f.readText(Charsets.UTF_8).trim().take(4000)
                    else null
                }.getOrNull()?.takeIf { it.isNotBlank() }
            } ?: ""
    }

    // ─── Gap 6: Project file listing with full symbol set ────────────────────

    /** Build file outline: paths + ALL class/function symbols. No file content. */
    private fun buildProjectListing(project: Project?): String {
        if (project == null) return ""
        return try {
            val entries = ProjectIndexer.index(project).take(300)
            if (entries.isEmpty()) return ""
            buildString {
                append("PROJECT FILES (${entries.size} source files):\n")
                entries.forEach { f ->
                    // Gap 6: include more symbols (10 instead of 4) for better context
                    val syms = if (f.symbols.isNotEmpty()) " [${f.symbols.take(10).joinToString(", ")}]" else ""
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
        val projectRules = readProjectRules(project)
        val fileList = changedFiles.joinToString(", ")
        val fileContents = fetchRequestedFiles(project, changedFiles)

        onStatus("Analyzing failures...")
        callModelBlocking(settings.kimiModel, settings.kimiApiKey,
            kimiPlannerSystem(info, deps, projectRules),
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
            onStatus("Fixing...")
            callModelStreaming(settings.gptModel, settings.gptApiKey,
                gptExecuteSystem(info, deps, projectRules),
                buildExecutePrompt("Fix the test failures in $fileList", plan, fileContents, emptyList()),
                emptyList(), onToken)
        }
        .thenAccept { onComplete(it) }
        .exceptionally { ex -> onComplete("Error: ${ex.cause?.message ?: ex.message}"); null }
    }

    // ─── Gap 4: Auto-fix after lint/analyze errors ────────────────────────────

    /**
     * Called by ChatToolWindowContent after auto-verify finds errors.
     * Sends lint/analyze output through a targeted fix chain.
     */
    fun fixLintErrors(
        lintOutput: String,
        project: Project,
        onStatus: (String) -> Unit,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        stopRequested = false
        val settings = AppSettingsState.instance
        val info = ProjectTypeDetector.detect(project)
        val deps = ProjectDependencyAnalyzer.analyze(project)
        val projectRules = readProjectRules(project)

        // Extract just the error lines to keep context small
        val errorLines = lintOutput.lines()
            .filter { it.contains("error", ignoreCase = true) || it.contains("warning", ignoreCase = true) }
            .take(40)
            .joinToString("\n")

        if (errorLines.isBlank()) { onComplete(""); return }

        // Parse file paths from error output to fetch relevant files
        val errorPaths = Regex("""(?:lib|app|src|android|ios|test)/[\w/\-]+\.(?:dart|kt|java|xml)""")
            .findAll(lintOutput)
            .map { it.value.trim() }
            .distinct()
            .take(6)
            .toList()

        onStatus("Reading error files...")
        val fileContents = fetchRequestedFiles(project, errorPaths)

        onStatus("Fixing lint errors...")
        callModelBlocking(settings.kimiModel, settings.kimiApiKey,
            kimiPlannerSystem(info, deps, projectRules),
            """
            ═══ SITUATION ═══
            Post-apply lint/analyze errors. Need to fix all of them.

            ACTION: FIX_BUG
            FILES_NEEDED: ${errorPaths.joinToString(", ")}
            SEARCH_QUERIES: NONE

            ═══ ERROR FILES ═══
            $fileContents

            ═══ LINT OUTPUT ═══
            $errorLines

            Produce a precise plan to fix ALL lint errors shown.
            """.trimIndent()
        ).thenCompose { plan ->
            if (stopRequested) return@thenCompose CompletableFuture.completedFuture("")
            onStatus("Applying lint fixes...")
            implementWithAgenticRetry(
                settings, info, deps, projectRules,
                "Fix lint errors", plan, fileContents, emptyList(), emptyList(),
                project, onStatus, onToken
            )
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

    /**
     * Gap 8: Smart history — keeps last 6 turns verbatim; compresses older ones.
     * Prevents context bloat while preserving recent context accurately.
     */
    private fun buildSmartHistory(history: List<Message>): String {
        if (history.isEmpty()) return ""
        val verbatimCutoff = 6
        val recent  = history.takeLast(verbatimCutoff)
        val older   = history.dropLast(verbatimCutoff)

        val sb = StringBuilder()

        if (older.isNotEmpty()) {
            sb.append("EARLIER CONVERSATION (${older.size} turns, summarized):\n")
            older.forEach { msg ->
                val label = if (msg.role == "user") "USER" else "ASSISTANT"
                sb.append("  [$label]: ${msg.content.take(120).replace('\n', ' ')}\n")
            }
            sb.append("\n")
        }

        recent.forEach { msg ->
            val label = if (msg.role == "user") "USER" else "ASSISTANT (previously delivered)"
            sb.append("[$label]:\n${msg.content.take(2000)}\n\n")
        }

        return sb.toString().trimEnd()
    }

    private fun buildPlannerPrompt(msg: String, history: List<Message>, editorCtx: String, projectListing: String) = buildString {
        val h = buildSmartHistory(history)
        if (h.isNotEmpty()) append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        editorCtx.takeIf { it.isNotBlank() }?.let { append("═══ ACTIVE EDITOR CONTEXT ═══\n$it\n\n") }
        projectListing.takeIf { it.isNotBlank() }?.let { append("═══ PROJECT FILE LISTING ═══\n$it\n\n") }
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("Produce your CONTEXT PLAN (FILES_NEEDED, SEARCH_QUERIES) followed by your SITUATION ANALYSIS and ACTION PLAN.")
    }

    /**
     * Gap 7: Gemini verification prompt — focused on checking file coverage and quoting edit locations.
     */
    private fun buildGeminiVerifyPrompt(msg: String, kimiPlan: String, fullCtx: String, history: List<Message>) = buildString {
        val h = buildSmartHistory(history)
        if (h.isNotEmpty()) append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        fullCtx.takeIf { it.isNotBlank() }?.let { append("═══ FETCHED FILES & SEARCH RESULTS ═══\n$it\n\n") }
        append("═══ KIMI'S PLAN ═══\n$kimiPlan\n\n")
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("""
            Verify this plan against the fetched file contents above.
            1. List any MISSING_FILES (files the plan edits that are NOT in the fetched context above).
            2. For each planned edit, find and quote the exact EDIT_ANCHOR from the fetched file.
            3. Check API correctness and flag anything missing.
        """.trimIndent())
    }

    private fun buildExecutePrompt(msg: String, plan: String, fullCtx: String, history: List<Message>) = buildString {
        val h = buildSmartHistory(history)
        if (h.isNotEmpty()) append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        fullCtx.takeIf { it.isNotBlank() }?.let { append("═══ PROJECT CONTEXT ═══\n$it\n\n") }
        append("═══ VERIFIED PLAN (with EDIT_ANCHOR quotes) ═══\n$plan\n\n")
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("Execute the plan completely. Use the EDIT_ANCHOR quotes for all <search> blocks. Start now.")
    }

    /**
     * Gap 1 (Agentic retry): GPT's previous output had <search> blocks that didn't match real files.
     * Inject the actual file content and ask GPT to regenerate only the failing blocks.
     */
    private fun buildSearchRetryPrompt(
        msg: String, plan: String, fullCtx: String, history: List<Message>,
        previousOutput: String, failures: List<SearchFailure>
    ) = buildString {
        val h = buildSmartHistory(history)
        if (h.isNotEmpty()) append("═══ CONVERSATION HISTORY ═══\n$h\n\n")
        fullCtx.takeIf { it.isNotBlank() }?.let { append("═══ PROJECT CONTEXT ═══\n$it\n\n") }
        append("═══ VERIFIED PLAN ═══\n$plan\n\n")
        append("═══ CURRENT REQUEST ═══\n$msg\n\n")
        append("═══ PREVIOUS RESPONSE (partial — had search failures) ═══\n")
        append(previousOutput.take(2000))
        append("\n\n")
        append("═══ SEARCH BLOCK FAILURES — ACTUAL FILE CONTENTS ═══\n")
        failures.forEach { failure ->
            append("FAILED path=\"${failure.path}\":\n")
            append("Your <search> block was:\n```\n${failure.searchBlock.take(500)}\n```\n\n")
            append("ACTUAL file content:\n```\n${failure.actualContent.take(4000)}\n```\n\n")
        }
        append("""
            Some of your <search> blocks did not match the real file content.
            The actual file content is shown above for each failure.

            Regenerate the ENTIRE response (all file tags), this time copying the <search> blocks
            VERBATIM from the actual file content shown above.
        """.trimIndent())
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
