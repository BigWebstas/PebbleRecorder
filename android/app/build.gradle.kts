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
        versionCode = 9
        versionName = "0.1.8"
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

// Keeps assets/watch.pbw (bundled for the in-app "Install watchapp on watch" sideload button) in
// sync with the sibling watch/ project's latest build output. Best-effort: if the watch app
// hasn't been built (no watch/build/watch.pbw yet), this leaves whatever's already committed to
// assets/ alone rather than failing the Android build.
val syncWatchAppAsset = tasks.register<Copy>("syncWatchAppAsset") {
    val source = rootProject.file("../watch/build/watch.pbw")
    onlyIf { source.exists() }
    from(source)
    into("src/main/assets")
}
tasks.named("preBuild") {
    dependsOn(syncWatchAppAsset)
}
