pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nuvx-apple"
include(":shared", ":innertubex", ":quackjs")
project(":innertubex").projectDir = file("vendor/innertubex")
project(":quackjs").projectDir = file("vendor/quackjs")
