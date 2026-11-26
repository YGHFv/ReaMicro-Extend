buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val miuixVersion = libs.versions.miuix.get()

subprojects {
    if (name.startsWith("miuix-")) {
        version = miuixVersion
    }
}
