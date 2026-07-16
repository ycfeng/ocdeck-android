pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("frpc-stcp-visitor-go/build/repo") }
        google()
        mavenCentral()
    }
}

rootProject.name = "ocdeck-android"
include(":app")
include(":frpc-stcp-visitor")
