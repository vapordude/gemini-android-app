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
    }
}

rootProject.name = "Kaimahi"

include(":app")
include(":core-bridge")
include(":domain")
include(":ui-components")
include(":inference-bridge")
include(":agent-bridge")
include(":emdash-bridge")
include(":native-driver")
