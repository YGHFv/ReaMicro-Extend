import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val bundledSourceFilesDir = rootProject.layout.projectDirectory.dir("source-files")
val generatedBundledSourcesDir = layout.buildDirectory.dir("generated/reamicroBundledSources")
val generatedBundledSourcesRoot = layout.buildDirectory.file("generated/reamicroBundledSources").get().asFile
val syncBundledSources by tasks.registering(Sync::class) {
    from(bundledSourceFilesDir) {
        include("*.rmsource")
    }
    into(generatedBundledSourcesDir.map { it.dir("reamicro_sources") })
}

android {
    namespace = "com.reamicro.fix"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reamicro.fix"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "1.1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(generatedBundledSourcesRoot)
}

tasks.matching { task ->
    task.name.startsWith("merge", ignoreCase = false) && task.name.endsWith("Assets", ignoreCase = false)
}.configureEach {
    dependsOn(syncBundledSources)
}

tasks.matching { task -> task.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(syncBundledSources)
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
