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

// 配套的 KSU 模块 ZIP 打进 APK 的 assets：用户点「使用 KSU」时由应用自己释放并用 ksud 安装，
// 不再需要去 GitHub Releases 手动下载（CI 仍会单独产出同一份 ZIP 供手动刷入）。
val ksuModuleSourceDir = rootProject.layout.projectDirectory.dir("ksu/reamicro-automation")
val ksuModuleVersion: String = Regex("^version=(.+)$", RegexOption.MULTILINE)
    .find(ksuModuleSourceDir.file("module.prop").asFile.readText(Charsets.UTF_8))
    ?.groupValues?.get(1)?.trim()
    .orEmpty()
    .ifBlank { error("ksu/reamicro-automation/module.prop 缺少 version，无法生成内置 KSU 模块包") }
val generatedKsuModuleDir = layout.buildDirectory.dir("generated/reamicroKsuModule")
val generatedKsuModuleRoot = generatedKsuModuleDir.get().asFile
val bundleKsuModule by tasks.registering(Zip::class) {
    archiveFileName.set("ReaMicro-Automation-KSU-$ksuModuleVersion.zip")
    destinationDirectory.set(generatedKsuModuleDir.map { it.dir("ksu") })
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // 与 tools/build-ksu-module.py 同一份清单与权限：脚本 0755、module.prop 0644。
    from(ksuModuleSourceDir) {
        include("*.sh")
        filePermissions { unix("755") }
    }
    from(ksuModuleSourceDir) {
        include("module.prop")
        filePermissions { unix("644") }
    }
}

android {
    namespace = "com.reamicro.fix"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reamicro.fix"
        minSdk = 26
        targetSdk = 35
        versionCode = 64
        versionName = "2.3.3"
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
    sourceSets["main"].assets.srcDir(generatedKsuModuleRoot)
}

tasks.matching { task ->
    task.name.startsWith("merge", ignoreCase = false) && task.name.endsWith("Assets", ignoreCase = false)
}.configureEach {
    dependsOn(syncBundledSources)
    dependsOn(bundleKsuModule)
}

tasks.matching { task -> task.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(syncBundledSources)
    dependsOn(bundleKsuModule)
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
