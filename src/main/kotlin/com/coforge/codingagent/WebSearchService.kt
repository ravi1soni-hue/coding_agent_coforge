package com.coforge.codingagent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Web search and documentation fetcher — fully open-ended.
 *
 * NO hardcoded library whitelists. Any package, any topic, any query.
 *
 * Tier 1: DuckDuckGo Instant Answer API  — official, stable
 * Tier 2: Direct doc fetch (pub.dev, kotlinlang.org, developer.android.com, flutter.dev)
 *         - Known topics: direct URL fetch
 *         - Unknown topics: site:-scoped DuckDuckGo Lite search → fetch top result
 * Tier 3: DuckDuckGo Lite general search — catches everything else
 */
object WebSearchService {
    private val LOG = Logger.getInstance(WebSearchService::class.java)

    private const val DDG_RATE_LIMIT_MS = 3000L
    private val lastDdgRequestMs = AtomicLong(0L)

    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
            .also { c -> Runtime.getRuntime().addShutdownHook(Thread {
                try { c.javaClass.getMethod("close").invoke(c) } catch (_: Exception) { }
            }) }
    }

    // Thread-safe user agent rotation
    private val uaIndexAtomic = java.util.concurrent.atomic.AtomicInteger(0)

    private val userAgents = listOf(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
    private fun nextUserAgent() = userAgents[uaIndexAtomic.getAndIncrement() % userAgents.size]

    // ─── Main entry point ─────────────────────────────────────────────────────

    fun fetchContextIfNeeded(
        userMessage: String,
        projectType: ProjectTypeDetector.ProjectType
    ): String {
        if (!shouldSearch(userMessage)) return ""

        val sb = StringBuilder()

        // Tier 1: Official DDG Instant Answer API
        instantAnswer(userMessage)?.let { sb.append("$it\n\n") }

        // Tier 2: Direct documentation (open-ended — works for any topic)
        directDocFetch(userMessage, projectType)?.let { sb.append("$it\n\n") }

        // Tier 3: DDG Lite general search (always fires if tiers 1+2 returned little)
        if (sb.length < 500) {
            val query = buildSearchQuery(userMessage, projectType)
            liteSearchAndFetch(query, maxResults = 2)?.let { sb.append(it) }
        }

        return sb.toString().trim()
    }

    // ─── Tier 1: Official DuckDuckGo Instant Answer API ──────────────────────

    fun instantAnswer(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val body = fetch(url, isDdgLite = false) ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            val result = StringBuilder()
            json.getStr("AbstractText")?.takeIf { it.length > 30 }?.let { result.append("Summary: $it\n") }
            json.getStr("Answer")?.takeIf { it.isNotBlank() }?.let { result.append("Answer: $it\n") }
            json.getStr("Definition")?.takeIf { it.isNotBlank() }?.let { result.append("Definition: $it\n") }
            json.getAsJsonArray("RelatedTopics")?.take(3)?.forEach { el ->
                el.asJsonObject.getStr("Text")?.takeIf { it.length > 20 }?.let { result.append("• $it\n") }
            }
            result.toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            LOG.debug("DDG Instant Answer: ${e.message}")
            null
        }
    }

    // ─── Tier 2: Direct documentation — open-ended ───────────────────────────
    //
    // For known topic keywords → direct URL fetch (fast, authoritative)
    // For UNKNOWN topics       → site:-scoped DDG search → fetch top result
    // This means ANY library, ANY topic is covered — no whitelist needed.

    fun directDocFetch(query: String, projectType: ProjectTypeDetector.ProjectType): String? {
        val lower = query.lowercase()
        val results = mutableListOf<String>()

        // ── pub.dev: any snake_case word is a candidate package name ──────────
        if (projectType == ProjectTypeDetector.ProjectType.FLUTTER || lower.contains("flutter") || lower.contains("dart")) {
            extractAnyPackageName(lower)?.let { pkg ->
                try {
                    val result = UrlContentFetcher.fetch("https://pub.dev/packages/$pkg")
                    if (result.content.length > 50) results.add("pub.dev / $pkg:\n${result.content.take(3000)}")
                } catch (_: Exception) {
                    // Package not found — fall through to general search
                }
            }
        }

        // ── Kotlin docs ───────────────────────────────────────────────────────
        if (lower.contains("kotlin") || lower.contains("coroutine") || lower.contains("flow") ||
            lower.contains("suspend") || lower.contains("kmp") || lower.contains("multiplatform")) {
            val topic = mapKotlinTopic(lower)
            if (topic != null) {
                // Known topic — fetch directly
                fetch("https://kotlinlang.org/docs/$topic.html", isDdgLite = false)
                    ?.let { cleanDocHtml(it) }?.takeIf { it.length > 100 }
                    ?.let { results.add("Kotlin docs:\n${it.take(2000)}") }
            } else {
                // Unknown topic — search within kotlinlang.org
                fetchTopResultFor("site:kotlinlang.org $lower")
                    ?.let { results.add("Kotlin docs:\n${it.take(2000)}") }
            }
        }

        // ── Android developer docs ────────────────────────────────────────────
        if (projectType == ProjectTypeDetector.ProjectType.ANDROID_NATIVE ||
            lower.contains("android") || lower.contains("compose") || lower.contains("jetpack")) {
            val path = mapAndroidPath(lower)
            if (path != null) {
                fetch("https://developer.android.com/$path", isDdgLite = false)
                    ?.let { cleanDocHtml(it) }?.takeIf { it.length > 100 }
                    ?.let { results.add("Android docs:\n${it.take(2000)}") }
            } else {
                // Unknown topic — search within developer.android.com
                fetchTopResultFor("site:developer.android.com $lower")
                    ?.let { results.add("Android docs:\n${it.take(2000)}") }
            }
        }

        // ── Flutter docs ──────────────────────────────────────────────────────
        if (lower.contains("flutter")) {
            val path = mapFlutterPath(lower)
            if (path != null) {
                fetch("https://docs.flutter.dev/$path", isDdgLite = false)
                    ?.let { cleanDocHtml(it) }?.takeIf { it.length > 100 }
                    ?.let { results.add("Flutter docs:\n${it.take(2000)}") }
            } else {
                // Unknown topic — search within docs.flutter.dev
                fetchTopResultFor("site:docs.flutter.dev $lower")
                    ?.let { results.add("Flutter docs:\n${it.take(2000)}") }
            }
        }

        return results.joinToString("\n\n---\n\n").takeIf { it.isNotBlank() }
    }

    /**
     * Searches DDG Lite with a site: query and fetches the first real result.
     * Used when we don't have a hard-coded path for the topic asked.
     */
    private fun fetchTopResultFor(siteQuery: String): String? {
        val urls = liteSearchUrls(siteQuery, maxResults = 1) ?: return null
        val url = urls.firstOrNull() ?: return null
        return try {
            UrlContentFetcher.fetch(url).content.takeIf { it.length > 100 }
        } catch (_: Exception) { null }
    }

    // ─── Tier 3: DuckDuckGo Lite general search ───────────────────────────────

    fun liteSearchAndFetch(query: String, maxResults: Int = 3): String? {
        val urls = liteSearchUrls(query, maxResults) ?: return null
        val fetched = urls.mapNotNull { url ->
            try {
                val result = UrlContentFetcher.fetch(url)
                if (result.content.isBlank()) null
                else "### ${result.title.ifBlank { url }}\n${result.content.take(2000)}"
            } catch (e: Exception) {
                LOG.debug("Fetch failed $url: ${e.message}")
                null
            }
        }
        return if (fetched.isEmpty()) null
               else "Web search results:\n\n${fetched.joinToString("\n\n---\n\n")}"
    }

    fun liteSearchUrls(query: String, maxResults: Int = 5): List<String>? {
        val now = System.currentTimeMillis()
        val elapsed = now - lastDdgRequestMs.get()
        if (elapsed < DDG_RATE_LIMIT_MS) Thread.sleep(DDG_RATE_LIMIT_MS - elapsed)

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = fetch("https://lite.duckduckgo.com/lite/?q=$encoded&kl=us-en", isDdgLite = true) ?: return null
            lastDdgRequestMs.set(System.currentTimeMillis())
            parseLiteUrls(html, maxResults)
        } catch (e: Exception) {
            LOG.debug("DDG Lite error: ${e.message}")
            try {
                Thread.sleep(2000)
                val encoded = URLEncoder.encode(query, "UTF-8")
                val html = fetch("https://lite.duckduckgo.com/lite/?q=$encoded", isDdgLite = true) ?: return null
                lastDdgRequestMs.set(System.currentTimeMillis())
                parseLiteUrls(html, maxResults)
            } catch (_: Exception) { null }
        }
    }

    private fun parseLiteUrls(html: String, max: Int): List<String> =
        Regex("""href="//duckduckgo\.com/l/\?uddg=([^&"]+)""")
            .findAll(html)
            .mapNotNull { m ->
                try {
                    URLDecoder.decode(m.groupValues[1], "UTF-8")
                        .takeIf { it.startsWith("http") && !it.contains("duckduckgo.com") }
                } catch (_: Exception) { null }
            }
            .distinct().take(max).toList()

    // ─── shouldSearch — open-ended, no fixed library list ────────────────────
    //
    // Searches when the query is about something that could have changed since
    // the model's training cutoff or needs current documentation.
    // No hardcoded library names — any library triggers search.

    fun shouldSearch(message: String): Boolean {
        val lower = message.lowercase()

        // Explicit doc requests
        if (Regex("""\b(search|documentation|docs|official|changelog|release|migration|upgrade|version)\b""")
                .containsMatchIn(lower)) return true

        // Any version number mentioned
        if (Regex("""\d+\.\d+""").containsMatchIn(lower)) return true

        // Any snake_case identifier = almost certainly a library/package name
        // e.g. go_router, flutter_riverpod, retrofit2, moko_mvvm, patrol_finders
        if (Regex("""\b[a-z][a-z0-9]*_[a-z][a-z0-9_]*\b""").containsMatchIn(lower)) return true

        // Implementation-type questions about specific tech
        if (Regex("""\b(how (do|to|can)|implement|integrate|setup|configure|install|add|use)\b""")
                .containsMatchIn(lower) &&
            Regex("""\b(library|plugin|package|sdk|api|framework|gradle|pubspec|dependency|module)\b""")
                .containsMatchIn(lower)) return true

        // Long task descriptions (Jira/requirements paste)
        if (message.trim().length > 300) return true

        return false
    }

    fun buildSearchQuery(message: String, projectType: ProjectTypeDetector.ProjectType): String {
        val platform = when (projectType) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Android Kotlin"
        }
        val cleaned = message
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), " ")
            .trim().take(120)
        return "$platform $cleaned"
    }

    // ─── Package name extraction — open-ended ─────────────────────────────────
    //
    // Extracts ANY snake_case word from the query as a pub.dev package candidate.
    // No whitelist — works for new packages released after this code was written.

    private fun extractAnyPackageName(lower: String): String? {
        // Words that look like package names but are common Dart/Flutter code patterns
        val falsePositives = setOf(
            "build_context", "build_runner", "build_gradle", "on_pressed", "on_tap",
            "on_changed", "text_style", "box_decoration", "edge_insets", "font_weight",
            "font_size", "border_radius", "on_create", "on_resume", "on_pause",
            "on_destroy", "view_model", "data_binding", "view_binding", "use_state",
            "use_effect", "on_submit", "on_save", "key_value", "content_type"
        )

        return Regex("""\b([a-z][a-z0-9]*(?:_[a-z0-9]+){1,5})\b""")
            .findAll(lower)
            .map { it.groupValues[1] }
            .filter { it.length >= 4 && it !in falsePositives }
            .firstOrNull()
    }

    // ─── Known topic maps (direct URL routes) ─────────────────────────────────
    // These cover the COMMON cases for speed. Unknown topics fall through to
    // site:-scoped DDG search above — so nothing is missed.

    private fun mapKotlinTopic(lower: String): String? = when {
        lower.contains("coroutine") -> "coroutines-overview"
        lower.contains("stateflow") || (lower.contains("flow") && lower.contains("state")) -> "stateflow-and-sharedflow"
        lower.contains("sharedflow") -> "stateflow-and-sharedflow"
        lower.contains("flow") -> "flow"
        lower.contains("channel") -> "channels"
        lower.contains("suspend") -> "composing-suspending-functions"
        lower.contains("sealed") -> "sealed-classes"
        lower.contains("extension fun") || lower.contains("extension function") -> "extensions"
        lower.contains("inline") && lower.contains("fun") -> "inline-functions"
        lower.contains("delegation") || lower.contains("delegate") -> "delegation"
        lower.contains("companion") -> "object-declarations"
        lower.contains("data class") -> "data-classes"
        lower.contains("enum") -> "enum-classes"
        lower.contains("generics") -> "generics"
        lower.contains("lambda") -> "lambdas"
        lower.contains("scope function") || lower.contains("let(") || lower.contains(".run(") || lower.contains(".apply(") -> "scope-functions"
        else -> null  // → falls through to site:kotlinlang.org search
    }

    private fun mapAndroidPath(lower: String): String? = when {
        lower.contains("compose") && lower.contains("navigation") -> "jetpack/compose/navigation"
        lower.contains("compose") && lower.contains("state") -> "jetpack/compose/state"
        lower.contains("compose") && lower.contains("layout") -> "jetpack/compose/layouts/basics"
        lower.contains("compose") && lower.contains("animation") -> "jetpack/compose/animation/overview"
        lower.contains("compose") && lower.contains("test") -> "jetpack/compose/testing"
        lower.contains("compose") && lower.contains("side effect") -> "jetpack/compose/side-effects"
        lower.contains("compose") && lower.contains("theming") -> "jetpack/compose/designsystems/material3"
        lower.contains("compose") -> "jetpack/compose/documentation"
        lower.contains("viewmodel") -> "topic/libraries/architecture/viewmodel"
        lower.contains("datastore") -> "topic/libraries/architecture/datastore"
        lower.contains("workmanager") -> "topic/libraries/architecture/workmanager"
        lower.contains("paging") -> "topic/libraries/architecture/paging/v3-overview"
        lower.contains("hilt") -> "training/dependency-injection/hilt-android"
        lower.contains("room") -> "training/data-storage/room"
        lower.contains("lifecycle") -> "topic/libraries/architecture/lifecycle"
        lower.contains("navigation") -> "guide/navigation"
        lower.contains("biometric") -> "training/sign-in/biometric-auth"
        lower.contains("media3") || lower.contains("exoplayer") -> "guide/topics/media/exoplayer"
        lower.contains("camerax") -> "training/camerax"
        lower.contains("permission") -> "training/permissions"
        else -> null  // → falls through to site:developer.android.com search
    }

    private fun mapFlutterPath(lower: String): String? = when {
        lower.contains("navigation") || lower.contains("routing") -> "ui/navigation"
        lower.contains("state management") || lower.contains("state mgmt") -> "data-and-backend/state-mgmt/intro"
        lower.contains("animation") -> "ui/animations"
        lower.contains("test") -> "testing/overview"
        lower.contains("platform channel") -> "platform-integration/platform-channels"
        lower.contains("widget") -> "ui/widgets/basics"
        lower.contains("accessibility") -> "ui/accessibility"
        lower.contains("localization") || lower.contains("l10n") -> "ui/accessibility/internationalization"
        lower.contains("async") || lower.contains("future") || lower.contains("stream") -> "cookbook/networking/background-parsing"
        lower.contains("network") || lower.contains("http") -> "cookbook/networking/fetch-data"
        lower.contains("json") -> "data-and-backend/serialization/json"
        lower.contains("firebase") -> "data-and-backend/firebase"
        lower.contains("flavor") -> "deployment/flavors"
        else -> null  // → falls through to site:docs.flutter.dev search
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private fun fetch(url: String, isDdgLite: Boolean): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", nextUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.9")
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply { if (isDdgLite) header("Referer", "https://lite.duckduckgo.com/") }
                .GET()
                .timeout(Duration.ofSeconds(12))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                in 200..299 -> response.body()
                429 -> { LOG.warn("Rate limited: $url"); null }
                else -> { LOG.debug("HTTP ${response.statusCode()}: $url"); null }
            }
        } catch (e: Exception) {
            LOG.debug("Fetch error $url: ${e.message}")
            null
        }
    }

    private fun cleanDocHtml(html: String): String =
        html.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<header[\\s\\S]*?</header>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
            .replace(Regex("<h[1-4][^>]*>", RegexOption.IGNORE_CASE), "\n## ")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&amp;"), "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()

    private fun JsonObject.getStr(key: String) = try { get(key)?.asString } catch (_: Exception) { null }
}
