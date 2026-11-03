import java.net.URI
import java.util.zip.GZIPInputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val desktopOnly = providers.gradleProperty("nougat.desktopOnly").orNull == "true"

/** Bibliotheques natives de Tesseract pour une plateforme (« windows-x86_64 », « linux-x86_64 »). */
fun natives(platform: String) = listOf(
    "org.bytedeco:tesseract:${libs.versions.tesseract.get()}:$platform",
    "org.bytedeco:leptonica:${libs.versions.leptonica.get()}:$platform",
    "org.bytedeco:javacpp:${libs.versions.javacpp.get()}:$platform",
)

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
        getByName("desktopTest").dependencies {
            // Bibliotheque graphique native de Compose, pour rendre des pages pendant les tests.
            implementation(compose.desktop.currentOs)
            // Tesseract pour Linux : les tests tournent sur Linux.
            for (n in natives("linux-x86_64")) runtimeOnly(n)
        }
        getByName("desktopMain") {
            // Code commun a l'ordinateur et a Android (Java/JVM) : l'assistant Claude.
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.pdfbox)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.anthropic.java)
                // OCR : Tesseract 5 (bibliotheque native) ; celle de Windows pour l'app.
                implementation(libs.tesseract)
                implementation(libs.leptonica)
                implementation(libs.javacpp)
                for (n in natives("windows-x86_64")) runtimeOnly(n)
            }
        }
    }
}

// ---- OCR : modeles de langue de Tesseract (tessdata_best, version entiere) --------------------------

/** Les langues de reconnaissance fournies avec l'app (voir OCR_LANGUAGES). */
val ocrLanguages = listOf(
    "fra", "eng", "deu", "ita", "spa", "por", "nld", "pol",
    "tur", "rus", "ukr", "ara", "chi_sim", "swe", "ell", "ron",
)
val tessdataDir = layout.buildDirectory.dir("tessdata")

/**
 * Telecharge les modeles depuis les paquets npm « @tesseract.js-data » (tessdata_best convertis
 * en entiers : la precision des meilleurs modeles, en plus leger). Une seule fois : les fichiers
 * deja presents sont gardes.
 */
val downloadTessdata = tasks.register("downloadTessdata") {
    inputs.property("languages", ocrLanguages)
    outputs.dir(tessdataDir)
    doLast {
        val dir = tessdataDir.get().asFile.apply { mkdirs() }
        for (lang in ocrLanguages) {
            val target = File(dir, "$lang.traineddata")
            if (target.length() > 0) continue
            val url = "https://registry.npmjs.org/@tesseract.js-data/$lang/-/$lang-1.0.0.tgz"
            val tar = GZIPInputStream(URI(url).toURL().openStream()).use { it.readBytes() }
            val wanted = "package/4.0.0_best_int/$lang.traineddata.gz"
            var pos = 0
            var found = false
            while (pos + 512 <= tar.size && !found) {
                val name = String(tar, pos, 100, Charsets.US_ASCII).substringBefore('\u0000')
                if (name.isEmpty()) break
                val size = String(tar, pos + 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLong(8).toInt()
                if (name == wanted) {
                    val gz = tar.copyOfRange(pos + 512, pos + 512 + size)
                    target.writeBytes(GZIPInputStream(gz.inputStream()).use { it.readBytes() })
                    found = true
                }
                pos += 512 + ((size + 511) / 512) * 512
            }
            check(found) { "Modele de langue $lang introuvable dans $url" }
        }
    }
}

tasks.withType<Test>().configureEach {
    if (name == "desktopTest") {
        dependsOn(downloadTessdata)
        systemProperty("nougat.tessdata", tessdataDir.get().asFile.absolutePath)
        maxHeapSize = "2g"
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
            setProperty("compileSdk", 37)
            setProperty("minSdk", 26)
        }
        sourceSets.getByName("androidMain") {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.pdfbox.android)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.anthropic.java)
                // OCR : Tesseract 5 pour Android, et Gemini Nano (IA de l'appareil) pour relire.
                implementation(libs.tesseract4android)
                implementation(libs.mlkit.genai.prompt)
            }
        }
    }
}
