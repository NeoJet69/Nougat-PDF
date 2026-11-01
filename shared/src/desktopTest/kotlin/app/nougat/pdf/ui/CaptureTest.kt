package app.nougat.pdf.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.nougat.pdf.assistant.AssistantEngine
import app.nougat.pdf.assistant.AssistantModels
import app.nougat.pdf.assistant.AssistantSettings
import app.nougat.pdf.assistant.AssistantToolbox
import app.nougat.pdf.assistant.ChatItem
import app.nougat.pdf.engine.DesktopPdfEngine
import app.nougat.pdf.engine.InkMark
import app.nougat.pdf.engine.NPoint
import app.nougat.pdf.engine.makeSampleForm
import app.nougat.pdf.viewer.DockMode
import app.nougat.pdf.viewer.PlatformFiles
import app.nougat.pdf.viewer.SavedFile
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.sin
import kotlin.test.Test

/**
 * Captures d'ecran de l'interface (sans fenetre), deposees dans build/captures.
 * La compilation GitHub les copie dans le dossier captures/ du depot.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CaptureTest {
    private val out = File("build/captures").apply { mkdirs() }
    private val dir = createTempDirectory("nougat-captures").toFile()

    private val sample: String by lazy { invoice() }
    private val formSample: String by lazy { makeSampleForm(File(dir, "inscription.pdf")) }

    @Test
    fun captures() {
        val desktop = PlatformInfo(isDesktop = true)
        val phone = PlatformInfo(isDesktop = false)
        shot("1-accueil", 1440, 900, 1f, desktop)
        shot("2-lecture", 1440, 900, 1f, desktop, withDocument = true)
        shot("3-editer", 1440, 900, 1f, desktop, withDocument = true) { it.tools.enter(DockMode.Edit) }
        shot("4-annoter-sombre", 1440, 900, 1f, desktop, dark = true, withDocument = true) { it.annotateDemo() }
        shot("5-claude", 1440, 900, 1f, desktop, withDocument = true) {
            it.saveAssistantSettings("sk-ant-demo", AssistantModels.DEFAULT)
            it.assistant.open = true
            it.assistant.items.addAll(
                listOf(
                    ChatItem.User("Change le nom du client en Marie Nougat et la date au 2 novembre 2026."),
                    ChatItem.Action("Lit la page 1", ok = true),
                    ChatItem.Action("Modifie « Client : Jean Dupont » → « Client : Marie Nougat » (page 1)", ok = true),
                    ChatItem.Action("Modifie « Date : 15 mars 2023 » → « Date : 2 novembre 2026 » (page 1)", ok = true),
                    ChatItem.Assistant("C'est fait : le client et la date sont modifiés, dans la même police. Pensez à enregistrer."),
                ),
            )
        }
        shot("6-pages", 1440, 900, 1f, desktop, withDocument = true) { it.pagesOpen = true; it.pageSelection = setOf(1) }
        shot("7-telephone-accueil", 412, 892, 2f, phone)
        shot("8-telephone-lecture", 412, 892, 2f, phone, withDocument = true)
        shot("9-telephone-annoter-sombre", 412, 892, 2f, phone, dark = true, withDocument = true) { it.annotateDemo() }
        shot("10-telephone-editer", 412, 892, 2f, phone, withDocument = true) { state ->
            state.tools.enter(DockMode.Edit)
            val session = state.session ?: error("Pas de document")
            val block = runBlocking { session.textBlocks(0) }.first { it.text.startsWith("Client") }
            session.startEditing(block)
            session.editingText = "Client : Marie Nougat"
        }
        shot("11-telephone-accueil-sombre", 412, 892, 2f, phone, dark = true)
        shot("12-formulaire", 1440, 900, 1f, desktop, form = true) { it.editFieldDemo("email", "marie@nougat.ch") }
        shot("13-telephone-formulaire", 412, 892, 2f, phone, form = true) { it.editFieldDemo("nom", "Marie Nougat") }
    }

    /** Formulaire : un champ en cours de saisie. */
    private fun ViewerState.editFieldDemo(name: String, text: String) {
        val session = session ?: error("Pas de document")
        session.hasForm = true
        val field = runBlocking { session.formFields(0) }.first { it.name == name }
        session.startFieldEditing(field)
        session.editingFieldText = text
    }

    /** Quelques annotations visibles : un trait sous le total et un surlignage sur la date. */
    private fun ViewerState.annotateDemo() {
        tools.enter(DockMode.Annotate)
        val marks = session?.marks ?: return
        marks.add(
            InkMark(
                page = 0,
                points = (0..40).map { i -> NPoint(0.565f + i * 0.0068f, 0.379f + sin(i / 2.6).toFloat() * 0.0035f) },
                argb = 0xFF1E5BD8.toInt(),
                width = 2.5f,
            ),
        )
        marks.add(
            InkMark(
                page = 0,
                points = listOf(NPoint(0.08f, 0.159f), NPoint(0.265f, 0.159f)),
                argb = 0xFFFFE14D.toInt(),
                width = 14f,
                opacity = 0.4f,
            ),
        )
    }

    private fun shot(
        name: String,
        widthDp: Int,
        heightDp: Int,
        density: Float,
        platform: PlatformInfo,
        dark: Boolean = false,
        /** Ouvrir la facture d'exemple (et attendre qu'elle soit ouverte) avant [setup]. */
        withDocument: Boolean = false,
        /** Ouvrir le formulaire d'exemple a la place de la facture. */
        form: Boolean = false,
        setup: (ViewerState) -> Unit = {},
    ) {
        val files = FakeFiles(dir)
        val state = ViewerState(DesktopPdfEngine(), CoroutineScope(SupervisorJob() + Dispatchers.Default), files, NoAssistant)
        // Auto -> Clair -> Sombre
        state.cycleTheme()
        if (dark) state.cycleTheme()
        val scene = ImageComposeScene(
            width = (widthDp * density).toInt(),
            height = (heightDp * density).toInt(),
            density = Density(density),
        ) {
            NougatApp(state, platform, onOpenFile = {})
        }
        try {
            var time = 0L
            fun frame() {
                scene.render(time)
                time += 50_000_000L
                Thread.sleep(60)
            }
            if (withDocument || form) {
                // L'ouverture remet l'ecran a zero (pages, Claude…) : on attend qu'elle soit finie.
                if (form) state.open(formSample, "Inscription-club.pdf") else state.open(sample, "Facture-2023-042.pdf")
                val deadline = System.currentTimeMillis() + 30_000
                while ((state.session == null || state.busyMessage != null) && System.currentTimeMillis() < deadline) frame()
                check(state.session != null && state.busyMessage == null) { "La facture d'exemple ne s'est pas ouverte" }
            }
            setup(state)
            // Laisser le temps de dessiner les pages, les miniatures et finir les animations.
            repeat(40) { frame() }
            val image = scene.render(time)
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Image vide")
            File(out, "$name.png").writeBytes(png.bytes)
        } finally {
            scene.close()
        }
    }

    private object NoAssistant : AssistantEngine {
        override suspend fun send(userText: String, settings: AssistantSettings, toolbox: AssistantToolbox, onItem: (ChatItem) -> Unit) = Unit
        override fun reset() = Unit
    }

    private class FakeFiles(dir: File) : PlatformFiles {
        private val settings = mutableMapOf(
            "recents" to "C:/Users/moi/Documents/Facture-2024-007.pdf\tFacture-2024-007.pdf\n" +
                "C:/Users/moi/Documents/Contrat de location.pdf\tContrat de location.pdf\n" +
                "C:/Users/moi/Téléchargements/Formulaire impôts.pdf\tFormulaire impôts.pdf",
        )
        override val tempDir: String = dir.absolutePath
        override fun copy(src: String, dst: String) { File(src).copyTo(File(dst), true) }
        override fun delete(path: String) { File(path).delete() }
        override suspend fun overwrite(path: String, sourceRef: String) = false
        override suspend fun saveAs(path: String, suggestedName: String): SavedFile? = null
        override suspend fun pickPdf(): SavedFile? = null
        override val canPrint = true
        override suspend fun print(path: String, password: String?, name: String) = Unit
        override val canShare = false
        override suspend fun share(path: String, name: String) = Unit
        override suspend fun backupOriginal(sourceRef: String, name: String): String? = null
        override fun loadSetting(key: String): String? = settings[key]
        override fun saveSetting(key: String, value: String) { settings[key] = value }
    }

    /** Une facture de deux pages, comme un vrai document. */
    private fun invoice(): String {
        val file = File(dir, "facture.pdf")
        val reg = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val italic = PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC)
        PDDocument().use { d ->
            repeat(2) { pageIndex ->
                val page = PDPage(PDRectangle.A4)
                d.addPage(page)
                PDPageContentStream(d, page).use { cs ->
                    fun t(f: PDFont, s: Float, x: Float, y: Float, txt: String, c: Color = Color.BLACK) {
                        cs.beginText(); cs.setNonStrokingColor(c); cs.setFont(f, s); cs.newLineAtOffset(x, y); cs.showText(txt); cs.endText()
                    }
                    cs.setNonStrokingColor(Color(0xCC, 0x54, 0x2C)); cs.addRect(0f, 780f, 595f, 62f); cs.fill()
                    t(bold, 24f, 50f, 800f, if (pageIndex == 0) "FACTURE N° 2023-042" else "CONDITIONS GÉNÉRALES", Color.WHITE)
                    if (pageIndex == 0) {
                        t(reg, 12f, 50f, 740f, "Client : Jean Dupont")
                        t(reg, 12f, 50f, 722f, "Adresse : 12 rue des Lilas, Lausanne")
                        t(italic, 12f, 50f, 704f, "Date : 15 mars 2023")
                        cs.setNonStrokingColor(Color(0xF2, 0xE9, 0xE2)); cs.addRect(40f, 650f, 515f, 26f); cs.fill()
                        t(bold, 11f, 50f, 659f, "Désignation"); t(bold, 11f, 450f, 659f, "Montant")
                        t(reg, 12f, 50f, 620f, "Pose de carrelage (salle de bain)", Color.DARK_GRAY); t(reg, 12f, 450f, 620f, "1 200.00")
                        t(reg, 12f, 50f, 598f, "Fourniture joints 30 m", Color.DARK_GRAY); t(reg, 12f, 450f, 598f, "180.00")
                        t(reg, 12f, 50f, 576f, "Déplacement", Color.DARK_GRAY); t(reg, 12f, 450f, 576f, "60.00")
                        t(bold, 14f, 340f, 530f, "Total TTC :"); t(bold, 14f, 450f, 530f, "1 440.00")
                        t(reg, 10f, 50f, 120f, "Merci de votre confiance. Paiement à 30 jours.", Color.GRAY)
                    } else {
                        var y = 740f
                        for (line in listOf(
                            "1. Les travaux sont garantis deux ans.",
                            "2. Le paiement est dû à 30 jours.",
                            "3. Tout retard entraîne des pénalités.",
                            "4. Les fournitures restent notre propriété jusqu'au paiement.",
                        )) { t(reg, 12f, 50f, y, line); y -= 22f }
                    }
                }
            }
            d.save(file)
        }
        return file.absolutePath
    }
}
