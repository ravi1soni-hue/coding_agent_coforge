package com.coforge.codingagent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/**
 * Extracts rich type signatures from Kotlin source files using the Kotlin PSI.
 * Replaces the regex fallback in EditorContext for .kt/.kts files.
 *
 * Requires org.jetbrains.kotlin bundled plugin (always present in Android Studio).
 */
object KotlinPsiService {

    data class KtSymbolInfo(
        val name: String,
        val kind: String,          // "class", "data class", "sealed class", "interface", "enum", "fun", "suspend fun", "val", "var"
        val returnType: String,    // e.g. "StateFlow<UiState>", "" for classes
        val params: String,        // e.g. "id: Long, name: String"
        val superTypes: String,    // e.g. "ViewModel(), Parcelable"
        val isPrivate: Boolean
    )

    /** Extract all top-level and member symbols with full type info. */
    fun extractSymbols(project: Project, file: VirtualFile): List<KtSymbolInfo> {
        if (file.extension !in setOf("kt", "kts")) return emptyList()
        return try {
            ReadAction.compute<List<KtSymbolInfo>, Exception> {
                val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile
                    ?: return@compute emptyList()
                buildSymbols(psiFile)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun buildSymbols(psiFile: KtFile): List<KtSymbolInfo> {
        val result = mutableListOf<KtSymbolInfo>()
        psiFile.declarations.forEach { decl ->
            when (decl) {
                is KtClass -> {
                    result.add(symbolFromClass(decl))
                    // Member functions and properties
                    decl.declarations.forEach { member ->
                        when (member) {
                            is KtNamedFunction -> result.add(symbolFromFun(member))
                            is KtProperty      -> result.add(symbolFromProp(member))
                            else               -> {}
                        }
                    }
                }
                is KtObjectDeclaration -> {
                    result.add(KtSymbolInfo(
                        name = decl.name ?: "?",
                        kind = if (decl.isCompanion()) "companion object" else "object",
                        returnType = "", params = "", superTypes = "",
                        isPrivate = decl.hasModifier(KtTokens.PRIVATE_KEYWORD)
                    ))
                    decl.declarations.filterIsInstance<KtNamedFunction>().forEach { result.add(symbolFromFun(it)) }
                }
                is KtNamedFunction -> result.add(symbolFromFun(decl))
                is KtProperty      -> result.add(symbolFromProp(decl))
                else               -> {}
            }
        }
        return result
    }

    private fun symbolFromClass(cls: KtClass) = KtSymbolInfo(
        name = cls.name ?: "?",
        kind = when {
            cls.isInterface() -> "interface"
            cls.isData()      -> "data class"
            cls.isSealed()    -> "sealed class"
            cls.isAbstract()  -> "abstract class"
            cls.isEnum()      -> "enum"
            else              -> "class"
        },
        returnType = "",
        params = cls.primaryConstructorParameters.joinToString(", ") {
            "${it.name}: ${it.typeReference?.text ?: "Any"}"
        },
        superTypes = cls.superTypeListEntries.joinToString(", ") { it.text },
        isPrivate = cls.hasModifier(KtTokens.PRIVATE_KEYWORD)
    )

    private fun symbolFromFun(fn: KtNamedFunction) = KtSymbolInfo(
        name = fn.name ?: "?",
        kind = when {
            fn.hasModifier(KtTokens.SUSPEND_KEYWORD) && fn.hasModifier(KtTokens.OVERRIDE_KEYWORD) -> "override suspend fun"
            fn.hasModifier(KtTokens.SUSPEND_KEYWORD) -> "suspend fun"
            fn.hasModifier(KtTokens.OVERRIDE_KEYWORD) -> "override fun"
            else -> "fun"
        },
        returnType = fn.typeReference?.text ?: "Unit",
        params = fn.valueParameters.joinToString(", ") {
            "${it.name}: ${it.typeReference?.text ?: "Any"}"
        },
        superTypes = "",
        isPrivate = fn.hasModifier(KtTokens.PRIVATE_KEYWORD)
    )

    private fun symbolFromProp(prop: KtProperty) = KtSymbolInfo(
        name = prop.name ?: "?",
        kind = if (prop.isVar) "var" else "val",
        returnType = prop.typeReference?.text ?: "",
        params = "", superTypes = "",
        isPrivate = prop.hasModifier(KtTokens.PRIVATE_KEYWORD)
    )

    /**
     * Format symbol list for AI context injection.
     * Produces a compact type-signature summary like an IDL or interface stub.
     */
    fun formatForContext(symbols: List<KtSymbolInfo>): String = buildString {
        symbols.filter { !it.isPrivate }.forEach { s ->
            when {
                s.kind.endsWith("class") || s.kind == "interface" || s.kind == "object"
                    || s.kind == "companion object" || s.kind == "enum" -> {
                    val superClause = if (s.superTypes.isNotBlank()) " : ${s.superTypes}" else ""
                    val paramsClause = if (s.params.isNotBlank()) "(${s.params})" else ""
                    appendLine("  ${s.kind} ${s.name}$paramsClause$superClause")
                }
                s.kind.contains("fun") -> {
                    appendLine("  ${s.kind} ${s.name}(${s.params}): ${s.returnType}")
                }
                else -> {
                    val typeClause = if (s.returnType.isNotBlank()) ": ${s.returnType}" else ""
                    appendLine("  ${s.kind} ${s.name}$typeClause")
                }
            }
        }
    }

    /**
     * Returns the enclosing function and class at a given offset, with full type info.
     * Used by EditorContext to replace the regex fallback for Kotlin files.
     */
    fun getEnclosingContext(project: Project, file: VirtualFile, offset: Int): String? {
        if (file.extension !in setOf("kt", "kts")) return null
        return try {
            ReadAction.compute<String?, Exception> {
                val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return@compute null
                val element = psiFile.findElementAt(offset) ?: return@compute null
                val parts = mutableListOf<String>()

                // Walk up the PSI tree to find enclosing class and function
                var cur = element.parent
                var foundFun = false
                var foundClass = false
                while (cur != null && cur !is KtFile) {
                    if (!foundFun && cur is KtNamedFunction) {
                        val params = cur.valueParameters.joinToString(", ") { "${it.name}: ${it.typeReference?.text ?: "Any"}" }
                        val ret = cur.typeReference?.text ?: "Unit"
                        val kind = if (cur.hasModifier(KtTokens.SUSPEND_KEYWORD)) "suspend fun" else "fun"
                        parts.add("$kind ${cur.name}($params): $ret")
                        foundFun = true
                    }
                    if (!foundClass && (cur is KtClass || cur is KtObjectDeclaration)) {
                        val name = (cur as? KtNamedDeclaration)?.name ?: "?"
                        val kind = when {
                            cur is KtClass && cur.isData()   -> "data class"
                            cur is KtClass && cur.isSealed() -> "sealed class"
                            cur is KtObjectDeclaration       -> "object"
                            cur is KtClass                   -> "class"
                            else                             -> "class"
                        }
                        parts.add("$kind $name")
                        foundClass = true
                    }
                    if (foundFun && foundClass) break
                    cur = cur.parent
                }

                // Kotlin imports for framework context
                psiFile.importDirectives
                    .mapNotNull { it.importedFqName?.asString() }
                    .filter { imp ->
                        listOf("android", "kotlinx", "compose", "lifecycle", "hilt", "dagger",
                               "room", "retrofit", "coroutine", "flow", "navigation")
                            .any { imp.contains(it, ignoreCase = true) }
                    }
                    .take(10)
                    .takeIf { it.isNotEmpty() }
                    ?.let { parts.add("imports: ${it.joinToString(", ")}") }

                if (parts.isEmpty()) null else parts.joinToString(" | ")
            }
        } catch (_: Exception) { null }
    }
}
