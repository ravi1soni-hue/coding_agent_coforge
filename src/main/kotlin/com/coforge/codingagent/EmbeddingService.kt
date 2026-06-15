package com.coforge.codingagent

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.sqrt

/**
 * Calls the Coforge embedding endpoint to get vector representations of text.
 * Used for hybrid BM25 + semantic search in ProjectIndexer.
 *
 * Endpoint: https://quasarmarket.coforge.com/qag/llmrouter-api/v2/text/embeddings
 * Model: text-embeddings
 * Auth: X-API-KEY header (same key as chat completions)
 */
object EmbeddingService {
    private val LOG = Logger.getInstance(EmbeddingService::class.java)
    private const val EMBED_URL = "https://quasarmarket.coforge.com/qag/llmrouter-api/v2/text/embeddings"
    private const val EMBED_MODEL = "text-embeddings"
    private val gson = Gson()

    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }

    // LRU cache: text hash → embedding vector, capped at 200 entries
    private val cache = object : LinkedHashMap<Int, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FloatArray>) = size > 200
    }

    fun embed(text: String, apiKey: String): FloatArray? {
        if (text.isBlank() || apiKey.isBlank()) return null
        val cacheKey = text.hashCode()
        synchronized(cache) { cache[cacheKey] }?.let { return it }

        return try {
            val body = gson.toJson(mapOf("model" to EMBED_MODEL, "input" to text.take(8192)))
            val request = HttpRequest.newBuilder()
                .uri(URI.create(EMBED_URL))
                .header("Content-Type", "application/json")
                .header("X-API-KEY", apiKey)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                LOG.warn("Embedding HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
                return null
            }
            val json = JsonParser.parseString(resp.body()).asJsonObject
            val arr = json.getAsJsonArray("data")
                ?.get(0)?.asJsonObject
                ?.getAsJsonArray("embedding")
                ?: run { LOG.warn("No embedding in response: ${resp.body().take(200)}"); return null }
            val vec = FloatArray(arr.size()) { arr[it].asFloat }
            synchronized(cache) { cache[cacheKey] = vec }
            vec
        } catch (e: Exception) {
            LOG.debug("Embedding call failed: ${e.message}")
            null
        }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-9) 0f else (dot / denom).toFloat()
    }

    fun clearCache() = synchronized(cache) { cache.clear() }
}
