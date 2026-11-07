pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.MetrolistGroup.innertubex")
                includeGroup("com.github.MetrolistGroup.quackjs")
                includeGroup("com.github.bxanedot.GlyphStudio")
            }
        }
    }
}

rootProject.name = "nuvx"
include(":shared", ":androidApp", ":desktopApp")
project(":shared").projectDir = file("core/shared")
project(":androidApp").projectDir = file("App/androidApp")
project(":desktopApp").projectDir = file("App/desktopApp")

val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
val engineFetch = providers.exec {
    workingDir(settingsDir)
    commandLine(listOf(python, "-B", "core/scripts/update-engines.py", "--fetch") +
        if (gradle.startParameter.isOffline) listOf("--offline") else emptyList())
}
engineFetch.result.get().assertNormalExitValue()
logger.lifecycle(engineFetch.standardOutput.asText.get().trim())
val engines = java.util.Properties().apply {
    file(".gradle/engines/resolved.properties").inputStream().use { load(it) }
}
val engineCommit = engines.getProperty("excor.commit")
require(engineCommit.matches(Regex("[0-9a-f]{40}")))
includeBuild(".gradle/engines/excor-$engineCommit") {
    name = "excor"
    dependencySubstitution {
        substitute(module("com.excor:eXcor")).using(project(":"))
    }
}
