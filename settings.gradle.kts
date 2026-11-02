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
        // eXcor has no remote release yet; CI publishes it to mavenLocal first (see docs/TOOLCHAIN.md).
        mavenLocal { content { includeGroup("com.excor") } }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.MetrolistGroup.innertubex")
                includeGroup("com.github.MetrolistGroup.quackjs")
            }
        }
    }
}

rootProject.name = "nuvx"
include(":shared", ":androidApp", ":desktopApp")
project(":shared").projectDir = file("core/shared")
project(":androidApp").projectDir = file("App/androidApp")
project(":desktopApp").projectDir = file("App/desktopApp")
