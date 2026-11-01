plugins {
    // Le plugin Android est charge par le build.gradle.kts racine (Kotlin y est integre).
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.nougat.pdf.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.nougat.pdf"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // App personnelle, installee a la main : signee avec la cle de debug.
            signingConfig = signingConfigs.getByName("debug")
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
