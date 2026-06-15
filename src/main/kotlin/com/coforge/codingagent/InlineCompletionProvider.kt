package com.coforge.codingagent

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Copilot-style ghost-text completions inside the editor.
 *
 * Trigger: any document change in Kotlin, Java, Dart, KTS, XML, YAML files.
 *
 * Optimisations vs original:
 *   1. 200ms debounce (was 400ms) — snappier on fast typists.
 *   2. Uses Gemini Flash by default for much lower latency than GPT-5.
 *   3. LRU cache (100 entries) keyed on language + 300-char prefix + 100-char suffix.
 *      Language prefix prevents cross-file cache collisions.
 *   4. max_tokens = 80 keeps round-trips fast.
 *   5. Reads 3000 chars of prefix (was 2000) for better function-level context.
 */
class AiInlineCompletionProvider : InlineCompletionProvider {

    override val id = InlineCompletionProviderID("com.coforge.codingagent.inline")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (event !is InlineCompletionEvent.DocumentChange) return false
        val settings = AppSettingsState.instance
        if (!settings.inlineCompletionsEnabled) return false
        if (settings.geminiApiKey.isBlank() && settings.gptApiKey.isBlank()) return false
        val ext = event.editor.virtualFile?.extension?.lowercase()
        return ext in setOf("kt", "kts", "java", "dart", "xml", "yaml")
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        // 200ms debounce: cancels automatically when the user keeps typing
        delay(200)

        data class CtxData(val pre: String, val suf: String, val lang: String, val projPath: String)
        val ctx = withContext(Dispatchers.Default) {
            ReadAction.compute<CtxData, Throwable> {
                val doc    = request.editor.document
                val offset = request.startOffset.coerceIn(0, doc.textLength)
                val text   = doc.text
                val pre    = text.substring(maxOf(0, offset - 3000), offset)
                val suf    = text.substring(offset, minOf(text.length, offset + 500))
                val lang   = request.editor.virtualFile?.extension?.lowercase() ?: "kotlin"
                val proj   = request.editor.project?.basePath ?: ""
                CtxData(pre, suf, lang, proj)
            }
        }
        val (prefix, suffix, language, projectPath) = ctx

        // Project + language + context key prevents cross-project and cross-file cache collisions
        val cacheKey = "$projectPath::$language::${prefix.takeLast(300)}::${suffix.take(100)}"
        completionCache[cacheKey]?.let { cached ->
            return if (cached.isBlank()) InlineCompletionSuggestion.Empty
                   else InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(cached)) }
        }

        val completion = withContext(Dispatchers.IO) {
            AiService.getInlineCompletion(prefix, suffix, language)
        }

        completionCache[cacheKey] = completion   // cache blank results too to skip retries

        return if (completion.isBlank()) InlineCompletionSuggestion.Empty
               else InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(completion)) }
    }

    companion object {
        // LRU cache shared across all instances; capped at 100 entries
        private val completionCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 100
        }
    }
}
