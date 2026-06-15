plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
    id("org.jetbrains.intellij.platform") // Version managed in settings.gradle.kts
}

group = "com.coforge.codingagent"
// Version is managed in gradle.properties to ensure unique builds for every iteration
version = project.property("pluginVersion") as String

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    // Bundling Gson with the plugin to avoid NoClassDefFoundError at runtime
    implementation("com.google.code.gson:gson:2.10.1")
    
    intellijPlatform {
        // Target SDK: Ladybug (Build 241)
        androidStudio("2024.1.2.12")
        // org.jetbrains.kotlin is re-added to enable KtFile/KtNamedFunction/KtClass PSI APIs
        // for real Kotlin type resolution (KotlinPsiService) and multi-file rename.
        // We use only stable structural PSI (no internal analysis APIs), so K2 compatibility is preserved.
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
        instrumentationTools()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
    kotlinOptions.freeCompilerArgs += listOf("-Xjvm-default=all")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.coforge.codingagent"
        name = "Coforge AI Coding Agent"
        vendor {
            name = "Coforge"
            email = "support@coforge.com"
            url = "https://www.coforge.com"
        }
        description = "An elite AI Coding Agent for Experienced Android Architects and Native Specialist Lead Developers. Strictly Native Android focused."
        
        ideaVersion {
            // Align with Ladybug baseline (241) to ensure compatibility with modern Android Studio
            sinceBuild.set("241")
            // Robustly remove untilBuild restriction for compatibility with future versions (252+)
            untilBuild.set(provider { null })
        }
    }
    
    // Enabling searchable options is required for the task that was failing with warnings
    buildSearchableOptions.set(true)
}
