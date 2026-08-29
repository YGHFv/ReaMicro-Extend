import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("io.gitlab.arturbosch.detekt")
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
        versionCode = 50
        versionName = "2.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes.configureEach {
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
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

// detekt 只做体积/复杂度基线度量，不参与构建成败判定。
// 用途：重构前后对比「单文件行数、单类成员数、超长方法数」是否收敛。
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/java", "src/test/java"))
    ignoreFailures = true
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = JavaVersion.VERSION_17.toString()
    reports {
        html.required.set(true)
        txt.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

dependencies {
    implementation("io.github.proify.lyricon:provider:0.1.70")

    compileOnly("io.github.libxposed:api:101.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
