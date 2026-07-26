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
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.TeamNewPipe")
                includeGroup("com.github.teamnewpipe")
            }
        }
    }
}

rootProject.name = "Plyvanta"
include(":app")
