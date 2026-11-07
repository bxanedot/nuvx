pluginManagement {
    val versions = file("../../../gradle/libs.versions.toml").readLines()
    fun version(name: String) = versions.first { it.startsWith("$name = ") }.substringAfter('"').substringBefore('"')
    val agpVersion = version("agp")
    val kotlinVersion = version("kotlin")
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.kotlin.multiplatform.library" -> useVersion(agpVersion)
                "org.jetbrains.kotlin.multiplatform" -> useVersion(kotlinVersion)
            }
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "eXcor"
