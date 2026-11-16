plugins {
    id("com.android.library")
}

android {
    namespace = "com.reamicro.legado.parser"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    api("org.mozilla:rhino:1.8.1")
    api("com.google.code.gson:gson:2.14.0")
    api("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.collection:collection:1.6.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jsoup:jsoup:1.16.2")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.5")
    implementation("com.jayway.jsonpath:json-path:3.0.0")
    implementation("org.apache.commons:commons-text:1.15.0")
    implementation("cn.hutool:hutool-crypto:5.8.22")
    implementation("com.github.bumptech.glide:glide:5.0.9")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("me.zhanghai.android.libarchive:library:1.1.6")
}
