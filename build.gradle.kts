// Le plugin Android n'est charge que si l'on construit aussi la version Android
// (voir nougat.desktopOnly dans gradle.properties).
buildscript {
    val desktopOnly = providers.gradleProperty("nougat.desktopOnly").orNull == "true"
    if (!desktopOnly) {
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
        dependencies {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}
