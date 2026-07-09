import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

val bundledSourceFilesDir = rootProject.layout.projectDirectory.dir("source-files")
val generatedBundledSourcesDir = layout.buildDirectory.dir("generated/reamicroBundledSources")
val generatedBundledSourcesRoot = layout.buildDirectory.file("generated/reamicroBundledSources").get().asFile
val localReleaseSecretsFile = rootProject.layout.projectDirectory.file("signing/reamicro-release-secrets.txt").asFile
val localReleaseSecrets = Properties().apply {
    if (localReleaseSecretsFile.isFile) {
        localReleaseSecretsFile.inputStream().use(::load)
    }
}

fun signingValue(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
        System.getenv(name)?.takeIf { it.isNotBlank() }
    } ?: names.firstNotNullOfOrNull { name ->
        localReleaseSecrets.getProperty(name)?.takeIf { it.isNotBlank() }
    }.orEmpty()

fun resolveProjectFile(path: String): File =
    File(path).takeIf { it.isAbsolute } ?: rootProject.file(path)

val releaseKeystorePath = signingValue("RELEASE_KEYSTORE_FILE", "REAMICRO_RELEASE_KEYSTORE_FILE")
    .ifBlank { "signing/reamicro-release.jks" }
val releaseKeystoreFile = resolveProjectFile(releaseKeystorePath)
val releaseKeystorePassword = signingValue("RELEASE_KEYSTORE_PASSWORD", "REAMICRO_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS", "REAMICRO_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "REAMICRO_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isNotBlank() } && releaseKeystoreFile.isFile
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
        versionCode = 38
        versionName = "1.3.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
    implementation("io.github.proify.lyricon:provider:0.1.70")

    compileOnly("io.github.libxposed:api:101.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
