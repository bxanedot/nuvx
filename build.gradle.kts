extra["nuvxVersionCode"] = providers.gradleProperty("nuvx.nightlyBaseCode").map(String::toInt).getOrElse(9)
extra["nuvxVersionName"] = providers.gradleProperty("nuvx.nightlyBaseName").getOrElse("0.9.0")

extra["nuvxBetaSuffix"] = providers.gradleProperty("nuvx.nightlySuffix").getOrElse("")

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}
