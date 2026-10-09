pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "fermix-android"

include(":app")
include(":core-noise")
include(":core-protocol")
include(":core-transport")
include(":attest")
include(":core-session")
include(":demo-daemon")
include(":design")
include(":data")
include(":feature-onboarding")
include(":feature-instance")
include(":feature-chats")
include(":feature-chat")
include(":push")
