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

compose.desktop {
    application {
        mainClass = "app.nougat.pdf.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Nougat PDF"
            // Version croissante a chaque compilation GitHub : l'installateur remplace l'ancienne version.
            packageVersion = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
            description = "Lecteur et editeur de PDF, gratuit et hors ligne"
            vendor = "Nougat"
            // Modules Java utilises par PDFBox et l'interface.
            modules("java.desktop", "java.logging", "java.naming", "java.xml", "jdk.unsupported")

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
