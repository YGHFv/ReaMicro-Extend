import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Android-only compilation of unchanged upstream commonMain + androidMain sources.
android {
    namespace = "top.yukonga.scripta.editor"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDirs("src/commonMain/kotlin", "src/androidMain/kotlin")
    sourceSets["test"].java.srcDir("src/commonTest/kotlin")
}
val commonSources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
tasks.withType<KotlinCompile>().configureEach {
    if (!name.contains("Test")) {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xmulti-platform",
            "-Xexpect-actual-classes",
            "-Xcommon-sources=" + commonSources.files.sortedBy { it.path }.joinToString(",") { it.absolutePath },
        )
    }
}
dependencies {
    api("org.jetbrains.compose.foundation:foundation-android:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
    testImplementation("junit:junit:4.13.2")
}
