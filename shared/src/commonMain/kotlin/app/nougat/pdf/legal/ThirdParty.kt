package app.nougat.pdf.legal

/*
 * Ce que l'app embarque reellement (verifie dans l'installateur Windows et dans l'APK), avec la
 * licence de chaque composant et ses avis obligatoires (fichiers NOTICE des licences Apache,
 * mentions de copyright des licences BSD / MIT...). Affiche dans « A propos → Licences ».
 * Les noms et les textes juridiques restent en anglais, leur langue officielle.
 */

/** Ou le composant est livre. */
enum class Shipped { Everywhere, Windows, Android }

/**
 * Un composant tiers. [texts] : les avis a reproduire (NOTICE, licence BSD / MIT avec ses
 * copyrights...) ; pour une licence Apache 2.0 sans NOTICE, le texte complet suffit (en bas).
 */
class ThirdParty(
    val name: String,
    val license: String,
    val holders: String,
    val url: String,
    val shipped: Shipped = Shipped.Everywhere,
    val details: String? = null,
    val texts: List<String> = emptyList(),
)

/** Un texte de licence complet, montre une seule fois en bas de l'ecran. */
class FullLicense(val title: String, val text: String)

object Licenses {
    const val NOUGAT_LICENSE = "MIT"

    val nougat: String get() = LicenseTexts.NOUGAT_MIT

    val components: List<ThirdParty> = listOf(
        // ---- Partout ----------------------------------------------------------------------------
        ThirdParty(
            "Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx.datetime, atomicfu",
            "Apache-2.0", "JetBrains s.r.o. and Kotlin Programming Language contributors",
            "https://kotlinlang.org",
        ),
        ThirdParty(
            "Compose Multiplatform, Jetpack Compose, Material 3, Material Icons",
            "Apache-2.0", "JetBrains s.r.o.; The Android Open Source Project; Google LLC",
            "https://www.jetbrains.com/compose-multiplatform/",
            details = "Some icons of the app are drawn from Material Icons (Google, Apache-2.0).",
        ),
        ThirdParty(
            "AndroidX (activity, annotation, collection, core, lifecycle, savedstate, …)",
            "Apache-2.0", "The Android Open Source Project", "https://developer.android.com/jetpack/androidx",
        ),
        ThirdParty(
            "Anthropic Java SDK 3.3.0",
            "Apache-2.0 (MIT for versions before 3.0.0)", "Anthropic, PBC",
            "https://github.com/anthropics/anthropic-sdk-java",
            texts = listOf(LicenseTexts.ANTHROPIC_MIT),
        ),
        ThirdParty(
            "ZXing core 3.5.4", "Apache-2.0", "ZXing authors", "https://github.com/zxing/zxing",
            details = "Reads and writes the QR codes of Swiss QR-bills.",
        ),
        ThirdParty(
            "OkHttp, Okio", "Apache-2.0", "Square, Inc.", "https://square.github.io/okhttp/",
            details = "OkHttp includes the Public Suffix List (Mozilla Public License 2.0).",
            texts = listOf(LicenseTexts.PUBLIC_SUFFIX_NOTICE),
        ),
        ThirdParty(
            "Jackson (core, databind, annotations, datatypes, Kotlin module)",
            "Apache-2.0", "Tatu Saloranta and FasterXML contributors", "https://github.com/FasterXML/jackson",
            details = "jackson-core includes FastDoubleParser (MIT), fast_double_parser (BSL-1.0), " +
                "fast_float (MIT) and bigint (BSD-2-Clause).",
            texts = listOf(LicenseTexts.JACKSON_NOTICE),
        ),
        ThirdParty(
            "ClassMate", "Apache-2.0", "Tatu Saloranta", "https://github.com/FasterXML/java-classmate",
            texts = listOf(LicenseTexts.CLASSMATE_NOTICE),
        ),
        ThirdParty(
            "Swagger Annotations", "Apache-2.0", "SmartBear Software Inc.", "https://github.com/swagger-api/swagger-core",
            texts = listOf(LicenseTexts.SWAGGER_NOTICE),
        ),
        ThirdParty(
            "victools jsonschema-generator", "Apache-2.0", "VicTools", "https://github.com/victools/jsonschema-generator",
        ),
        ThirdParty(
            "OpenTelemetry API", "Apache-2.0", "The OpenTelemetry Authors", "https://opentelemetry.io",
        ),
        ThirdParty(
            "Standard Webhooks", "Apache-2.0", "Standard Webhooks contributors", "https://www.standardwebhooks.com",
        ),
        ThirdParty(
            "JSpecify, Error Prone annotations, JetBrains annotations",
            "Apache-2.0", "The JSpecify Authors; Google LLC; JetBrains s.r.o.", "https://jspecify.dev",
        ),

        // ---- Windows ----------------------------------------------------------------------------
        ThirdParty(
            "Apache PDFBox 3.0.8 (pdfbox, fontbox, pdfbox-io)", "Apache-2.0",
            "The Apache Software Foundation", "https://pdfbox.apache.org", Shipped.Windows,
            details = "Includes the Liberation Sans font (SIL Open Font License 1.1), the Adobe Glyph List, " +
                "Adobe AFM files and CMaps, and parts of TwelveMonkeys (see below).",
            texts = listOf(LicenseTexts.PDFBOX_NOTICE, LicenseTexts.PDFBOX_EXTERNAL),
        ),
        ThirdParty(
            "Apache Commons Logging", "Apache-2.0", "The Apache Software Foundation",
            "https://commons.apache.org/logging/", Shipped.Windows,
            texts = listOf(LicenseTexts.COMMONS_LOGGING_NOTICE),
        ),
        ThirdParty(
            "SLF4J API", "MIT", "QOS.ch Sarl", "https://www.slf4j.org", Shipped.Windows,
            texts = listOf(LicenseTexts.SLF4J),
        ),
        ThirdParty(
            "Tesseract OCR (5.5.3 on Windows, 5.5.1 on Android)", "Apache-2.0",
            "Tesseract OCR contributors (Google Inc. and others)",
            "https://github.com/tesseract-ocr/tesseract",
            details = "The invisible text layer uses a font adapted from Tesseract's glyphless font (pdf.ttf).",
        ),
        ThirdParty(
            "Tesseract language models (tessdata_best, 16 languages)", "Apache-2.0",
            "Tesseract OCR contributors (Google Inc. and others)",
            "https://github.com/tesseract-ocr/tessdata_best",
            details = "Integer versions published by the tesseract.js-data project (https://github.com/naptha/tessdata). " +
                "On Android, French and English are included and the other languages are downloaded on demand.",
        ),
        ThirdParty(
            "Leptonica (1.87.0 on Windows, 1.85.0 on Android)", "BSD-2-Clause", "Leptonica", "http://www.leptonica.org",
            texts = listOf(LicenseTexts.LEPTONICA),
        ),
        ThirdParty(
            "zlib 1.3.2 (inside Leptonica)", "Zlib", "Jean-loup Gailly and Mark Adler", "https://zlib.net",
            Shipped.Windows, texts = listOf(LicenseTexts.ZLIB),
        ),
        ThirdParty(
            "libpng (1.6.53 on Windows, 1.6.48 on Android)", "libpng-2.0", "The PNG Reference Library Authors; Cosmin Truta; Glenn Randers-Pehrson",
            "http://www.libpng.org", texts = listOf(LicenseTexts.LIBPNG),
        ),
        ThirdParty(
            "libjpeg-turbo 3.0.1 (inside Leptonica)", "IJG AND BSD-3-Clause AND Zlib",
            "D. R. Commander and contributors; Thomas G. Lane, Guido Vollbeding (IJG)",
            "https://libjpeg-turbo.org", Shipped.Windows,
            details = "This software is based in part on the work of the Independent JPEG Group.",
            texts = listOf(LicenseTexts.LIBJPEG_TURBO),
        ),
        ThirdParty(
            "LibTIFF 4.7.1 (inside Leptonica)", "libtiff", "Sam Leffler; Silicon Graphics, Inc.",
            "https://libtiff.gitlab.io/libtiff/", Shipped.Windows, texts = listOf(LicenseTexts.LIBTIFF),
        ),
        ThirdParty(
            "libwebp (inside Leptonica)", "BSD-3-Clause", "Google Inc.", "https://chromium.googlesource.com/webm/libwebp",
            Shipped.Windows, texts = listOf(LicenseTexts.LIBWEBP),
        ),
        ThirdParty(
            "OpenJPEG 2.5.4 (inside Leptonica)", "BSD-2-Clause", "Université catholique de Louvain (UCL) and contributors",
            "https://www.openjpeg.org", Shipped.Windows, texts = listOf(LicenseTexts.OPENJPEG),
        ),
        ThirdParty(
            "GIFLIB (inside Leptonica)", "MIT", "Eric S. Raymond; Gershon Elber; Toshio Kuratomi",
            "https://giflib.sourceforge.net", Shipped.Windows, texts = listOf(LicenseTexts.GIFLIB),
        ),
        ThirdParty(
            "JavaCPP, JavaCPP Presets 1.5.14", "Apache-2.0 (or GPL-2.0-or-later with Classpath Exception)",
            "Samuel Audet", "https://github.com/bytedeco/javacpp", Shipped.Windows,
            texts = listOf(LicenseTexts.JAVACPP),
        ),
        ThirdParty(
            "GCC runtime libraries (libgcc, libstdc++, libgomp)",
            "GPL-3.0-or-later WITH GCC-exception-3.1", "Free Software Foundation, Inc.",
            "https://gcc.gnu.org", Shipped.Windows,
            details = "Shipped with Leptonica. The GCC Runtime Library Exception lets them be combined with " +
                "software under any license. GPL-3.0 text: https://www.gnu.org/licenses/gpl-3.0.html — " +
                "source code: https://gcc.gnu.org",
            texts = listOf(LicenseTexts.GCC_RLE),
        ),
        ThirdParty(
            "mingw-w64 winpthreads", "MIT AND BSD-3-Clause", "mingw-w64 project; Lockless Inc.",
            "https://www.mingw-w64.org", Shipped.Windows, texts = listOf(LicenseTexts.WINPTHREADS),
        ),
        ThirdParty(
            "Microsoft Visual C++ Runtime (vcruntime140, msvcp140, ucrtbase, …)",
            "Microsoft Visual C++ Redistributable license terms", "Microsoft Corporation",
            "https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist", Shipped.Windows,
            details = "Not open source: redistributable files provided by Microsoft, shipped with the Java " +
                "runtime and with JavaCPP. They remain under Microsoft's terms, not under the MIT license.",
        ),
        ThirdParty(
            "Skiko, Skia", "Apache-2.0 (Skiko), BSD-3-Clause (Skia)", "JetBrains s.r.o.; Google Inc.",
            "https://github.com/JetBrains/skiko", Shipped.Windows, texts = listOf(LicenseTexts.SKIA),
        ),
        ThirdParty(
            "Java runtime (OpenJDK 21, Eclipse Temurin)", "GPL-2.0 WITH Classpath-exception-2.0",
            "Oracle and/or its affiliates, and the OpenJDK contributors", "https://adoptium.net", Shipped.Windows,
            details = "The full license texts of the runtime and of its own components are installed with the app, " +
                "in the folder runtime\\legal. Source code: https://github.com/adoptium/jdk21u",
        ),

        // ---- Android ----------------------------------------------------------------------------
        ThirdParty(
            "PdfBox-Android 2.0.27.0", "Apache-2.0", "Tom Roush; The Apache Software Foundation",
            "https://github.com/TomRoush/PdfBox-Android", Shipped.Android,
            details = "Includes the Adobe Glyph List, Adobe AFM files and CMaps (see below).",
            texts = listOf(LicenseTexts.PDFBOX_ANDROID_NOTICE, LicenseTexts.PDFBOX_ANDROID_EXTERNAL),
        ),
        ThirdParty(
            "Bouncy Castle", "MIT", "The Legion of the Bouncy Castle Inc.", "https://www.bouncycastle.org",
            Shipped.Android, texts = listOf(LicenseTexts.BOUNCY_CASTLE),
        ),
        ThirdParty(
            "Tesseract4Android 4.9.0", "Apache-2.0", "Adaptech s.r.o., Robert Pösel; Robert Theis; Google Inc.",
            "https://github.com/adaptech-cz/Tesseract4Android", Shipped.Android,
            details = "Builds Tesseract, Leptonica, libjpeg and libpng for Android.",
        ),
        ThirdParty(
            "libjpeg 9f (inside Tesseract4Android)", "IJG", "Thomas G. Lane, Guido Vollbeding (Independent JPEG Group)",
            "https://ijg.org", Shipped.Android,
            details = "This software is based in part on the work of the Independent JPEG Group.",
            texts = listOf(LicenseTexts.IJG),
        ),
        ThirdParty(
            "Google ML Kit (GenAI Prompt API, Document Scanner, Translation, Language ID)", "ML Kit Terms of Service (not open source)", "Google LLC",
            "https://developers.google.com/ml-kit/terms", Shipped.Android,
            details = "Closed-source libraries from Google. The Prompt API asks Gemini Nano (the AI built into compatible " +
                "phones, provided by Android's AICore) to proofread doubtful OCR lines and to polish translations; the " +
                "Document Scanner (Google Play services) scans pages with the camera; Translation and Language ID translate " +
                "documents on the phone and recognize their language (each translation model, about 30 MB, is downloaded " +
                "from Google once). The Gemini Nano model and the scanner itself are provided by the system, not by this app. " +
                "Subject to Google's terms and Generative AI Prohibited Use Policy, not to the MIT license.",
        ),
    )

    /** Les composants livres sur cette plateforme. */
    fun components(desktop: Boolean): List<ThirdParty> = components.filter {
        it.shipped == Shipped.Everywhere || it.shipped == (if (desktop) Shipped.Windows else Shipped.Android)
    }

    /** Textes complets, en bas de l'ecran. */
    val fullTexts: List<FullLicense> = listOf(
        FullLicense("Apache License 2.0", LicenseTexts.APACHE_2_0),
    )
}
