import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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

val rootModuleSourceDir = rootProject.layout.projectDirectory.dir("module/reamicro-automation")
val rootModulePropText: String = rootModuleSourceDir.file("module.prop").asFile.readText(Charsets.UTF_8)
val rootModuleVersion: String = Regex("^version=(.+)$", RegexOption.MULTILINE)
    .find(rootModulePropText)
    ?.groupValues?.get(1)?.trim()
    .orEmpty()
    .ifBlank { error("module/reamicro-automation/module.prop 缺少 version，无法生成内置通用模块包") }

val rootModuleVersionCode: Int = Regex("^versionCode=(\\d+)$", RegexOption.MULTILINE)
    .find(rootModulePropText)
    ?.groupValues?.get(1)?.trim()?.toIntOrNull()
    ?: error("module/reamicro-automation/module.prop 缺少合法 versionCode")
val generatedRootModuleDir = layout.buildDirectory.dir("generated/reamicroModule")
val generatedRootModuleRoot = generatedRootModuleDir.get().asFile
val bundleModule by tasks.registering(Zip::class) {
    archiveFileName.set("ReaMicro-Automation-Root-$rootModuleVersion.zip")
    destinationDirectory.set(generatedRootModuleDir.map { it.dir("module") })

    doFirst {
        destinationDirectory.get().asFile.listFiles()?.filter {
            it.name.startsWith("ReaMicro-Automation-Root-") && it.extension == "zip" &&
                it.name != archiveFileName.get()
        }?.forEach { check(it.delete()) { "Cannot remove stale generated Root module: $it" } }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(rootModuleSourceDir) {
        include("*.sh")
        filePermissions { unix("755") }
    }
    from(rootModuleSourceDir) {
        include("META-INF/com/google/android/update-binary")
        filePermissions { unix("755") }
    }
    from(rootModuleSourceDir) {
        include("META-INF/com/google/android/updater-script")
        filePermissions { unix("644") }
    }
    from(rootModuleSourceDir) {
        include("module.prop")
        filePermissions { unix("644") }
    }
}

android {
    namespace = "com.reamicro.fix"

    compileSdk = 37

    defaultConfig {
        applicationId = "com.reamicro.fix"
        ndk { abiFilters += "arm64-v8a" }
        minSdk = 26
        targetSdk = 35
        versionCode = 71
        versionName = "2.3.9"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true

        compose = true
    }

    buildTypes.configureEach {
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        buildConfigField("int", "ROOT_MODULE_VERSION_CODE", "$rootModuleVersionCode")
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
            signingConfig = signingConfigs.getByName("debug")
        }
        release {

            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }
    sourceSets["main"].assets.srcDir(generatedBundledSourcesRoot)
    sourceSets["main"].assets.srcDir(generatedRootModuleRoot)
}

tasks.matching { task ->
    task.name.startsWith("merge", ignoreCase = false) && task.name.endsWith("Assets", ignoreCase = false)
}.configureEach {
    dependsOn(syncBundledSources)
    dependsOn(bundleModule)
}

tasks.matching { task -> task.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(syncBundledSources)
    dependsOn(bundleModule)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/java"))
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
    implementation(project(":scripta-editor"))
    implementation("com.google.re2j:re2j:1.8")

    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("io.github.proify.lyricon:provider:0.1.70")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")

    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    compileOnly("io.github.libxposed:api:102.0.0")

}