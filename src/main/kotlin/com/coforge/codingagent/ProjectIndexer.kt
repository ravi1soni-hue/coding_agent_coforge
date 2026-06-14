package com.coforge.codingagent

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Indexes all source files in the project so AI has full codebase awareness —
 * not just the currently open file.
 *
 * Features:
 * - Walks up to MAX_FILES source files, skipping build/generated dirs
 * - Extracts class/function/widget names per language (Kotlin, Java, Dart, XML)
 * - Scores files against a query by: path match > symbol match > content match
 * - Returns top-N most relevant files with the most pertinent code section
 * - Result is cached per project; invalidated on session reset
 */
object ProjectIndexer {

    private val SOURCE_EXTS = setOf("kt", "java", "dart", "xml", "gradle", "kts", "yaml")
    private val SKIP_DIRS = setOf(
        "build", ".gradle", ".idea", ".git", "node_modules", ".dart_tool",
        ".pub-cache", "Pods", "generated", "gen", "intermediates", "release",
        "debug", "tmp", "cache", "outputs", ".cxx", "jniLibs"
    )
    // Skip these exactly — auto-generated, pollute the index
    private val SKIP_FILES = setOf("R.java", "BuildConfig.java", "R2.java")
    private val SKIP_SUFFIXES = setOf(".g.dart", ".freezed.dart", ".gr.dart", ".config.dart")

    private const val MAX_FILE_BYTES = 120_000L
    private const val MAX_FILES = 700
    private const val SNIPPET_LINES = 80  // lines of context to include per file

    data class FileEntry(
        val relativePath: String,
        val absolutePath: String,
        val symbols: List<String>,   // class/fun/widget names
        val content: String
    )

    // LRU cache capped at 5 projects — prevents unbounded growth in multi-project IDE sessions
    private val cache = object : LinkedHashMap<String, List<FileEntry>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileEntry>>) = size > 5
    }

    // Language-specific symbol extractors
    private val KT_SYMBOLS = Regex(
        """(?:^|\n)\s*(?:(?:public|private|protected|internal|abstract|sealed|data|open|inline|suspend)\s+)*(?:class|object|interface|fun|typealias)\s+(\w+)"""
    )
    private val JAVA_SYMBOLS = Regex(
        """(?:^|\n)\s*(?:public|private|protected|abstract|static|final\s+)*(?:class|interface|enum|@interface)\s+(\w+)"""
    )
    private val DART_SYMBOLS = Regex(
        """(?:^|\n)\s*(?:abstract\s+)?(?:class|mixin|extension|enum)\s+(\w+)"""
    )
    private val DART_FUNS = Regex(
        """(?:^|\n)\s*(?:Widget|Future|Stream|void|String|int|double|bool|List|Map)\s+(\w+)\s*\("""
    )
    private val XML_CLASSES = Regex("""<([A-Z]\w+(?:\.\w+)+)""")  // fully qualified XML view classes

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Build index for project (cached after first call). Thread-safe — called from background. */
    fun index(project: Project): List<FileEntry> {
        val base = project.basePath ?: return emptyList()
        return synchronized(cache) { cache[base] } ?: run {
            val entries = buildIndex(base)
            synchronized(cache) { cache[base] = entries }
            entries
        }
    }

    /**
     * Find the top-N most relevant files for a query.
     * Returns entries with the most relevant snippet pre-extracted.
     */
    fun findRelevant(project: Project, query: String, topN: Int = 5): List<FileEntry> {
        val all = index(project)
        if (all.isEmpty() || query.isBlank()) return emptyList()

        val baseWords = tokenize(query)
        if (baseWords.isEmpty()) return emptyList()
        // Gap 3: expand with semantic concept synonyms
        val words = expandWithConcepts(baseWords)

        return all
            .map { entry -> entry to scoreEntry(entry, words, baseWords) }
            .filter { (_, s) -> s > 0 }
            .sortedByDescending { (_, s) -> s }
            .take(topN)
            .map { (entry, _) ->
                entry.copy(content = extractRelevantSection(entry.content, baseWords, SNIPPET_LINES))
            }
    }

    /** Start indexing in a background thread so it's ready when first query arrives. */
    fun warmUp(project: Project) {
        Thread { index(project) }.apply { isDaemon = true; name = "CoforgeIndexer" }.start()
    }

    fun invalidate(project: Project) {
        synchronized(cache) { cache.remove(project.basePath) }
    }

    // ─── Scoring ──────────────────────────────────────────────────────────────

    /**
     * Gap 3: Semantic scoring. Uses concept-expanded words for broader recall,
     * but applies a higher weight when original (non-expanded) words match.
     * This gives exact matches priority while still surfacing semantically related files.
     */
    private fun scoreEntry(entry: FileEntry, expandedWords: Set<String>, baseWords: Set<String> = expandedWords): Int {
        var score = 0
        val fileName  = entry.relativePath.substringAfterLast("/").lowercase()
        val pathLower = entry.relativePath.lowercase()

        // File name match — highest weight; bonus for exact (non-concept-expanded) words
        expandedWords.forEach { w ->
            val isBase = w in baseWords
            when {
                fileName.contains(w) -> score += if (isBase) 12 else 5
                pathLower.contains(w) -> score += if (isBase) 5 else 2
            }
        }

        // Symbol (class/function) name match — very high weight
        val symsLower = entry.symbols.map { it.lowercase() }
        expandedWords.forEach { w ->
            val isBase = w in baseWords
            symsLower.forEach { sym ->
                when {
                    sym == w               -> score += if (isBase) 14 else 6
                    sym.contains(w)        -> score += if (isBase) 6 else 3
                    w.contains(sym) && sym.length > 3 -> score += 2
                }
            }
        }

        // Content keyword match — capped to avoid huge files dominating
        val contentLower = entry.content.lowercase()
        var hits = 0
        expandedWords.forEach { w -> if (contentLower.contains(w)) hits++ }
        score += minOf(hits * 2, 12)

        return score
    }

    // ─── Snippet extraction ───────────────────────────────────────────────────

    private fun extractRelevantSection(content: String, words: Set<String>, maxLines: Int): String {
        val lines = content.lines()
        if (lines.size <= maxLines) return content

        // Find line with most keyword hits
        var bestLine = 0
        var bestScore = 0
        lines.forEachIndexed { i, line ->
            val lower = line.lowercase()
            val hits = words.count { lower.contains(it) }
            if (hits > bestScore) { bestScore = hits; bestLine = i }
        }

        // Return window around that line
        val half = maxLines / 2
        val start = maxOf(0, bestLine - half)
        val end = minOf(lines.size, start + maxLines)
        return lines.subList(start, end).joinToString("\n")
    }

    // ─── Index builder ────────────────────────────────────────────────────────

    private fun buildIndex(base: String): List<FileEntry> {
        val root = File(base)
        val entries = mutableListOf<FileEntry>()
        var count = 0

        root.walkTopDown()
            .onEnter { dir ->
                dir.name !in SKIP_DIRS &&
                !dir.name.startsWith(".") &&
                count < MAX_FILES
            }
            .filter { file ->
                file.isFile &&
                file.extension in SOURCE_EXTS &&
                file.length() in 1..MAX_FILE_BYTES &&
                file.name !in SKIP_FILES &&
                SKIP_SUFFIXES.none { file.name.endsWith(it) }
            }
            .forEach { file ->
                if (count >= MAX_FILES) return@forEach
                try {
                    val content = file.readText(Charsets.UTF_8)
                    val relative = file.relativeTo(root).path.replace("\\", "/")
                    entries.add(FileEntry(relative, file.absolutePath, extractSymbols(content, file.extension), content))
                    count++
                } catch (_: Exception) {}
            }

        return entries
    }

    private fun extractSymbols(content: String, ext: String): List<String> {
        val regex: List<Regex> = when (ext) {
            "kt", "kts" -> listOf(KT_SYMBOLS)
            "java"       -> listOf(JAVA_SYMBOLS)
            "dart"       -> listOf(DART_SYMBOLS, DART_FUNS)
            "xml"        -> listOf(XML_CLASSES)
            else         -> return emptyList()
        }
        return regex.flatMap { r ->
            r.findAll(content).map { it.groupValues[1] }.filter { it.length in 2..80 }
        }.distinct()
    }

    // ─── Tokenizer ────────────────────────────────────────────────────────────

    private val STOP_WORDS = setOf(
        "the","and","for","with","this","that","from","have","has","use",
        "used","using","new","get","set","val","var","fun","class","def",
        "null","true","false","void","int","string","list","map","any",
        "override","return","import","package","private","public","protected"
    )

    private fun tokenize(text: String): Set<String> =
        // Split camelCase: "UserRepository" → "user", "repository"
        text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-zA-Z0-9_]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    // ─── Gap 3: Semantic concept groups (replaces pure TF-IDF) ───────────────
    //
    // When a query word belongs to a concept group, also score against all synonyms
    // in that group. Dramatically improves recall for common mobile dev concepts
    // (e.g. querying "login" also matches files containing "authenticate", "session").

    private val CONCEPT_GROUPS: List<Set<String>> = listOf(
        setOf("auth", "login", "logout", "signin", "signup", "password", "token", "jwt",
              "credential", "session", "authenticate", "authorization", "permissions"),
        setOf("network", "http", "api", "rest", "request", "response", "endpoint",
              "retrofit", "dio", "fetch", "url", "client", "interceptor", "header"),
        setOf("database", "db", "sql", "query", "table", "record", "store", "persist",
              "cache", "room", "realm", "sqlite", "hive", "shared", "preferences", "prefs"),
        setOf("navigation", "route", "screen", "page", "navigate", "push", "pop", "gorouter",
              "navhost", "fragment", "activity", "intent", "deeplink", "stack"),
        setOf("state", "viewmodel", "bloc", "provider", "riverpod", "getx", "redux",
              "store", "reducer", "cubit", "notifier", "statenotifier", "changenotifier"),
        setOf("ui", "widget", "composable", "view", "layout", "component", "button",
              "text", "image", "icon", "color", "theme", "style", "padding", "margin"),
        setOf("payment", "cart", "checkout", "order", "price", "product", "item",
              "stripe", "invoice", "billing", "subscription", "purchase"),
        setOf("user", "profile", "account", "customer", "member", "person",
              "identity", "avatar", "username", "email", "phone"),
        setOf("list", "recycler", "listview", "lazy", "scroll", "adapter", "items",
              "gridview", "flatlist", "listview", "virtualized"),
        setOf("error", "exception", "crash", "bug", "fail", "failure", "catch",
              "throw", "stacktrace", "debug", "log", "trace"),
        setOf("image", "photo", "picture", "bitmap", "drawable", "icon", "asset",
              "glide", "coil", "picasso", "imageview", "imageloader"),
        setOf("notification", "push", "fcm", "firebase", "alert", "banner",
              "message", "channel", "broadcast", "alarm"),
        setOf("test", "unit", "integration", "mock", "stub", "verify", "assert",
              "expect", "when", "given", "should", "spec"),
        setOf("permission", "grant", "deny", "request", "camera", "location",
              "storage", "microphone", "runtime", "manifest"),
        setOf("async", "coroutine", "suspend", "flow", "future", "promise",
              "callback", "rxjava", "observable", "single", "maybe", "completable"),
    )

    /** Expand a set of query words with semantic synonyms from concept groups. */
    private fun expandWithConcepts(words: Set<String>): Set<String> {
        val expanded = words.toMutableSet()
        words.forEach { word ->
            CONCEPT_GROUPS.forEach { group ->
                if (word in group) expanded.addAll(group)
            }
        }
        return expanded
    }
}
