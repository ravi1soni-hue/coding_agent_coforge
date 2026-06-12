package com.coforge.codingagent

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Reads the project's ACTUAL dependency declarations so prompts say
 * "this project uses retrofit:2.9.0, hilt:2.48, riverpod:2.4.9" instead of
 * guessing from a fixed hardcoded list.
 *
 * Supports:
 *   - Android: build.gradle (Groovy), build.gradle.kts (Kotlin DSL), libs.versions.toml
 *   - Flutter: pubspec.yaml (dependencies + dev_dependencies)
 */
object ProjectDependencyAnalyzer {

    data class Dependency(val name: String, val version: String?, val category: String)

    data class ProjectDeps(
        val dependencies: List<Dependency>,
        val kotlinVersion: String?,
        val agpVersion: String?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val compileSdk: Int?,
        val flutterSdkConstraint: String?,
        val dartVersion: String?
    ) {
        fun isEmpty() = dependencies.isEmpty()

        /** Compact string injected into every system prompt. */
        fun toPromptContext(): String {
            if (isEmpty()) return ""
            val sb = StringBuilder()

            // SDK info
            if (minSdk != null || targetSdk != null) {
                sb.append("Android SDK: min=$minSdk, target=$targetSdk, compile=$compileSdk\n")
            }
            if (flutterSdkConstraint != null) sb.append("Flutter SDK: $flutterSdkConstraint\n")
            if (dartVersion != null) sb.append("Dart SDK: $dartVersion\n")
            if (kotlinVersion != null) sb.append("Kotlin: $kotlinVersion\n")
            if (agpVersion != null) sb.append("AGP: $agpVersion\n")
            sb.append("\n")

            // Group dependencies by category
            val grouped = dependencies.groupBy { it.category }
            grouped.entries.sortedBy { it.key }.forEach { (cat, deps) ->
                sb.append("[$cat]\n")
                deps.sortedBy { it.name }.forEach { dep ->
                    sb.append("  ${dep.name}${if (dep.version != null) ":${dep.version}" else ""}\n")
                }
            }
            return sb.toString().trim()
        }
    }

    private val cache = object : LinkedHashMap<String, ProjectDeps>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProjectDeps>) = size > 10
    }

    fun analyze(project: Project): ProjectDeps {
        val base = project.basePath ?: return empty()
        return cache.getOrPut(base) { doAnalyze(base) }
    }

    fun invalidate(project: Project) { cache.remove(project.basePath) }

    // ─── Dispatcher ───────────────────────────────────────────────────────────

    private fun doAnalyze(base: String): ProjectDeps {
        if (File("$base/pubspec.yaml").exists()) return analyzePubspec(base)
        return analyzeGradle(base)
    }

    // ─── Flutter / Dart ───────────────────────────────────────────────────────

    private fun analyzePubspec(base: String): ProjectDeps {
        val text = File("$base/pubspec.yaml").readText()
        val deps = mutableListOf<Dependency>()

        var inDeps = false; var inDevDeps = false; var inFlutter = false
        for (line in text.lines()) {
            when {
                line.trimStart().startsWith("#") -> continue
                line.startsWith("dependencies:") -> { inDeps = true; inDevDeps = false; inFlutter = false }
                line.startsWith("dev_dependencies:") -> { inDevDeps = true; inDeps = false; inFlutter = false }
                line.startsWith("flutter:") && !inDeps && !inDevDeps -> { inFlutter = true; inDeps = false; inDevDeps = false }
                line.startsWith("  ") && (inDeps || inDevDeps) -> {
                    val trimmed = line.trim()
                    if (trimmed.isBlank() || trimmed.startsWith("#")) continue
                    val parts = trimmed.split(":")
                    val name = parts[0].trim()
                    val version = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                        ?.replace(Regex("[^\\d.^~>=<]"), "")?.takeIf { it.isNotBlank() }
                    if (name.isNotBlank() && name != "sdk")
                        deps.add(Dependency(name, version, if (inDevDeps) "dev" else "dependencies"))
                }
                !line.startsWith(" ") -> { inDeps = false; inDevDeps = false; inFlutter = false }
            }
        }

        val flutterConstraint = Regex("flutter:\\s*['\"]?([>=<^~][\\d. ]+)").find(text)?.groupValues?.get(1)?.trim()
        val dartVersion       = Regex("sdk:\\s*['\"]?([>=<^~][\\d. ]+)").find(text)?.groupValues?.get(1)?.trim()

        return ProjectDeps(deps, null, null, null, null, null, flutterConstraint, dartVersion)
    }

    // ─── Android / Gradle ────────────────────────────────────────────────────

    private fun analyzeGradle(base: String): ProjectDeps {
        val deps = mutableListOf<Dependency>()
        var minSdk = 0; var targetSdk = 0; var compileSdk = 0
        var kotlinVer: String? = null; var agpVer: String? = null

        // 1. Version catalog (libs.versions.toml) — most modern projects use this
        val toml = File("$base/gradle/libs.versions.toml")
        val tomlVersions = mutableMapOf<String, String>()
        if (toml.exists()) {
            var inVersions = false; var inLibs = false
            for (line in toml.readLines()) {
                val t = line.trim()
                when {
                    t == "[versions]" -> { inVersions = true; inLibs = false }
                    t == "[libraries]" || t == "[plugins]" -> { inLibs = true; inVersions = false }
                    t.startsWith("[") -> { inVersions = false; inLibs = false }
                    inVersions && t.contains("=") -> {
                        val (k, v) = t.split("=", limit = 2)
                        tomlVersions[k.trim()] = v.trim().trim('"')
                    }
                    inLibs && t.contains("=") -> {
                        val name = t.substringBefore("=").trim().replace("-", ":")
                        val ver = Regex("version\\.ref\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)
                            ?.let { tomlVersions[it] }
                            ?: Regex("version\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)
                        val group = Regex("module\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)
                            ?: Regex("group\\s*=\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)
                        val libName = group?.substringAfterLast(":") ?: name.substringAfterLast(".")
                        deps.add(Dependency(group ?: libName, ver, categorize(group ?: libName)))
                    }
                }
            }
            kotlinVer = tomlVersions["kotlin"] ?: tomlVersions["kotlin-version"]
            agpVer    = tomlVersions["agp"] ?: tomlVersions["android-gradle-plugin"]
        }

        // 2. Module build.gradle files (app + any modules)
        val gradleFiles = mutableListOf<File>()
        listOf("app/build.gradle", "app/build.gradle.kts", "build.gradle", "build.gradle.kts")
            .map { File("$base/$it") }.filterTo(gradleFiles) { it.exists() }
        // Also find module build.gradle files
        File(base).listFiles()
            ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
            ?.forEach { gradleFiles.add(File(it, "build.gradle.kts")) }

        val depPatterns = listOf(
            Regex("""(?:implementation|api|testImplementation|androidTestImplementation|kapt|ksp|debugImplementation)\s*["'(]([^"')]+)["')]"""),
            Regex("""(?:implementation|api|testImplementation)\s*\(libs\.[a-zA-Z.]+\)""")
        )
        val sdkPattern = Regex("""(minSdk|targetSdk|compileSdk)(?:Version)?\s*[=:]\s*(\d+)""")
        val kotlinPattern = Regex("""kotlin[_-]version\s*[=:]\s*["']([^"']+)""")
        val agpPattern = Regex("""com\.android\.tools\.build:gradle:([^\s"']+)""")

        for (f in gradleFiles) {
            val text = f.readText()
            sdkPattern.findAll(text).forEach { m ->
                when (m.groupValues[1]) {
                    "minSdk", "minSdkVersion"     -> minSdk = m.groupValues[2].toIntOrNull() ?: minSdk
                    "targetSdk", "targetSdkVersion" -> targetSdk = m.groupValues[2].toIntOrNull() ?: targetSdk
                    "compileSdk", "compileSdkVersion" -> compileSdk = m.groupValues[2].toIntOrNull() ?: compileSdk
                }
            }
            if (kotlinVer == null) kotlinPattern.find(text)?.let { kotlinVer = it.groupValues[1] }
            if (agpVer == null) agpPattern.find(text)?.let { agpVer = it.groupValues[1] }

            depPatterns[0].findAll(text).forEach { m ->
                val coord = m.groupValues[1]
                val parts = coord.split(":")
                if (parts.size >= 2) {
                    val name = parts.getOrNull(1) ?: parts[0]
                    val ver  = parts.getOrNull(2)?.replace(Regex("[^\\d.]"), "")?.takeIf { it.isNotBlank() }
                    if (deps.none { it.name == name }) deps.add(Dependency(name, ver, categorize(coord)))
                }
            }
        }

        return ProjectDeps(
            dependencies = deps.distinctBy { it.name },
            kotlinVersion = kotlinVer,
            agpVersion = agpVer,
            minSdk = minSdk.takeIf { it > 0 },
            targetSdk = targetSdk.takeIf { it > 0 },
            compileSdk = compileSdk.takeIf { it > 0 },
            flutterSdkConstraint = null,
            dartVersion = null
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun categorize(coord: String): String {
        val lower = coord.lowercase()
        return when {
            lower.contains("compose") || lower.contains("material") -> "UI / Compose"
            lower.contains("hilt") || lower.contains("dagger") || lower.contains("inject") -> "DI"
            lower.contains("room") || lower.contains("database") || lower.contains("sqlite") -> "Database"
            lower.contains("retrofit") || lower.contains("okhttp") || lower.contains("gson") || lower.contains("moshi") || lower.contains("ktor") -> "Network"
            lower.contains("coroutine") || lower.contains("flow") || lower.contains("rxjava") -> "Async"
            lower.contains("navigation") || lower.contains("gorouter") -> "Navigation"
            lower.contains("test") || lower.contains("mockk") || lower.contains("junit") || lower.contains("espresso") -> "Testing"
            lower.contains("work") || lower.contains("paging") || lower.contains("datastore") -> "Jetpack"
            lower.contains("firebase") || lower.contains("crashlytics") || lower.contains("analytics") -> "Firebase"
            lower.contains("bloc") || lower.contains("riverpod") || lower.contains("provider") || lower.contains("getx") -> "State Mgmt"
            lower.contains("coil") || lower.contains("glide") || lower.contains("picasso") || lower.contains("cached_network") -> "Image Loading"
            lower.contains("lottie") || lower.contains("animation") -> "Animation"
            lower.contains("maps") || lower.contains("location") || lower.contains("geolocator") -> "Maps / Location"
            lower.contains("camera") || lower.contains("image_picker") -> "Camera / Media"
            else -> "Libraries"
        }
    }

    private fun empty() = ProjectDeps(emptyList(), null, null, null, null, null, null, null)
}
