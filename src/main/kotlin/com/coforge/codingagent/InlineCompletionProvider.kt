package com.coforge.codingagent

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides Copilot-style ghost-text completions inside the editor.
 *
 * Trigger: any document change event in a Kotlin or Java file.
 * The provider grabs the text BEFORE and AFTER the cursor (as prefix/suffix),
 * makes a fast GPT call via AiService, and returns the suggestion as gray text.
 * The user accepts with Tab.
 */
class AiInlineCompletionProvider : InlineCompletionProvider {

    override val id = InlineCompletionProviderID("com.coforge.codingagent.inline")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (event !is InlineCompletionEvent.DocumentChange) return false
        val settings = AppSettingsState.instance
        if (!settings.inlineCompletionsEnabled) return false
        val ext = event.editor.virtualFile?.extension?.lowercase()
        return ext == "kt" || ext == "java"
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val (prefix, suffix, language) = withContext(Dispatchers.Default) {
            ReadAction.compute<Triple<String, String, String>, Throwable> {
                val doc = request.editor.document
                val offset = request.startOffset.coerceIn(0, doc.textLength)
                val fullText = doc.text
                val pre = fullText.substring(maxOf(0, offset - 2000), offset)
                val suf = fullText.substring(offset, minOf(fullText.length, offset + 500))
                val lang = request.editor.virtualFile?.extension?.lowercase() ?: "kotlin"
                Triple(pre, suf, lang)
            }
        }

        val completion = withContext(Dispatchers.IO) {
            AiService.getInlineCompletion(prefix, suffix, language)
        }

        if (completion.isBlank()) return InlineCompletionSuggestion.Empty
        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(completion))
        }
    }
}
