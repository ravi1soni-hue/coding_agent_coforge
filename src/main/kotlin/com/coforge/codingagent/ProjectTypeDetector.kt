package com.coforge.codingagent

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Detects whether the open project is Android Native or Flutter.
 * Result is cached per project path so detection only runs once.
 */
object ProjectTypeDetector {

    enum class ProjectType { ANDROID_NATIVE, FLUTTER, UNKNOWN }

    data class ProjectInfo(
        val type: ProjectType,
        val minSdkVersion: Int?,
        val targetSdkVersion: Int?,
        val flutterVersion: String?,
        val mainLanguage: String
    )

    private val cache = mutableMapOf<String, ProjectInfo>()

    fun detect(project: Project): ProjectInfo {
        val base = project.basePath ?: return unknown()
        return cache.getOrPut(base) { analyse(base) }
    }

    fun invalidate(project: Project) { cache.remove(project.basePath) }

    private fun analyse(base: String): ProjectInfo {
        // Flutter: has pubspec.yaml with a flutter SDK dependency
        val pubspec = File("$base/pubspec.yaml")
        if (pubspec.exists()) {
            val text = pubspec.readText()
            if (text.contains("flutter:") || text.contains("sdk: flutter")) {
                val flutterVer = Regex("flutter:\\s*['\"]?([\\d.]+)").find(text)?.groupValues?.get(1)
                return ProjectInfo(ProjectType.FLUTTER, null, null, flutterVer, "Dart")
            }
        }

        // Android: has build.gradle or build.gradle.kts
        val gradleFiles = listOf(
            File("$base/app/build.gradle"),
            File("$base/app/build.gradle.kts"),
            File("$base/build.gradle"),
            File("$base/build.gradle.kts")
        )
        val gradleText = gradleFiles.firstOrNull { it.exists() }?.readText() ?: ""
        val minSdk    = Regex("minSdk(?:Version)?\\s*[=:]\\s*(\\d+)").find(gradleText)?.groupValues?.get(1)?.toIntOrNull()
        val targetSdk = Regex("targetSdk(?:Version)?\\s*[=:]\\s*(\\d+)").find(gradleText)?.groupValues?.get(1)?.toIntOrNull()
        val isKotlin  = File("$base/app/src/main/kotlin").exists() || gradleText.contains("kotlin")
        if (gradleText.isNotEmpty()) {
            return ProjectInfo(ProjectType.ANDROID_NATIVE, minSdk, targetSdk, null, if (isKotlin) "Kotlin" else "Java")
        }

        return unknown()
    }

    private fun unknown() = ProjectInfo(ProjectType.UNKNOWN, null, null, null, "Kotlin")
}
