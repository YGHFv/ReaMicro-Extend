pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            content { includeGroup("dev.detekt") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}

rootProject.name = "阅微补全"
include(":app")

include(":scripta-editor")
project(":scripta-editor").projectDir = file("third_party/scripta/editor")
project(":scripta-editor").buildFileName = "reamicro.android.gradle"

// Android adapters compile the pinned upstream common and Android sources.
listOf("core", "material", "squircle", "ui", "preference", "icons", "blur", "glass", "glass-icons", "nav").forEach { module ->
    include(":miuix-$module")
    project(":miuix-$module").projectDir = file("third_party/miuix/android/miuix-$module")
}
