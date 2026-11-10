import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.31.1")
                api("io.ktor:ktor-client-core:3.6.1")
                api("io.ktor:ktor-client-okhttp:3.6.1")
                api("io.ktor:ktor-client-websockets:3.6.1")
                api("io.ktor:ktor-client-content-negotiation:3.6.1")
                api("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jsoup:jsoup:1.24.1")
                api("com.github.MetrolistGroup.innertubex:innertubex:v0.11.6")
                compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:5.5.1")
        }
    }
}

android {
    namespace = "com.bxane.nuvx.shared"
    compileSdk = 37
}
