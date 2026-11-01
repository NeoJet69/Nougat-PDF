plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val desktopOnly = providers.gradleProperty("nougat.desktopOnly").orNull == "true"

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            implementation(libs.compose.material.icons.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.pdfbox)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

// Cible Android : ajoutee seulement quand le SDK Android est disponible.
// La configuration passe par des noms (et non des types) pour que ce fichier
// compile aussi sans le plugin Android.
if (!desktopOnly) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    kotlin {
        targets.getByName("android").withGroovyBuilder {
            setProperty("namespace", "app.nougat.pdf.shared")
            setProperty("compileSdk", 36)
            setProperty("minSdk", 26)
        }
        sourceSets.getByName("androidMain").dependencies {
            implementation(libs.pdfbox.android)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}
