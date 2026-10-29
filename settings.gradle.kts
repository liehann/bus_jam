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
        google()
        mavenCentral()
        maven("https://maven.gameanalytics.com/release") {
            content { includeGroup("com.gameanalytics.sdk") }
        }
    }
}
rootProject.name = "bus_jam"
include(":core", ":levelgen", ":app")
