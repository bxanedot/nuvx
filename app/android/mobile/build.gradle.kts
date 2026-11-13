import java.util.Properties
import java.security.MessageDigest
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val lastfmApiKey: String = (
    localProps.getProperty("LASTFM_API_KEY")
        ?: System.getenv("LASTFM_API_KEY")
        ?: ""
    ).trim()
val lastfmSecret: String = (
    localProps.getProperty("LASTFM_SECRET")
        ?: System.getenv("LASTFM_SECRET")
        ?: ""
    ).trim()

val listenTogetherServer: String = (
    localProps.getProperty("LISTEN_TOGETHER_SERVER")
        ?: System.getenv("LISTEN_TOGETHER_SERVER")
        ?: "https://nuvx-listen.bxane.workers.dev"
    ).trim().trimEnd('/')

val addonAuthServer: String = (
    localProps.getProperty("ADDON_AUTH_SERVER")
        ?: System.getenv("ADDON_AUTH_SERVER")
        ?: "https://auth.nuvx.lol"
    ).trim().trimEnd('/')

val betaSuffix = rootProject.extra["nuvxBetaSuffix"] as String

android {
    namespace = "com.bxane.nuvx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bxane.nuvx"
        minSdk = 26
        targetSdk = 36
        versionCode = rootProject.extra["nuvxVersionCode"] as Int
        versionName = rootProject.extra["nuvxVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "LASTFM_API_KEY", "\"${lastfmApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${lastfmSecret.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField(
            "String",
            "LISTEN_TOGETHER_SERVER",
            "\"${listenTogetherServer.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "String",
            "ADDON_AUTH_URL",
            "\"${addonAuthServer.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField("String", "UPDATE_APK_PREFIX", "\"nuvx-\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../wearos/protocol")
            assets.srcDir("../../web/app/public/models")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationId = "com.bxane.nuvx.dev"
            resValue("string", "app_name", "nuvx Dev")
        }
        create("prod") {
            dimension = "env"
        }
    }

    signingConfigs {
        val store = signing.getProperty("storeFile")?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            create("release") {
                storeFile = store
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
        }
        release {
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.md")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>(
    "stripNewPipeExtractorUtils"
) {
    archiveFileName.set("NewPipeExtractor-v0.27.0-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { newPipeExtractorRaw.map { zipTree(it) } }) {
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":sharedUi"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    implementation("androidx.palette:palette-ktx:1.1.0")

    implementation("dev.chrisbanes.haze:haze:1.7.3")
    implementation("dev.chrisbanes.haze:haze-materials:1.7.3")

    implementation("com.google.zxing:core:3.5.4")

    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.20.0")
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.20.0")

    implementation("io.ktor:ktor-client-core:3.6.1")
    implementation("io.ktor:ktor-client-okhttp:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    implementation("io.ktor:ktor-client-websockets:3.6.1")

    implementation("com.github.MetrolistGroup.innertubex:innertubex-android:v0.11.6")
    implementation("com.excor:eXcor-android:0.2.0")

    implementation(files(newPipeExtractorStripped))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation("org.mozilla:rhino:1.9.2")
    implementation("org.mozilla:rhino-engine:1.9.2")

    implementation("androidx.security:security-crypto:1.1.0")

    implementation("io.github.dokar3:quickjs-kt-android:1.0.18")

    implementation("com.hierynomus:smbj:0.15.0")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    implementation("com.google.android.gms:play-services-cast-framework:23.0.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")

    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear:wear-remote-interactions:1.2.0")

    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        exclude(group = "org.bouncycastle")
    }
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

val verifyDevInstall = tasks.register("verifyDevInstall") {
    group = "install"
    description = "Pre-verifies the installed dev build on every connected device."
    val adb = androidComponents.sdkComponents.adb
    doLast {
        val adbPath = adb.get().asFile.absolutePath
        val serials = ProcessBuilder(adbPath, "devices").start()
            .inputStream.bufferedReader().readLines()
            .drop(1)
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 && it[1] == "device" }?.get(0) }
        serials.forEach { serial ->
            logger.lifecycle("verifyDevInstall: compiling com.bxane.nuvx.dev on $serial")
            ProcessBuilder(
                adbPath, "-s", serial, "shell", "cmd", "package", "compile",
                "-m", "verify", "-f", "com.bxane.nuvx.dev",
            ).inheritIO().start().waitFor()
        }
    }
}
tasks.matching { it.name == "installDevDebug" }.configureEach { finalizedBy(verifyDevInstall) }

abstract class BundleWearApk : DefaultTask() {
    @get:InputFiles
    abstract val apks: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile.resolve("wear")
        out.deleteRecursively()
        out.mkdirs()
        val apk = apks.asFileTree.matching { include("*.apk") }.files.singleOrNull()
            ?: throw GradleException("Expected exactly one watch APK in ${apks.files}")
        val target = out.resolve("NuvxWear.apk")
        apk.copyTo(target, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        out.resolve("NuvxWear.sha256").writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

if (providers.gradleProperty("nuvx.bundleWear").orNull != "false" && findProject(":wearos") != null) {
    androidComponents.onVariants { variant ->
        val flavor = variant.productFlavors.firstOrNull { it.first == "env" }?.second ?: return@onVariants
        val type = variant.buildType ?: return@onVariants
        if (type != "debug" && type != "release") return@onVariants
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val bundle = tasks.register<BundleWearApk>("bundleWearApk$cap") {
            dependsOn(":wearos:assemble$cap")
            apks.from(rootProject.layout.projectDirectory.dir("app/android/wearos/build/outputs/apk/$flavor/$type"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleWearApk::outputDir)
    }
}
