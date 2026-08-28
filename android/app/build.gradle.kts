import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.pebblerecorder.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.pebblerecorder.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "0.1.12"
    }

    // AGP embeds a "Dependency metadata" signing block by default (Play Console integrity
    // checks, irrelevant since this app isn't distributed there) - F-Droid's binary scanner
    // rejects any extra signing block in a reproducible-build reference APK, so turn it off.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // F-Droid's build server only builds this android/ module - it has no Pebble SDK toolchain,
    // so it can't reproduce watch/build/watch.pbw from source. The "fdroid" flavor ships without
    // that bundled binary (see the syncWatchAppAsset task below); MainActivity hides the "Install
    // the app on Pebble" sideload button when it's absent (bool/has_bundled_watchapp). The
    // "github" flavor keeps today's behavior for the artifacts published on the Releases page.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("fdroid") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.pebblekit.client)
    implementation(libs.kotlinx.coroutines.android)
}

// Keeps the "github" flavor's bundled assets/watch.pbw (used by the in-app "Install the app on
// Pebble" sideload button) in sync with the sibling watch/ project's latest build output.
// Best-effort: if the watch app hasn't been built (no watch/build/watch.pbw yet), this leaves
// whatever's already committed to src/github/assets/ alone rather than failing the Android build.
val syncWatchAppAsset = tasks.register<Copy>("syncWatchAppAsset") {
    val source = rootProject.file("../watch/build/watch.pbw")
    onlyIf { source.exists() }
    from(source)
    into("src/github/assets")
}
tasks.named("preBuild") {
    dependsOn(syncWatchAppAsset)
}
