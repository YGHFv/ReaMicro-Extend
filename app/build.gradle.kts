import org.apache.tools.ant.filters.FixCrLfFilter
import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties

plugins {
    id("dev.detekt")
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
    inputs.property("rootModuleVersionCode", rootModuleVersionCode)
    outputs.dir(destinationDirectory).withPropertyName("rootModuleAssets")
    outputs.upToDateWhen {
        destinationDirectory.get().asFile.listFiles().orEmpty().none {
            it.name.startsWith("ReaMicro-Automation-Root-") && it.extension == "zip" &&
                it.name != archiveFileName.get()
        }
    }

    doFirst {
        val moduleVersions = rootModulePropText.lineSequence()
            .filter { it.startsWith("versionCode=") }.map { it.substringAfter('=') }.toList()
        check(rootModuleVersionCode > 0 && moduleVersions == listOf(rootModuleVersionCode.toString())) {
            "Root module.prop 必须包含唯一的正整数 versionCode，且不能包含前导零"
        }
        val runtimeVersions = rootModuleSourceDir.file("common.sh").asFile.readLines(Charsets.UTF_8)
            .filter { it.startsWith("REAMICRO_MODULE_VERSION=") }
            .map { it.substringAfter('=') }
        check(runtimeVersions == listOf(rootModuleVersionCode.toString())) {
            "Root 脚本与模块版本不匹配：module.prop versionCode=$rootModuleVersionCode，" +
                "common.sh REAMICRO_MODULE_VERSION=$runtimeVersions；请同步版本后重新构建"
        }
        val wakeHelper = rootModuleSourceDir.file("bin/wake-timer").asFile
        check(wakeHelper.isFile && wakeHelper.length() > 0) {
            "Root wake helper missing: module/reamicro-automation/bin/wake-timer"
        }
        val header = wakeHelper.inputStream().use { it.readNBytes(20) }
        check(header.size == 20 &&
            header.take(6) == listOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).map { it.toByte() } &&
            header[18] == 0xb7.toByte() && header[19] == 0.toByte()) {
            "Root wake helper must be a little-endian arm64 ELF executable"
        }
        destinationDirectory.get().asFile.listFiles()?.filter {
            it.name.startsWith("ReaMicro-Automation-Root-") && it.extension == "zip" &&
                it.name != archiveFileName.get()
        }?.forEach { check(it.delete()) { "Cannot remove stale generated Root module: $it" } }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dirPermissions { unix("755") }

    from(rootModuleSourceDir) {
        include("*.sh", "META-INF/com/google/android/update-binary")
        filteringCharset = "UTF-8"
        filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        filePermissions { unix("755") }
    }
    from(rootModuleSourceDir) {
        include("bin/wake-timer")
        filePermissions { unix("755") }
    }
    from(rootModuleSourceDir) {
        include("module.prop", "META-INF/com/google/android/updater-script")
        filteringCharset = "UTF-8"
        filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        filePermissions { unix("644") }
    }
}

android {
    namespace = "com.reamicro.fix"

    // Miuix beta02 uses Compose 1.13, whose Android artifacts require API 37.1.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    androidResources {
        // Module OkHttp requests use NO_COOKIES and never query topPrivateDomain.
        ignoreAssetsPatterns += "PublicSuffixDatabase.list"
        // 仅保留中英文资源及对应地区变体。
        localeFilters += listOf(
            "en", "en-rAU", "en-rCA", "en-rGB", "en-rIN", "en-rXC",
            "zh", "zh-rCN", "zh-rHK", "zh-rTW",
        )
    }

    defaultConfig {
        applicationId = "com.reamicro.fix"
        ndk { abiFilters += "arm64-v8a" }
        minSdk = 26
        targetSdk = 37
        versionCode = 83
        versionName = "2.3.21"
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
    buildUponDefaultConfig.set(false)
    allRules.set(false)
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/java"))
    basePath.set(rootProject.layout.projectDirectory)
    // Findings remain informational; configuration and execution errors must fail.
    ignoreFailures.set(false)
    failOnSeverity.set(dev.detekt.gradle.extensions.FailOnSeverity.Never)
    parallel.set(true)
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    jvmTarget.set(JavaVersion.VERSION_17.toString())
    reports {
        html.required.set(true)
        markdown.required.set(true)
        checkstyle.required.set(false)
        sarif.required.set(false)
    }
}

androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        if (name == "detekt$variantName" || (name == "detekt" && variant.name == "debug")) {
            // AGP built-in Kotlin's classpath omits local Java sources and generated BuildConfig.
            classpath.setFrom(
                tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compile${variantName}Kotlin")
                    .map { it.libraries },
                tasks.named<JavaCompile>("compile${variantName}JavaWithJavac")
                    .flatMap { it.destinationDirectory },
            )
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation(project(":scripta-editor"))
    implementation("com.google.re2j:re2j:1.8")

    implementation("androidx.activity:activity-compose:1.13.0")
    // The app's event relay uses the same public dispatcher API as the vendored Miuix modules.
    implementation("androidx.navigationevent:navigationevent-compose:1.2.0")
    implementation(project(":miuix-ui"))
    implementation(project(":miuix-preference"))
    implementation(project(":miuix-icons"))
    implementation(project(":miuix-material"))
    implementation(project(":miuix-glass"))
    implementation(project(":miuix-glass-icons"))
    implementation(project(":miuix-nav"))

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")

    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")

    // 从当前宿主 DEX 查询语义契约。
    implementation("org.luckypray:dexkit:2.3.0")
    // Resolve record properties from StringBuilder register flow, not field order.
    implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
    implementation("com.highcapable.kavaref:kavaref-core:1.1.1")
    implementation("com.highcapable.kavaref:kavaref-android:1.1.1")

}
