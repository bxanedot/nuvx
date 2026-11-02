import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}


kotlin {
    // eXcor's desktop artifact is JVM 26 bytecode, so the desktop target compiles on JDK 27 (see docs/TOOLCHAIN.md).
    jvmToolchain(27)

    android {
        namespace = "com.bxane.nuvx.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        androidResources { enable = true }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_26) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework { baseName = "NuvxShared"; isStatic = true }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        val desktopMain by getting { dependsOn(jvmCommonMain) }

        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            api(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.coil.compose)
            implementation(libs.coil.ktor)
            implementation(libs.innertubex)
        }
        jvmCommonMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.excor)
        }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        desktopMain.dependencies {
            implementation(libs.coroutines.swing)
        }
    }
}
