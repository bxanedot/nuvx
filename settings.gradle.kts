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
        maven("https://jitpack.io")
        maven(uri("core/third_party/maven")) { content { includeGroup("com.excor") } }
    }
}

rootProject.name = "nuvx"
include(":shared")
include(":sharedUi")
project(":shared").projectDir = file("core/shared")
project(":sharedUi").projectDir = file("core/sharedUi")

val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
    include(":tv")
    include(":wearos")
    project(":app").projectDir = file("app/android/mobile")
    project(":tv").projectDir = file("app/android/tv")
    project(":wearos").projectDir = file("app/android/wearos")
} else {
    logger.lifecycle("No Android SDK found — building the desktop targets only.")
}
