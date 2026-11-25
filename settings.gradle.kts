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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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

rootProject.name = "VaultAnything"

// App module: navigation, DI entry point, theme host.
include(":app")

// Core modules. Dependency direction: feature -> core:ui / core:domain / core:data, core:data -> core:domain.
include(":core:domain") // Pure Kotlin: models and repository interfaces
include(":core:data")   // Firebase implementations of the repositories
include(":core:ui")     // Theme and shared Compose components

// Feature modules. Features never depend on each other.
include(":feature:auth")      // Google and email sign-in, the settings page
include(":feature:family")    // Family spaces, members with their roles, invites
include(":feature:documents") // One person's documents: list, details, add and edit, open, share, print
