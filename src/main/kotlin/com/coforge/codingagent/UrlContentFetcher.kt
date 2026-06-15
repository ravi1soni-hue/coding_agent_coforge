package com.coforge.codingagent

import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Smart URL content extractor.
 * Understands Jira tickets, GitHub issues/PRs/READMEs, Android docs,
 * Flutter docs, pub.dev packages, and arbitrary web pages.
 * Returns clean, structured text — no HTML noise.
 */
object UrlContentFetcher {
    private val LOG = Logger.getInstance(UrlContentFetcher::class.java)

    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }

    // TTL cache: URL -> (content, fetchTime). TTL = 5 minutes.
    private data class CacheEntry(val result: FetchResult, val fetchedAt: Long)
    private val urlCache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, CacheEntry>) = size > 50
    }
    private const val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    // Per-domain rate limiting: track last request time
    private val domainLastRequest = mutableMapOf<String, Long>()

    data class FetchResult(
        val url: String,
        val title: String,
        val content: String,
        val sourceType: SourceType
    ) {
        fun toContextString() = buildString {
            append("URL: $url\n")
            append("Source: ${sourceType.label}\n")
            if (title.isNotBlank()) append("Title: $title\n")
            append("Content:\n$content")
        }
    }

    enum class SourceType(val label: String) {
        JIRA("Jira Ticket"),
        GITHUB_ISSUE("GitHub Issue/PR"),
        GITHUB_CODE("GitHub File/README"),
        ANDROID_DOCS("Android Documentation"),
        FLUTTER_DOCS("Flutter Documentation"),
        PUB_DEV("pub.dev Package"),
        STACK_OVERFLOW("Stack Overflow"),
        GENERAL("Web Page")
    }

    /** Detects all URLs in text and fetches each, returning formatted context strings. */
    fun fetchAll(text: String, maxUrls: Int = 3): String {
        val urls = Regex("(https?://[^\\s\"'<>]+)").findAll(text)
            .map { it.value.trimEnd(')', ',', '.', '>', ']') }
            .distinct()
            .take(maxUrls)
            .toList()

        if (urls.isEmpty()) return ""

        return urls.mapNotNull { url ->
            try { fetch(url) } catch (e: Exception) {
                LOG.warn("Failed to fetch $url: ${e.message}")
                null
            }
        }.joinToString("\n\n---\n\n") { it.toContextString() }
    }

    fun fetch(url: String, timeoutSec: Long = 12): FetchResult {
        // Check TTL cache first
        synchronized(urlCache) {
            urlCache[url]?.let { entry ->
                if (System.currentTimeMillis() - entry.fetchedAt < CACHE_TTL_MS) return entry.result
            }
        }

        // Per-domain rate limit: max 1 request/second per domain
        val domain = try { URI.create(url).host ?: url } catch (_: Exception) { url }
        synchronized(domainLastRequest) {
            val last = domainLastRequest[domain] ?: 0L
            val sinceLastMs = System.currentTimeMillis() - last
            if (sinceLastMs < 1000) Thread.sleep(1000 - sinceLastMs)
            domainLastRequest[domain] = System.currentTimeMillis()
        }

        val type = detectType(url)
        val html = fetchRaw(url, timeoutSec)
            ?: return FetchResult(url, "Could not fetch", "Failed to retrieve content.", type)

        val title = extractTitle(html)
        val content = when (type) {
            SourceType.JIRA           -> extractJira(html)
            SourceType.GITHUB_ISSUE   -> extractGitHubIssue(html)
            SourceType.GITHUB_CODE    -> extractGitHubCode(html)
            SourceType.ANDROID_DOCS   -> extractDocPage(html)
            SourceType.FLUTTER_DOCS   -> extractDocPage(html)
            SourceType.PUB_DEV        -> extractPubDev(html)
            SourceType.STACK_OVERFLOW -> extractStackOverflow(html)
            SourceType.GENERAL        -> extractGeneral(html)
        }.take(8000).trim()

        val result = FetchResult(url, title, content, type)
        synchronized(urlCache) { urlCache[url] = CacheEntry(result, System.currentTimeMillis()) }
        return result
    }

    // ─── URL type detection ───────────────────────────────────────────────────

    private fun detectType(url: String): SourceType = when {
        url.contains("atlassian.net/browse") || url.contains("/jira/") || url.contains("jira.") -> SourceType.JIRA
        url.contains("github.com") && (url.contains("/issues/") || url.contains("/pull/"))        -> SourceType.GITHUB_ISSUE
        url.contains("github.com")                                                                 -> SourceType.GITHUB_CODE
        url.contains("developer.android.com")                                                      -> SourceType.ANDROID_DOCS
        url.contains("flutter.dev") || url.contains("api.flutter.dev") || url.contains("dart.dev")-> SourceType.FLUTTER_DOCS
        url.contains("pub.dev/packages")                                                           -> SourceType.PUB_DEV
        url.contains("stackoverflow.com")                                                          -> SourceType.STACK_OVERFLOW
        else                                                                                       -> SourceType.GENERAL
    }

    // ─── HTTP fetch ───────────────────────────────────────────────────────────

    private fun fetchRaw(url: String, timeoutSec: Long): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; CoforgeAI/1.0)")
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .GET()
                .timeout(Duration.ofSeconds(timeoutSec))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) response.body() else null
        } catch (e: Exception) { null }
    }

    // ─── Extractors ───────────────────────────────────────────────────────────

    private fun extractTitle(html: String): String =
        Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.decodeHtml()?.trim() ?: ""

    private fun extractJira(html: String): String {
        val sb = StringBuilder()
        // Issue summary/title
        listOf(
            Regex("<h1[^>]*id=\"summary-val\"[^>]*>([^<]+)<", RegexOption.IGNORE_CASE),
            Regex("<h1[^>]*class=\"[^\"]*issue-header[^\"]*\"[^>]*>([^<]+)<", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
            ?.let { sb.append("Summary: ${it.decodeHtml().trim()}\n\n") }

        // Description
        extractBetweenTags(html, "description-val")?.let { sb.append("Description:\n${cleanHtml(it)}\n\n") }
        extractBetweenTags(html, "issue-description")?.let { sb.append("Description:\n${cleanHtml(it)}\n\n") }

        // Acceptance criteria / comments
        Regex("Acceptance Criteria[\\s\\S]{0,3000}", RegexOption.IGNORE_CASE)
            .find(html)?.value?.let { sb.append("Acceptance Criteria:\n${cleanHtml(it.take(2000))}\n\n") }

        // Status, priority, assignee
        Regex("<span[^>]*id=\"status-val\"[^>]*>([^<]+)<").find(html)?.groupValues?.get(1)
            ?.let { sb.append("Status: ${it.decodeHtml().trim()}\n") }

        return if (sb.isNotEmpty()) sb.toString() else extractGeneral(html)
    }

    private fun extractGitHubIssue(html: String): String {
        val sb = StringBuilder()
        Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.let { sb.append("Title: ${it.decodeHtml().trim()}\n\n") }
        // Main issue body
        Regex("<div[^>]*class=\"[^\"]*comment-body[^\"]*\"[^>]*>([\\s\\S]{0,4000}?)</div>")
            .find(html)?.groupValues?.get(1)?.let { sb.append("Body:\n${cleanHtml(it)}\n\n") }
        // Labels
        Regex("<a[^>]*class=\"[^\"]*label[^\"]*\"[^>]*>([^<]+)</a>").findAll(html)
            .map { it.groupValues[1].decodeHtml() }.take(8).toList()
            .takeIf { it.isNotEmpty() }?.let { sb.append("Labels: ${it.joinToString(", ")}\n") }
        return if (sb.isNotEmpty()) sb.toString() else extractGeneral(html)
    }

    private fun extractGitHubCode(html: String): String {
        // README or raw file content
        val readme = Regex("<article[^>]*>([\\s\\S]{0,6000}?)</article>").find(html)
            ?.groupValues?.get(1)?.let { cleanHtml(it) }
        if (!readme.isNullOrBlank()) return readme

        // Code file
        val code = Regex("<table[^>]*data-hunk[^>]*>([\\s\\S]{0,4000}?)</table>").find(html)
            ?.groupValues?.get(1)?.let { cleanHtml(it) }
        if (!code.isNullOrBlank()) return code

        return extractGeneral(html)
    }

    private fun extractDocPage(html: String): String {
        // Try <main>, <article>, <div id="content">, <div class="devsite-article-body">
        val patterns = listOf(
            Regex("<main[^>]*>([\\s\\S]{0,8000}?)</main>", RegexOption.IGNORE_CASE),
            Regex("<article[^>]*>([\\s\\S]{0,8000}?)</article>", RegexOption.IGNORE_CASE),
            Regex("<div[^>]*id=\"content\"[^>]*>([\\s\\S]{0,8000}?)</div>", RegexOption.IGNORE_CASE),
            Regex("<div[^>]*class=\"[^\"]*article[^\"]*\"[^>]*>([\\s\\S]{0,8000}?)</div>", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
            ?.let { cleanHtml(it) }
            ?: extractGeneral(html)
    }

    private fun extractPubDev(html: String): String {
        val sb = StringBuilder()
        // Package name + description
        Regex("<h1[^>]*class=\"[^\"]*title[^\"]*\"[^>]*>([^<]+)<").find(html)
            ?.groupValues?.get(1)?.let { sb.append("Package: ${it.decodeHtml()}\n\n") }
        // README section
        Regex("<section[^>]*id=\"readme[^\"]*\"[^>]*>([\\s\\S]{0,5000}?)</section>").find(html)
            ?.groupValues?.get(1)?.let { sb.append("README:\n${cleanHtml(it)}\n\n") }
        // Latest version + pubspec snippet
        Regex("version:\\s*([\\d.]+)").find(html)
            ?.groupValues?.get(1)?.let { sb.append("Latest version: $it\n") }
        return if (sb.isNotEmpty()) sb.toString() else extractGeneral(html)
    }

    private fun extractStackOverflow(html: String): String {
        val sb = StringBuilder()
        // Question body
        Regex("<div[^>]*class=\"[^\"]*question[^\"]*\"[^>]*>[\\s\\S]*?<div[^>]*class=\"[^\"]*s-prose[^\"]*\"[^>]*>([\\s\\S]{0,3000}?)</div>")
            .find(html)?.groupValues?.get(1)?.let { sb.append("Question:\n${cleanHtml(it)}\n\n") }
        // Top answer
        Regex("<div[^>]*class=\"[^\"]*answer[^\"]*\"[^>]*>[\\s\\S]*?<div[^>]*class=\"[^\"]*s-prose[^\"]*\"[^>]*>([\\s\\S]{0,3000}?)</div>")
            .find(html)?.groupValues?.get(1)?.let { sb.append("Top Answer:\n${cleanHtml(it)}\n") }
        return if (sb.isNotEmpty()) sb.toString() else extractGeneral(html)
    }

    private fun extractGeneral(html: String): String {
        // Remove script, style, nav, footer, header blocks
        var clean = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<header[\\s\\S]*?</header>", RegexOption.IGNORE_CASE), " ")
        return cleanHtml(clean).take(8000)
    }

    // ─── HTML cleaning ────────────────────────────────────────────────────────

    private fun cleanHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
            .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n### ")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex(" {2,}"), " ")
            .decodeHtml()
            .trim()

    private fun extractBetweenTags(html: String, id: String): String? =
        Regex("<[^>]+id=\"$id\"[^>]*>([\\s\\S]{0,4000}?)</", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private fun String.decodeHtml() = this
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
}
