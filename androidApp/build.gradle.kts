plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.bxane.nuvx"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.bxane.nuvx"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("nuvx.versionCode").get().toInt()
        versionName = providers.gradleProperty("nuvx.versionName").get()
    }
    buildFeatures { compose = true }
    flavorDimensions += "device"
    productFlavors {
        create("mobile") { dimension = "device" }
        create("tv") { dimension = "device" }
        create("wear") { dimension = "device" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
