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

    /**
     * Run a single AI-provided search query and return results.
     * Called by AiService with queries that Kimi explicitly requested — no rules about when to search.
     */
    fun search(query: String): String {
        if (query.isBlank()) return ""
        val sb = StringBuilder()

        // Instant answer first (fast, no rate-limit)
        instantAnswer(query)?.let { sb.append("$it\n\n") }

        // General web search for the AI's exact query
        liteSearchAndFetch(query, maxResults = 2)?.let { sb.append(it) }

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

    /**
     * Searches DDG Lite for the given query and fetches the first real result.
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
