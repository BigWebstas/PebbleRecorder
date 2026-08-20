buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9's built-in Kotlin support bundles KGP 2.2.10 by default, which can't read
        // metadata from libraries (e.g. pebblekit2) compiled with a newer Kotlin. Bump it.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
