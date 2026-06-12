package com.coforge.codingagent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern

/**
 * Advanced AI Orchestrator with URL reading and structured change proposal.
 */
object AiService {
    private val LOG = Logger.getInstance(AiService::class.java)
    private const val API_URL = "https://quasarmarket.coforge.com/qag/llmrouter-api/v2/chat/completions"
    
    private val gson by lazy { Gson() }
    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }

    fun callAgentChain(
        prompt: String,
        context: String,
        onStatus: (String) -> Unit,
        onResult: (String) -> Unit
    ) {
        val settings = AppSettingsState.instance
        
        // Check for URLs in prompt
        fetchUrlContentIfPresent(prompt).thenAccept { urlContext ->
            val enrichedContext = if (urlContext.isNotEmpty()) "$context\n\nExternal Reference Content:\n$urlContext" else context
            
            // 1. REASONING PHASE (KIMI)
            onStatus("🧠 Reasoning & URL Analysis...")
            callModel(settings.kimiModel, settings.kimiApiKey, 
                "You are an expert architect. Analyze the prompt and context. If a URL was provided, prioritize its requirements. " +
                "Create a detailed technical plan. If file changes are needed, specify which files and why.\n\n" +
                "Context:\n$enrichedContext\n\nPrompt: $prompt")
                .thenCompose { reasoning ->
                    // 2. REVIEW PHASE (GEMINI)
                    onStatus("🔍 Reviewing (Gemini)...")
                    callModel(settings.geminiModel, settings.geminiApiKey, 
                        "Review this implementation plan for Android best practices. Ensure it strictly follows the requirements from the provided context/URL.\n\n" +
                        "Plan:\n$reasoning\n\nContext:\n$enrichedContext")
                        .thenCompose { review ->
                            // 3. GENERATION PHASE (GPT)
                            onStatus("🚀 Proposing Changes (GPT)...")
                            callModel(settings.gptModel, settings.gptApiKey, 
                                "You are an expert Android Developer. Based on the reasoning and review, propose the necessary code changes. " +
                                "IMPORTANT: For any file you want to change, wrap the code in <file_change path=\"path/to/file\">CODE</file_change> tags. " +
                                "For new files, use <new_file path=\"path/to/file\">CODE</new_file>.\n\n" +
                                "Reasoning:\n$reasoning\n\n" +
                                "Review:\n$review\n\n" +
                                "Context:\n$enrichedContext"
                            )
                        }
                }
                .thenAccept { finalResponse ->
                    onResult(finalResponse)
                }
                .exceptionally { ex ->
                    LOG.error("Chain Error: ${ex.message}")
                    onResult("❌ Error: ${ex.localizedMessage}")
                    null
                }
        }
    }

    private fun fetchUrlContentIfPresent(prompt: String): CompletableFuture<String> {
        val urlPattern = Pattern.compile("(https?://\\S+)")
        val matcher = urlPattern.matcher(prompt)
        if (matcher.find()) {
            val url = matcher.group(1)
            val request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply { it.body().take(5000) } // Limit to 5k chars for prompt safety
                .exceptionally { "Could not fetch URL: ${it.message}" }
        }
        return CompletableFuture.completedFuture("")
    }

    private fun callModel(model: String, apiKey: String, fullPrompt: String): CompletableFuture<String> {
        if (apiKey.isBlank()) return CompletableFuture.failedFuture(Exception("API Key missing"))

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", fullPrompt)
                })
            })
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("X-API-KEY", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() == 200) parseResponse(response.body())
                else throw Exception("Error ${response.statusCode()}")
            }
    }

    private fun parseResponse(body: String): String {
        return try {
            gson.fromJson(body, JsonObject::class.java)
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message").get("content").asString
        } catch (e: Exception) { "Parse Error" }
    }
}
