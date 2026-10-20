import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun feedField(name: String): String {
    val feed = rootDir.resolve("../docs/feed.md")
    require(feed.isFile) { "canonical feed doc missing: ${feed.invariantSeparatorsPath}" }
    val text = feed.readText()
    val match = Regex("""^$name=(.+)$""", RegexOption.MULTILINE).find(text)
        ?: error("docs/feed.md has no $name= line")
    val value = match.groupValues[1].trim()
    require(value.isNotEmpty()) { "empty $name in docs/feed.md" }
    require(!value.contains("\"") && !value.contains("\\")) { "refusing unsafe $name" }
    return value
}

val localFeed = Properties().apply {
    val file = rootDir.resolve("../feed.local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun feedSetting(name: String, environment: String, default: String): String {
    val value = providers.environmentVariable(environment).orNull
        ?: localFeed.getProperty(name)
        ?: default
    require(!value.contains("\"") && !value.contains("\\")) { "refusing unsafe $name" }
    return value.trim()
}

val localSigning = Properties().apply {
    val file = rootDir.resolve("../signing.local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val signingFields = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
val signingConfigured = signingFields.all { !localSigning.getProperty(it).isNullOrBlank() }
require(localSigning.isEmpty() || signingConfigured) { "Incomplete release signing configuration" }

val feedBaseUrl = feedSetting("BASE_URL", "TRANSMICRONE_BASE_URL", feedField("BASE_URL"))
val feedAppId = feedSetting("APPID", "TRANSMICRONE_APP_ID", "unconfigured")
val prNumber = providers.gradleProperty("prNumber").orNull
require(prNumber == null || prNumber.matches(Regex("[1-9][0-9]*"))) {
    "prNumber must be a positive pull request number"
}
val prLabel = prNumber ?: "local"

android {
    namespace = "co.camilo.transmicrone"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "co.camilo.transmicrone"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-alpha.2"
        // Also inherited by the test APK, which cannot reference the app's strings.
        resValue("string", "launcher_name", "TransMinuto")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FEED_BASE_URL", "\"$feedBaseUrl\"")
        buildConfigField("String", "FEED_APPID", "\"$feedAppId\"")
        buildConfigField("boolean", "MOCK_FEED", "false")
        buildConfigField("long", "AUTO_REFRESH_MS", "25000L")
        buildConfigField("int", "HTTP_CONNECT_TIMEOUT_MS", "10000")
        buildConfigField("int", "HTTP_READ_TIMEOUT_MS", "15000")
    }

    signingConfigs {
        // Public test key, never use this identity for production releases.
        create("prPreview") {
            storeFile = rootDir.resolve("keystores/pr-preview.jks")
            storePassword = "android"
            keyAlias = "pr-preview"
            keyPassword = "android"
        }
        if (signingConfigured) create("permanentRelease") {
            storeFile = rootDir.resolve(localSigning.getProperty("STORE_FILE"))
            storePassword = localSigning.getProperty("STORE_PASSWORD")
            keyAlias = localSigning.getProperty("KEY_ALIAS")
            keyPassword = localSigning.getProperty("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (signingConfigured) signingConfig = signingConfigs.getByName("permanentRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "MOCK_FEED", providers.gradleProperty("mockFeed").orElse("false").get().toBoolean().toString())
        }
        create("device") {
            initWith(getByName("release"))
            // Update the development app in place without debugger overhead.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
        }
        create("pr") {
            initWith(getByName("release"))
            applicationIdSuffix = ".pr${prNumber.orEmpty()}"
            versionNameSuffix = "-pr.$prLabel"
            resValue("string", "launcher_name", "TransMinuto PR ${prNumber?.let { "#$it" } ?: "local"}")
            signingConfig = signingConfigs.getByName("prPreview")
            isDebuggable = false
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.matching { it.name == "prePrBuild" }.configureEach {
    doFirst {
        require(feedAppId.isNotBlank() && feedAppId !in setOf("unconfigured", "ci-not-configured")) {
            "PR APKs require a configured live feed app ID. See docs/feed.md."
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    // Public Expressive components compatible with the project's SDK 36 toolchain.
    implementation("androidx.compose.material3:material3:1.5.0-alpha02")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
