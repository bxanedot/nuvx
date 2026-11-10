import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val cmp = "1.12.2"

val CMP_DESKTOP =
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "1.10.3" else "1.12.0"

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(project(":shared"))
            api("org.jetbrains.compose.runtime:runtime:$cmp")
            api("org.jetbrains.compose.foundation:foundation:$cmp")
            api("org.jetbrains.compose.animation:animation:$cmp")
            api("org.jetbrains.compose.ui:ui:$cmp")
            api("org.jetbrains.compose.material3:material3:1.9.0")
            api("com.github.bxanedot.GlyphStudio:sfsymbols:v1.0.0")
            api("org.jetbrains.compose.components:components-resources:$cmp")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            api("io.coil-kt.coil3:coil-compose:3.6.3")
            api("dev.chrisbanes.haze:haze:1.7.3")
            api("dev.chrisbanes.haze:haze-materials:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        jvmMain.dependencies {
            api("org.jetbrains.compose.ui:ui-desktop:$CMP_DESKTOP")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("androidx.core:core-ktx:1.19.1")
            implementation("androidx.appcompat:appcompat:1.8.0")
            implementation("androidx.palette:palette-ktx:1.1.0")
        }
    }
}

android {
    namespace = "com.bxane.nuvx.sharedui"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.bxane.nuvx.sharedui.resources"
    generateResClass = always
}
