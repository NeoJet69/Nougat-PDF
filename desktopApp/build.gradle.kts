import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

// Les modeles de langue de l'OCR, installes avec l'app (dossier « resources » de l'application).
val ocrResources = layout.buildDirectory.dir("ocr-resources")
val copyTessdata = tasks.register<Sync>("copyTessdata") {
    dependsOn(":shared:downloadTessdata")
    from(project(":shared").layout.buildDirectory.dir("tessdata"))
    into(ocrResources.map { it.dir("common/tessdata") })
}
tasks.configureEach {
    if (name == "prepareAppResources") dependsOn(copyTessdata)
}

compose.desktop {
    application {
        mainClass = "app.nougat.pdf.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Nougat PDF"
            // Version croissante a chaque compilation GitHub : l'installateur remplace l'ancienne version.
            packageVersion = "1.0." + providers.gradleProperty("nougat.versionCode").get()
            description = "Lecteur et editeur de PDF, gratuit et sans pub"
            vendor = "NeoJet, Nougat et Caramel"
            // Modules Java utilises par PDFBox et l'interface.
            modules("java.desktop", "java.logging", "java.management", "java.naming", "java.xml", "jdk.unsupported", "jdk.httpserver")
            appResourcesRootDir.set(ocrResources)

            windows {
                menu = true
                menuGroup = "Nougat PDF"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                // Identifiant fixe : une nouvelle version remplace l'ancienne au lieu de s'installer a cote.
                upgradeUuid = "5b8a3c1e-6f2d-4b7a-9e41-0c7d2a9f1b63"
                iconFile.set(project.file("icons/nougat.ico"))
                fileAssociation(
                    mimeType = "application/pdf",
                    extension = "pdf",
                    description = "Document PDF",
                    iconFile = project.file("icons/nougat.ico"),
                )
            }
            linux {
                iconFile.set(project.file("icons/nougat.png"))
            }
        }
    }
}
