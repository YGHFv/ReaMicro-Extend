buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

val miuixVersion = libs.versions.miuix.get()

subprojects {
    if (name.startsWith("miuix-")) {
        version = miuixVersion
    }
}
