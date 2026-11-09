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
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
        // eXcor (github.com/bxanedot/eXcor) is not on a public repository yet; see third_party/README.md.
        maven(uri("core/third_party/maven")) { content { includeGroup("com.excor") } }
    }
}

rootProject.name = "nuvx"
include(":shared")
include(":sharedUi")
// Apps live under app/, everything they share under core/.
project(":shared").projectDir = file("core/shared")
project(":sharedUi").projectDir = file("core/sharedUi")

// The Android module only when there is an SDK to build it against. A machine
// set up for the desktop alone has none, and including it there fails at
// configuration time — before the desktop module it has nothing to do with can
// build at all.
// Read as a Properties file, not line by line: Android Studio writes the path
// escaped (`C\:\Users\...` on Windows), and only Properties undoes that.
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
