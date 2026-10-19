plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigning =
    listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith {
        providers.environmentVariable("MIEL_SIGNING_$it")
    }
val mielVersionName = providers.gradleProperty("mielVersionName").orElse("0.1.0").get()
val versionParts = mielVersionName.split('.').map { it.toIntOrNull() }

require(versionParts.size == 3 && versionParts.all { it != null && it >= 0 }) {
    "mielVersionName must be MAJOR.MINOR.PATCH"
}

val (major, minor, patch) = versionParts.map { it!! }

val mielVersionCode = major.toLong() * 1_000_000 + minor * 1_000L + patch

require(minor < 1000 && patch < 1000 && mielVersionCode in 1..2_100_000_000L) {
    "Version components exceed Android versionCode limits"
}

android {
    namespace = "io.github.landern.miel"
    compileSdk = 37
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    defaultConfig {
        applicationId = "io.github.landern.miel"
        minSdk = 29
        targetSdk = 35
        versionCode = mielVersionCode.toInt()
        versionName = mielVersionName
        testInstrumentationRunner = "io.github.landern.miel.CoreHostInstrumentation"
    }
    signingConfigs {
        create("release") {
            storeFile = releaseSigning.getValue("STORE_FILE").orNull?.let { file(it) }
            storePassword = releaseSigning.getValue("STORE_PASSWORD").orNull
            keyAlias = releaseSigning.getValue("KEY_ALIAS").orNull
            keyPassword = releaseSigning.getValue("KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets
        .getByName("main")
        .assets
        .directories
        .add(layout.buildDirectory.dir("generated/core-assets").get().asFile.path)
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    buildFeatures {
        compose = true
    }
}

tasks
    .matching { it.name == "validateSigningRelease" }
    .configureEach {
        doFirst {
            val missing = releaseSigning.filterValues { it.orNull.isNullOrBlank() }.keys
            check(missing.isEmpty()) {
                "Release signing requires: ${missing.joinToString { "MIEL_SIGNING_$it" }}"
            }
        }
    }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
