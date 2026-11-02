import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(JvmTarget.JVM_26) } }
java { sourceCompatibility = JavaVersion.VERSION_26; targetCompatibility = JavaVersion.VERSION_26 }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.swing)
    implementation(libs.vlcj)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.bxane.nuvx.desktop.MainKt"
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(27)) }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Nuvx"
            packageVersion = "0.1.0"
            description = "Nuvx music client"
            vendor = "bxane"
            modules("jdk.httpserver", "java.net.http", "java.prefs", "jdk.unsupported")
            windows { iconFile.set(layout.buildDirectory.file("native-icons/nuvx.ico")) }
            linux { packageName = "nuvx"; iconFile.set(rootProject.file("core/assets/brand/flat/01_silver_white.png")) }
            macOS { bundleID = "com.bxane.nuvx" }
        }
    }
}

tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
