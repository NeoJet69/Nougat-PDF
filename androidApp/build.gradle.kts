plugins {
    // Le plugin Android est charge par le build.gradle.kts racine (Kotlin y est integre).
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

// Cle de signature : fournie par GitHub Actions (secrets NOUGAT_*), voir README.
// Sans elle, l'APK est signe avec la cle de debug (il faut alors desinstaller avant chaque mise a jour).
val keystoreFile = providers.environmentVariable("NOUGAT_KEYSTORE_FILE").orNull?.let { file(it) }?.takeIf { it.isFile }

// Numero de version : celui de la compilation GitHub, pour que chaque APK remplace le precedent.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1

android {
    namespace = "app.nougat.pdf.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.nougat.pdf"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("nougat") {
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("NOUGAT_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("NOUGAT_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("NOUGAT_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("nougat") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
}
