package com.coforge.codingagent

import com.intellij.openapi.project.Project

/**
 * Lightweight import/usage graph built on top of ProjectIndexer.
 *
 * Enables real cross-file reasoning:
 *   - "Where is UserRepository used?" → finds all files that import it
 *   - "What does LoginViewModel depend on?" → traces its imports transitively
 *   - Query enrichment: symbols in the user's question → their definitions + callers included in context
 *
 * Design: uses already-indexed file content (no re-read), parsed with fast regex.
 * No AST — fast enough to run inline on a background thread.
 */
object CodebaseGraph {

    data class Node(
        val relativePath: String,
        val exportedSymbols: Set<String>,   // classes/funs/widgets defined in this file
        val importedSymbols: Set<String>,   // last segment of each import statement
        val referencedSymbols: Set<String>  // symbols referenced in the body (all identifiers ≥4 chars)
    )

    data class GraphData(
        val nodes: Map<String, Node>,                     // relativePath → Node
        val symbolDefinedIn: Map<String, String>,         // "ClassName" → "path/to/File.kt"
        val symbolUsedIn: Map<String, List<String>>       // "ClassName" → [files that reference it]
    )

    // LRU cache per project
    private val cache = object : LinkedHashMap<String, GraphData>(4, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, GraphData>) = size > 5
    }

    private val IMPORT_KT   = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
    private val IMPORT_JAVA  = Regex("""^import\s+([\w.]+);""", RegexOption.MULTILINE)
    private val IMPORT_DART  = Regex("""^import\s+'[^']*?([a-z_]+\.dart)'""", RegexOption.MULTILINE)
    private val IDENT        = Regex("""\b([A-Z][a-zA-Z0-9]{2,})\b""")

    // Common Pascal-case words that are NOT useful for cross-file graph linking
    private val IDENT_STOP = setOf(
        "String","Int","Long","Double","Float","Boolean","Unit","Any","Nothing",
        "List","Map","Set","Pair","Triple","Array","Collection","Sequence",
        "View","Text","Button","Column","Row","Box","Spacer","Modifier","Image","Icon","Card",
        "Widget","State","Build","Context","Result","Error","Exception","Throwable",
        "Companion","Object","Data","Null","True","False","This","Super","Init","Override",
        "Log","Tag","Runnable","Thread","Handler","Bundle","Intent","Activity","Fragment",
        "ViewModel","Repository","UseCase","Mapper","Dto","Response","Request","Model",
        "Future","Stream","Completer","Duration","DateTime","Timer",
        "BuildContext","StatefulWidget","StatelessWidget","Key","GlobalKey"
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    fun build(project: Project): GraphData {
        val base = project.basePath ?: return empty()
        return synchronized(cache) { cache[base] } ?: run {
            val entries = ProjectIndexer.index(project)
            val data = buildGraph(entries)
            synchronized(cache) { cache[base] = data }
            data
        }
    }

    fun invalidate(project: Project) {
        synchronized(cache) { cache.remove(project.basePath) }
    }

    /**
     * Returns the top-N most contextually relevant files using graph traversal.
     * Step 1: keyword score (from ProjectIndexer)
     * Step 2: for each symbol mentioned in the query, pull its definition file + callers
     * Step 3: deduplicate and rank by combined graph+keyword score
     */
    fun findRelevantWithGraph(project: Project, query: String, topN: Int = 7): List<ProjectIndexer.FileEntry> {
        val graph = build(project)
        val indexEntries = ProjectIndexer.index(project).associateBy { it.relativePath }
        if (indexEntries.isEmpty()) return emptyList()

        val scoreMap = mutableMapOf<String, Int>()

        // Keyword scoring — uses concept-expanded words for better recall
        val baseWords = tokenize(query)
        val words = ProjectIndexer.expandWithConcepts(baseWords)
        indexEntries.values.forEach { entry ->
            var s = 0
            val pathLow = entry.relativePath.lowercase()
            val fileName = pathLow.substringAfterLast("/")
            words.forEach { w ->
                val isBase = w in baseWords
                if (fileName.contains(w)) s += if (isBase) 10 else 4
                else if (pathLow.contains(w)) s += if (isBase) 4 else 2
            }
            val symsLow = entry.symbols.map { it.lowercase() }
            words.forEach { w ->
                val isBase = w in baseWords
                symsLow.forEach { sym ->
                    if (sym == w) s += if (isBase) 12 else 5
                    else if (sym.contains(w)) s += if (isBase) 5 else 2
                }
            }
            val cLow = entry.content.lowercase()
            words.forEach { w -> if (cLow.contains(w)) s += if (w in baseWords) 2 else 1 }
            if (s > 0) scoreMap[entry.relativePath] = (scoreMap[entry.relativePath] ?: 0) + s
        }

        // Graph scoring: for each symbol mentioned in the query
        IDENT.findAll(query).map { it.groupValues[1] }.forEach { sym ->
            // Definition file gets a boost
            graph.symbolDefinedIn[sym]?.let { defPath ->
                scoreMap[defPath] = (scoreMap[defPath] ?: 0) + 20
            }
            // Files that use this symbol get a smaller boost
            graph.symbolUsedIn[sym]?.forEach { usagePath ->
                scoreMap[usagePath] = (scoreMap[usagePath] ?: 0) + 8
            }
        }

        // Two-level dependency traversal:
        // Level 1 — what do the top-scoring files import?
        val level1Paths = scoreMap.entries.sortedByDescending { it.value }.take(4)
            .mapNotNull { (path, _) -> graph.nodes[path] }
        level1Paths.forEach { node ->
            node.importedSymbols.forEach { sym ->
                graph.symbolDefinedIn[sym]?.let { depPath ->
                    scoreMap[depPath] = (scoreMap[depPath] ?: 0) + 6
                }
            }
        }
        // Level 2 — what do those level-1 dependencies import?
        level1Paths.flatMap { node -> node.importedSymbols }
            .mapNotNull { graph.symbolDefinedIn[it] }
            .distinct()
            .mapNotNull { graph.nodes[it] }
            .forEach { depNode ->
                depNode.importedSymbols.forEach { sym ->
                    graph.symbolDefinedIn[sym]?.let { dep2Path ->
                        scoreMap[dep2Path] = (scoreMap[dep2Path] ?: 0) + 3
                    }
                }
            }

        return scoreMap.entries
            .sortedByDescending { it.value }
            .take(topN)
            .mapNotNull { (path, _) -> indexEntries[path] }
    }

    /**
     * Returns a formatted context string of files relevant to the query,
     * annotated with why each file was included (defined / used by / dependency of).
     */
    fun getGraphContext(project: Project, query: String): String {
        val graph = build(project)
        val relevant = findRelevantWithGraph(project, query, topN = 6)
        if (relevant.isEmpty()) return ""

        return buildString {
            append("RELEVANT CODEBASE FILES (with cross-file graph):\n")
            relevant.forEach { entry ->
                val node = graph.nodes[entry.relativePath]
                val reason = buildReason(graph, node, query)
                append("\n--- ${entry.relativePath}")
                if (reason.isNotBlank()) append(" ($reason)")
                append(" ---\n")
                append("```${entry.relativePath.substringAfterLast('.').ifBlank { "kotlin" }}\n")
                append(entry.content.take(3000))
                append("\n```\n")
            }
        }
    }

    // ─── Graph construction ───────────────────────────────────────────────────

    private fun buildGraph(entries: List<ProjectIndexer.FileEntry>): GraphData {
        val nodes = mutableMapOf<String, Node>()
        val symbolDefinedIn = mutableMapOf<String, String>()
        val symbolUsedIn = mutableMapOf<String, MutableList<String>>()

        entries.forEach { entry ->
            val ext = entry.relativePath.substringAfterLast(".")
            val imported = parseImports(entry.content, ext)
            val exported = entry.symbols.toSet()
            val referenced = IDENT.findAll(entry.content)
                .map { it.groupValues[1] }
                .filter { it !in exported && it !in IDENT_STOP }
                .toSet()

            nodes[entry.relativePath] = Node(entry.relativePath, exported, imported, referenced)
            exported.forEach { sym -> symbolDefinedIn[sym] = entry.relativePath }
        }

        // Build symbolUsedIn after all definitions are known
        nodes.values.forEach { node ->
            node.referencedSymbols.forEach { sym ->
                if (sym in symbolDefinedIn) {
                    symbolUsedIn.getOrPut(sym) { mutableListOf() }.add(node.relativePath)
                }
            }
        }

        return GraphData(nodes, symbolDefinedIn, symbolUsedIn)
    }

    private fun parseImports(content: String, ext: String): Set<String> =
        when (ext) {
            "kt", "kts" -> IMPORT_KT.findAll(content).map { it.groupValues[1].substringAfterLast(".") }.toSet()
            "java"       -> IMPORT_JAVA.findAll(content).map { it.groupValues[1].substringAfterLast(".") }.toSet()
            "dart"       -> IMPORT_DART.findAll(content).map { it.groupValues[1].removeSuffix(".dart") }.toSet()
            else         -> emptySet()
        }

    private fun buildReason(graph: GraphData, node: Node?, query: String): String {
        node ?: return ""
        val reasons = mutableListOf<String>()
        IDENT.findAll(query).map { it.groupValues[1] }.forEach { sym ->
            if (sym in node.exportedSymbols) reasons.add("defines $sym")
            if (sym in node.importedSymbols || sym in node.referencedSymbols) reasons.add("uses $sym")
        }
        return reasons.distinct().take(2).joinToString(", ")
    }

    private val GRAPH_STOP = setOf(
        "the","and","for","with","this","that","from","have","has","use","used","new",
        "get","set","val","var","fun","class","def","null","true","false","void",
        "override","return","import","package","private","public","protected","internal"
    )

    private fun tokenize(text: String): Set<String> =
        text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-zA-Z0-9_]+"))
            .filter { it.length > 2 && it !in GRAPH_STOP }
            .toSet()

    private fun empty() = GraphData(emptyMap(), emptyMap(), emptyMap())
}
