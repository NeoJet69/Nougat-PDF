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
        // Seulement les processeurs des telephones (ARM) : les versions x86 (emulateurs, rares
        // Chromebooks) alourdiraient l'APK d'environ 15 Mo pour presque personne.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // Le SDK d'Anthropic utilise des API Java recentes : Android les recoit par « desugaring ».
        isCoreLibraryDesugaringEnabled = true
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
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

// OCR : le francais et l'anglais sont livres dans l'app (les autres langues se telechargent).
abstract class CopyOcrAssets : DefaultTask() {
    @get:InputDirectory
    abstract val models: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = output.get().asFile.resolve("tessdata")
        dir.deleteRecursively()
        dir.mkdirs()
        for (lang in listOf("fra", "eng")) {
            models.get().asFile.resolve("$lang.traineddata").copyTo(dir.resolve("$lang.traineddata"), overwrite = true)
        }
    }
}

val copyOcrAssets = tasks.register<CopyOcrAssets>("copyOcrAssets") {
    dependsOn(":shared:downloadTessdata")
    models.set(project(":shared").layout.buildDirectory.dir("tessdata"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyOcrAssets, CopyOcrAssets::output)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
