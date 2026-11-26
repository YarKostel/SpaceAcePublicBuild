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

rootProject.name = "SpaceAce"

include(":core", ":desktop", ":android")

// Раскладка в духе Go: точки входа — cmd/, код игры — internal/. Имена проектов прежние (core:test, desktop:run).
project(":core").projectDir = file("internal")
project(":desktop").projectDir = file("cmd/desktop")
project(":android").projectDir = file("cmd/android")
