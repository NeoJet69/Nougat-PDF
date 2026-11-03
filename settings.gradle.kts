pluginManagement {
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

plugins {
    // Telecharge automatiquement le bon JDK si besoin.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
        // Tesseract pour Android (Tesseract4Android) n'est publie que sur JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "NougatPDF"

include(":shared")
include(":desktopApp")

// L'app Android a besoin du SDK Android. Sans lui (-Pnougat.desktopOnly=true),
// on ne construit que la version ordinateur.
val desktopOnly = providers.gradleProperty("nougat.desktopOnly").orNull == "true"
if (!desktopOnly) {
    include(":androidApp")
}
