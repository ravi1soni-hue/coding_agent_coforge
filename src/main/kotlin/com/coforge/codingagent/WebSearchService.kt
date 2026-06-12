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
 * Web search and documentation fetcher.
 *
 * Based on research findings:
 *
 * 1. DuckDuckGo Instant Answer API (api.duckduckgo.com/?format=json)
 *    - OFFICIAL, stable, no rate limits documented
 *    - Only returns results for well-known topics (Wikipedia abstracts, direct answers)
 *    - Returns empty for most developer library queries
 *    - Used as Tier 1 (fast, reliable when it works)
 *
 * 2. Direct documentation fetching
 *    - pub.dev, developer.android.com, kotlinlang.org, flutter.dev all respond well
 *    - Most reliable and accurate for developer queries
 *    - Used as Tier 2 (targeted, authoritative)
 *
 * 3. DuckDuckGo Lite HTML (lite.duckduckgo.com/lite/)
 *    - No official API — HTML scraping only
 *    - Rate limits are real (Python duckduckgo-search library warns about this)
 *    - URL format confirmed: href="//duckduckgo.com/l/?uddg=ENCODED_URL&rut=HASH"
 *    - Used as Tier 3 (general fallback, with rate limiting + retry)
 */
object WebSearchService {
    private val LOG = Logger.getInstance(WebSearchService::class.java)

    // Minimum milliseconds between DuckDuckGo Lite requests to avoid rate limiting
    private const val DDG_RATE_LIMIT_MS = 3000L
    private val lastDdgRequestMs = AtomicLong(0L)

    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }

    // User agents to rotate — reduces chance of being blocked
    private val userAgents = listOf(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
    private var uaIndex = 0
    private fun nextUserAgent() = userAgents[uaIndex++ % userAgents.size]

    data class SearchResult(val title: String, val url: String, val snippet: String = "")

    // ─── Main entry point ─────────────────────────────────────────────────────

    /**
     * Decides whether to search, then runs the tier waterfall.
     * Returns formatted context string ready to inject into the AI prompt.
     */
    fun fetchContextIfNeeded(
        userMessage: String,
        projectType: ProjectTypeDetector.ProjectType
    ): String {
        if (!shouldSearch(userMessage)) return ""

        val sb = StringBuilder()

        // Tier 1: Official DDG Instant Answer API (fast check)
        instantAnswer(userMessage)?.let { sb.append("$it\n\n") }

        // Tier 2: Smart direct documentation (most reliable)
        directDocFetch(userMessage, projectType)?.let { sb.append("$it\n\n") }

        // Tier 3: DDG Lite general search (only if tiers 1+2 returned little)
        if (sb.length < 500) {
            val query = buildSearchQuery(userMessage, projectType)
            liteSearchAndFetch(query, maxResults = 2)?.let { sb.append(it) }
        }

        return sb.toString().trim()
    }

    // ─── Tier 1: Official DuckDuckGo Instant Answer API ──────────────────────
    // Documented at: https://duckduckgo.com/api
    // Returns: AbstractText, Answer, Definition, RelatedTopics
    // Reliable for well-known entities; empty for niche library queries.

    fun instantAnswer(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val html = fetch(url, isDdgLite = false) ?: return null

            val json = JsonParser.parseString(html).asJsonObject
            val result = StringBuilder()

            json.getStr("AbstractText")?.takeIf { it.length > 30 }
                ?.let { result.append("Summary: $it\n") }
            json.getStr("Answer")?.takeIf { it.isNotBlank() }
                ?.let { result.append("Answer: $it\n") }
            json.getStr("Definition")?.takeIf { it.isNotBlank() }
                ?.let { result.append("Definition: $it\n") }

            // RelatedTopics give us snippets even when main answer is empty
            json.getAsJsonArray("RelatedTopics")?.take(3)?.forEach { el ->
                val topic = el.asJsonObject
                topic.getStr("Text")?.takeIf { it.length > 20 }
                    ?.let { result.append("• $it\n") }
            }

            result.toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            LOG.debug("DDG Instant Answer error: ${e.message}")
            null
        }
    }

    // ─── Tier 2: Smart direct documentation fetch ─────────────────────────────
    // Routes to the right documentation source based on what the query is about.
    // These sites are stable, load correctly, and return authoritative content.

    fun directDocFetch(query: String, projectType: ProjectTypeDetector.ProjectType): String? {
        val lower = query.lowercase()
        val results = mutableListOf<String>()

        // pub.dev: Flutter/Dart packages — use UrlContentFetcher which already knows the pub.dev format
        if (projectType == ProjectTypeDetector.ProjectType.FLUTTER || lower.contains("flutter") || lower.contains("dart")) {
            extractPackageName(lower)?.let { pkg ->
                try {
                    val result = UrlContentFetcher.fetch("https://pub.dev/packages/$pkg")
                    if (result.content.length > 50) results.add("pub.dev / $pkg:\n${result.content.take(3000)}")
                } catch (_: Exception) {}
            }
        }

        // Kotlin docs: kotlinlang.org
        if (lower.contains("kotlin") || lower.contains("coroutine") || lower.contains("flow") ||
            lower.contains("suspend") || lower.contains("channel") || lower.contains("stateflow")) {
            val topic = extractKotlinDocTopic(lower)
            if (topic != null) {
                fetch("https://kotlinlang.org/docs/$topic.html", isDdgLite = false)
                    ?.let { cleanDocHtml(it) }
                    ?.takeIf { it.length > 100 }
                    ?.let { results.add("Kotlin docs ($topic):\n${it.take(2000)}") }
            }
        }

        // Android developer docs
        if (projectType == ProjectTypeDetector.ProjectType.ANDROID_NATIVE ||
            lower.contains("android") || lower.contains("compose") || lower.contains("jetpack")) {
            val topic = extractAndroidDocPath(lower)
            if (topic != null) {
                fetch("https://developer.android.com/$topic", isDdgLite = false)
                    ?.let { cleanDocHtml(it) }
                    ?.takeIf { it.length > 100 }
                    ?.let { results.add("Android docs:\n${it.take(2000)}") }
            }
        }

        // Flutter docs: flutter.dev
        if (lower.contains("flutter") && (lower.contains("widget") || lower.contains("navigation") ||
            lower.contains("state") || lower.contains("animation") || lower.contains("testing"))) {
            val topic = extractFlutterDocPath(lower)
            if (topic != null) {
                fetch("https://docs.flutter.dev/$topic", isDdgLite = false)
                    ?.let { cleanDocHtml(it) }
                    ?.takeIf { it.length > 100 }
                    ?.let { results.add("Flutter docs:\n${it.take(2000)}") }
            }
        }

        return results.joinToString("\n\n---\n\n").takeIf { it.isNotBlank() }
    }

    // ─── Tier 3: DuckDuckGo Lite HTML scraping ────────────────────────────────
    // lite.duckduckgo.com/lite/ is simpler HTML than the full page.
    // Confirmed URL format: href="//duckduckgo.com/l/?uddg=ENCODED_URL&rut=HASH"
    // Rate limited — enforced by lastDdgRequestMs throttle + retry logic.

    fun liteSearchAndFetch(query: String, maxResults: Int = 3): String? {
        val urls = liteSearchUrls(query, maxResults) ?: return null
        val fetched = urls.mapNotNull { url ->
            try {
                val result = UrlContentFetcher.fetch(url)
                if (result.content.isBlank()) null
                else "### ${result.title.ifBlank { url }}\n${result.content.take(2000)}"
            } catch (e: Exception) {
                LOG.debug("Failed to fetch $url: ${e.message}")
                null
            }
        }
        if (fetched.isEmpty()) return null
        return "Web search results:\n\n${fetched.joinToString("\n\n---\n\n")}"
    }

    fun liteSearchUrls(query: String, maxResults: Int = 5): List<String>? {
        // Respect rate limit
        val now = System.currentTimeMillis()
        val elapsed = now - lastDdgRequestMs.get()
        if (elapsed < DDG_RATE_LIMIT_MS) {
            Thread.sleep(DDG_RATE_LIMIT_MS - elapsed)
        }

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://lite.duckduckgo.com/lite/?q=$encoded&kl=us-en"
            val html = fetch(url, isDdgLite = true) ?: return null
            lastDdgRequestMs.set(System.currentTimeMillis())
            parseLiteUrls(html, maxResults)
        } catch (e: Exception) {
            LOG.debug("DDG Lite search error: ${e.message}")
            // Retry once after 2s on error
            try {
                Thread.sleep(2000)
                val encoded = URLEncoder.encode(query, "UTF-8")
                val html = fetch("https://lite.duckduckgo.com/lite/?q=$encoded", isDdgLite = true) ?: return null
                lastDdgRequestMs.set(System.currentTimeMillis())
                parseLiteUrls(html, maxResults)
            } catch (_: Exception) { null }
        }
    }

    /**
     * Parses DuckDuckGo Lite HTML.
     * Confirmed URL format (from research): href="//duckduckgo.com/l/?uddg=ENCODED_URL&rut=HASH"
     */
    private fun parseLiteUrls(html: String, max: Int): List<String> {
        // Extract uddg= encoded URLs from DDG redirect links
        return Regex("""href="//duckduckgo\.com/l/\?uddg=([^&"]+)""")
            .findAll(html)
            .mapNotNull { m ->
                try {
                    val decoded = URLDecoder.decode(m.groupValues[1], "UTF-8")
                    decoded.takeIf { it.startsWith("http") && !it.contains("duckduckgo.com") }
                } catch (_: Exception) { null }
            }
            .distinct()
            .take(max)
            .toList()
    }

    // ─── Should search? ───────────────────────────────────────────────────────

    fun shouldSearch(message: String): Boolean {
        val lower = message.lowercase()

        // Explicit requests
        if (lower.contains("search") || lower.contains("find documentation") ||
            lower.contains("official docs") || lower.contains("how to use")) return true
        if (lower.contains("latest version") || lower.contains("changelog") ||
            lower.contains("migration") || lower.contains("release notes")) return true

        // Known packages and libraries that change frequently
        val fastMovingLibs = setOf(
            "riverpod", "bloc", "cubit", "getx", "isar", "objectbox",
            "ktor", "serialization", "coil3", "datastore", "workmanager",
            "compose multiplatform", "kotlin multiplatform", "kmp", "kmm",
            "go_router", "auto_route", "get_it", "flutter_hooks", "signals",
            "mason", "drift", "floor", "realm", "supabase", "appwrite",
            "accompanist", "voyager", "decompose", "circuit", "turbine",
            "firebase", "crashlytics", "amplitude", "mixpanel",
            "dagger", "hilt", "anvil", "koin"
        )
        if (fastMovingLibs.any { lower.contains(it) }) return true

        // Version number = user is asking about something specific
        if (Regex("\\d+\\.\\d+\\.?\\d*").containsMatchIn(lower)) return true

        // Long descriptions (pasted requirements from Jira / task)
        if (message.length > 350) return true

        return false
    }

    fun buildSearchQuery(message: String, projectType: ProjectTypeDetector.ProjectType): String {
        val platform = when (projectType) {
            ProjectTypeDetector.ProjectType.FLUTTER -> "Flutter Dart"
            ProjectTypeDetector.ProjectType.ANDROID_NATIVE -> "Android Kotlin"
            ProjectTypeDetector.ProjectType.UNKNOWN -> "Android"
        }
        val cleaned = message.replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim().take(100)
        return "$platform $cleaned"
    }

    // ─── Documentation routing helpers ───────────────────────────────────────

    private fun extractPackageName(lower: String): String? {
        // Common Flutter packages — extend as needed
        val packages = listOf(
            "riverpod","flutter_riverpod","hooks_riverpod","provider","bloc",
            "flutter_bloc","go_router","auto_route","get_it","injectable",
            "dio","http","retrofit","chopper","hive","isar","objectbox",
            "realm","drift","floor","shared_preferences","sqflite",
            "firebase_core","firebase_auth","cloud_firestore","firebase_storage",
            "supabase_flutter","appwrite","amplify_flutter",
            "flutter_hooks","flutter_gen","freezed","json_serializable",
            "built_value","equatable","dartz","fpdart",
            "cached_network_image","flutter_svg","lottie","shimmer",
            "intl","timeago","path_provider","package_info_plus",
            "permission_handler","geolocator","google_maps_flutter",
            "image_picker","camera","video_player","just_audio",
            "local_auth","flutter_secure_storage","encrypt",
            "connectivity_plus","internet_connection_checker",
            "flutter_local_notifications","awesome_notifications",
            "in_app_purchase","purchases_flutter","adapty",
            "sentry_flutter","datadog_flutter_plugin",
            "mason","very_good_cli","flutter_gen","slang"
        )
        return packages.firstOrNull { lower.contains(it) }
    }

    private fun extractKotlinDocTopic(lower: String): String? = when {
        lower.contains("coroutine") -> "coroutines-overview"
        lower.contains("flow") && lower.contains("state") -> "stateflow-and-sharedflow"
        lower.contains("flow") -> "flow"
        lower.contains("channel") -> "channels"
        lower.contains("suspend") -> "composing-suspending-functions"
        lower.contains("sealed") -> "sealed-classes"
        lower.contains("extension") -> "extensions"
        lower.contains("inline") && lower.contains("fun") -> "inline-functions"
        lower.contains("delegation") || lower.contains("delegate") -> "delegation"
        lower.contains("companion") -> "object-declarations"
        else -> null
    }

    private fun extractAndroidDocPath(lower: String): String? = when {
        lower.contains("compose") && lower.contains("navigation") -> "jetpack/compose/navigation"
        lower.contains("compose") && lower.contains("state") -> "jetpack/compose/state"
        lower.contains("compose") && lower.contains("layout") -> "jetpack/compose/layouts/basics"
        lower.contains("compose") && lower.contains("animation") -> "jetpack/compose/animation/overview"
        lower.contains("compose") && lower.contains("test") -> "jetpack/compose/testing"
        lower.contains("compose") -> "jetpack/compose/documentation"
        lower.contains("viewmodel") -> "topic/libraries/architecture/viewmodel"
        lower.contains("datastore") -> "topic/libraries/architecture/datastore"
        lower.contains("workmanager") -> "topic/libraries/architecture/workmanager"
        lower.contains("paging") -> "topic/libraries/architecture/paging/v3-overview"
        lower.contains("hilt") -> "training/dependency-injection/hilt-android"
        lower.contains("room") -> "training/data-storage/room"
        lower.contains("lifecycle") -> "topic/libraries/architecture/lifecycle"
        else -> null
    }

    private fun extractFlutterDocPath(lower: String): String? = when {
        lower.contains("navigation") || lower.contains("routing") -> "ui/navigation"
        lower.contains("state") -> "data-and-backend/state-mgmt/intro"
        lower.contains("animation") -> "ui/animations"
        lower.contains("testing") || lower.contains("test") -> "testing/overview"
        lower.contains("platform channel") -> "platform-integration/platform-channels"
        lower.contains("widget") -> "ui/widgets/basics"
        else -> null
    }

    // ─── HTML fetch ───────────────────────────────────────────────────────────

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
                429 -> { LOG.warn("Rate limited by $url"); null }
                else -> { LOG.debug("HTTP ${response.statusCode()} for $url"); null }
            }
        } catch (e: Exception) {
            LOG.debug("Fetch error for $url: ${e.message}")
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

