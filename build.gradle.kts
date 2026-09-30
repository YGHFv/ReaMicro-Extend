// AGP 9 自带内置 Kotlin（编译器随 AGP 分发），但版本落后于 miuix 0.9.4 的编译版本。
// miuix-ui-android 0.9.4 由 Kotlin 2.4.20 编出，把内置 Kotlin 连同 Compose 编译器一起抬到同一版本，
// 否则 Compose 编译器插件（版本必须与 Kotlin 编译器一致）和调用 miuix 时都会不匹配。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
