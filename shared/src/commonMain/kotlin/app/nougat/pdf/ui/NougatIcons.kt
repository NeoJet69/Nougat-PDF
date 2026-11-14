package app.nougat.pdf.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Les quelques icones absentes du jeu de base de Material. */
object NougatIcons {

    val Folder: ImageVector by lazy {
        materialIcon("Folder") {
            moveTo(10f, 4f); horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineToRelative(12f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(8f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineToRelative(-8f)
            close()
        }
    }

    val Remove: ImageVector by lazy {
        materialIcon("Remove") {
            moveTo(19f, 13f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(14f); close()
        }
    }

    val FitWidth: ImageVector by lazy {
        materialIcon("FitWidth") {
            // Deux fleches horizontales opposees
            moveTo(3f, 11f); horizontalLineTo(21f); verticalLineToRelative(2f); horizontalLineTo(3f); close()
            moveTo(3f, 12f); lineTo(7f, 8f); verticalLineToRelative(8f); close()
            moveTo(21f, 12f); lineTo(17f, 16f); verticalLineToRelative(-8f); close()
        }
    }

    private fun svgIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

    private fun strokeIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    val Save by lazy {
        svgIcon("Save", "M17,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10v4z")
    }
    /** Un « T » et un curseur de texte : modifier le texte existant. */
    val EditText by lazy {
        strokeIcon("EditText", "M3,5h11M8.5,5v14M18,7v13M16,7h4M16,20h4")
    }
    /** Avion en papier : envoyer. */
    val Send by lazy { svgIcon("Send", "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z") }

    /** Un oeil : le mode Lire. */
    val Eye by lazy {
        svgIcon("Eye", "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z")
    }

    /** Disque moitie plein : le theme (clair / sombre). */
    val Contrast by lazy {
        svgIcon("Contrast", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20V4c4.41,0 8,3.59 8,8s-3.59,8 -8,8z")
    }

    /** Un document : l'accueil, les fichiers recents. */
    val Document by lazy {
        svgIcon("Document", "M14,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z")
    }

    /** Un document dans un cadre de scanner : reconnaitre le texte (OCR). */
    val Scan by lazy {
        svgIcon("Scan", "M7,3H4v3H2V1h5V3zM22,6V1h-5v2h3v3H22zM7,21H4v-3H2v5h5V21zM20,18v3h-3v2h5v-5H20zM17,6H7v12h10V6zM19,18c0,1.1 -0.9,2 -2,2H7c-1.1,0 -2,-0.9 -2,-2V6c0,-1.1 0.9,-2 2,-2h10c1.1,0 2,0.9 2,2V18zM15,8H9v2h6V8zM15,11H9v2h6V11zM15,14H9v2h6V14z")
    }

    /** Deux fleches qui se rapprochent : compresser. */
    val Compress by lazy {
        svgIcon("Compress", "M8,19h3v3h2v-3h3l-4,-4 -4,4zM16,4h-3L13,1h-2v3L8,4l4,4 4,-4zM4,9v2h16L20,9L4,9zM4,12h16v2L4,14z")
    }

    val ArrowLeft by lazy { svgIcon("ArrowLeft", "M15.41,16.59L10.83,12l4.58,-4.59L14,6l-6,6 6,6 1.41,-1.41z") }
    val ArrowRight by lazy { svgIcon("ArrowRight", "M8.59,16.59L13.17,12 8.59,7.41 10,6l6,6 -6,6 -1.41,-1.41z") }
    val ArrowUp by lazy { svgIcon("ArrowUp", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z") }
    val ArrowDown by lazy { svgIcon("ArrowDown", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z") }

    /** Un telephone : echanger avec le telephone par le Wi-Fi. */
    val Phone by lazy {
        svgIcon("Phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z")
    }

    /** Un haut-parleur : lire a voix haute. */
    val Speaker by lazy {
        svgIcon(
            "Speaker",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    val Pause by lazy { svgIcon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }

    val SkipPrevious by lazy { svgIcon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }

    val SkipNext by lazy { svgIcon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }

    /** Deux coches et deux lignes : ce que je dois faire. */
    val Todo by lazy {
        svgIcon(
            "Todo",
            "M22,7h-9v2h9V7zM22,15h-9v2h9V15zM5.54,11L2,7.46l1.41,-1.41 2.12,2.12 4.24,-4.24 1.41,1.41L5.54,11z" +
                "M5.54,19L2,15.46l1.41,-1.41 2.12,2.12 4.24,-4.24 1.41,1.41L5.54,19z",
        )
    }

    /** Une equerre graduee : metre sur plan. */
    val Measure by lazy {
        svgIcon(
            "Measure",
            "M17.66,17.66l-1.06,1.06 -0.71,-0.71 1.06,-1.06 -1.94,-1.94 -1.06,1.06 -0.71,-0.71 1.06,-1.06 -1.94,-1.94" +
                " -1.06,1.06 -0.71,-0.71 1.06,-1.06L9.7,9.7l-1.06,1.06 -0.71,-0.71 1.06,-1.06 -1.94,-1.94 -1.06,1.06" +
                " -0.71,-0.71 1.06,-1.06L4,4v14c0,1.1 0.9,2 2,2h14L17.66,17.66zM7,17v-5.76L12.76,17H7z",
        )
    }

    /** Un code QR : QR-facture. */
    val QrCode by lazy {
        svgIcon(
            "QrCode",
            "M3,11h8V3H3V11zM5,5h4v4H5V5zM3,21h8v-8H3V21zM5,15h4v4H5V15zM13,3v8h8V3H13zM19,9h-4V5h4V9z" +
                "M19,19h2v2h-2V19zM13,13h2v2h-2V13zM15,15h2v2h-2V15zM13,17h2v2h-2V17zM15,19h2v2h-2V19z" +
                "M17,17h2v2h-2V17zM17,13h2v2h-2V13zM19,15h2v2h-2V15z",
        )
    }

    /** Deux feuilles : copier. */
    val Copy by lazy {
        svgIcon(
            "Copy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11" +
                "c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    }

    /** Le signe Markdown : un cadre, un M et une fleche vers le bas. */
    val Markdown by lazy {
        svgIcon(
            "Markdown",
            "M3,6h18a1,1 0,0 1,1 1v10a1,1 0,0 1,-1 1H3a1,1 0,0 1,-1 -1V7a1,1 0,0 1,1 -1z" +
                "M3.6,7.6v8.8h16.8V7.6z" +
                "M5,15V9h1.8l1.7,2.2 1.7,-2.2h1.8v6h-1.8v-3.4l-1.7,2.2 -1.7,-2.2V15z" +
                "M16,9h2v3h1.8l-2.8,3.2 -2.8,-3.2H16z",
        )
    }

    /** Une liste numerotee : numeros de page. */
    val Numbers by lazy {
        svgIcon("Numbers", "M2,17h2v0.5L3,17.5v1h1v0.5L2,19v1h3v-4L2,16v1zM3,8h1L4,4L2,4v1h1v3zM2,11h1.8L2,13.1v0.9h3v-1L3.2,13L5,10.9L5,10L2,10v1zM7,5v2h14L21,5L7,5zM7,19h14v-2L7,17v2zM7,13h14v-2L7,11v2z")
    }

    /** Deux equerres : rogner. */
    val Crop by lazy {
        svgIcon("Crop", "M17,15h2V7c0,-1.1 -0.9,-2 -2,-2H9v2h8v8zM7,17V1H5v4H1v2h4v10c0,1.1 0.9,2 2,2h10v4h2v-4h4v-2H7z")
    }

    /** Une regle : redresser. */
    val Straighten by lazy {
        svgIcon("Straighten", "M21,6H3C1.9,6 1,6.9 1,8v8c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2zM21,16H3V8h2v4h2V8h2v4h2V8h2v4h2V8h2v4h2V8h2v8z")
    }

    /** Un oeil barre : censurer. */
    val Censor by lazy {
        svgIcon("Censor", "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z")
    }

    /** Un bouclier avec une coche : finaliser pour envoyer. */
    val Shield by lazy {
        svgIcon("Shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z")
    }

    /** Un « A » et un caractere chinois : traduire. */
    val Translate by lazy {
        svgIcon("Translate", "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z")
    }

    /** Un appareil photo : scanner un document. */
    val Camera by lazy {
        svgIcon("Camera", "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z")
    }

    /** Un globe : choisir la langue. */
    val Language by lazy {
        svgIcon("Language", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56 1.84,0.63 3.37,1.91 4.33,3.56zM12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96zM4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2 0,0.68 0.06,1.34 0.14,2L4.26,14zM5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56 -1.84,-0.63 -3.37,-1.9 -4.33,-3.56zM8.03,8L5.08,8c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8zM12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96zM14.34,14L9.66,14c-0.09,-0.66 -0.16,-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2zM14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56zM16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z")
    }

    /** Une image (paysage et soleil) : creer un PDF depuis des photos. */
    val Image by lazy {
        svgIcon("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    }

    /** Une feuille avec un « + » : nouveau document. */
    val NewDocument by lazy {
        svgIcon("NewDocument", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,16h-3v3h-2v-3H8v-2h3v-3h2v3h3v2zM13,9V3.5L18.5,9H13z")
    }

    /** Fleche vers le haut dans un nuage : deposer un fichier. */
    val Upload by lazy {
        svgIcon("Upload", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM14,13v4h-4v-4H7l5,-5 5,5h-3z")
    }

    /** Etincelles : l'assistant Claude. */
    val Sparkle by lazy {
        svgIcon("Sparkle", "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12L11.5,9.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z")
    }
    val Undo by lazy {
        svgIcon("Undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    }
    val Pages by lazy {
        svgIcon("Pages", "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9zM13,13v8h8v-8H13zM19,19h-4v-4h4V19z")
    }
    val Text by lazy {
        svgIcon("Text", "M2.5,4v3h5v12h3V7h5V4H2.5zM21.5,9h-9v3h3v7h3v-7h3V9z")
    }
    val Pen by lazy {
        svgIcon("Pen", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    }
    val Highlighter by lazy {
        svgIcon("Highlighter", "M3,20h18v2H3zM14.06,3.19l4.75,4.75L10.75,16H6v-4.75L14.06,3.19z")
    }
    val Whiteout by lazy {
        svgIcon("Whiteout", "M19,5v14H5V5h14m0,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM7,9h10v2H7zM7,13h7v2H7z")
    }
    val Signature by lazy {
        strokeIcon("Signature", "M3,16c2,-5 4,-9 6,-8s-2,9 1,8 3,-6 5,-5 0,4 2,4 2,-1 4,-2M3,21h18")
    }
    val Eraser by lazy {
        svgIcon("Eraser", "M16.24,3.56l4.95,4.94c0.78,0.79 0.78,2.05 0,2.84L12,20.53c-1.56,1.56 -4.1,1.56 -5.66,0L2.81,17c-0.78,-0.79 -0.78,-2.05 0,-2.84l10.6,-10.6c0.79,-0.78 2.05,-0.78 2.83,0M4.22,15.58l3.54,3.53c0.78,0.79 2.04,0.79 2.83,0l3.53,-3.53 -4.95,-4.95 -4.95,4.95z")
    }
    val Hand by lazy {
        svgIcon("Hand", "M7,2l12,11.2 -5.8,0.5 3.3,7.3 -2.2,1 -3.2,-7.4L7,18.5V2z")
    }
    val RotateRight by lazy {
        svgIcon("RotateRight", "M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91s2.16,-5.43 5,-5.91V10l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42c0.54,0.75 0.88,1.6 1.02,2.47h2.02zM13,17.9v2.02c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48l1.42,1.41c0.9,-1.16 1.45,-2.5 1.62,-3.89h-2.02c-0.14,0.87 -0.48,1.72 -1.02,2.48z")
    }
    val RotateLeft by lazy {
        svgIcon("RotateLeft", "M7.11,8.53L5.7,7.11C4.8,8.27 4.24,9.61 4.07,11h2.02c0.14,-0.87 0.49,-1.72 1.02,-2.47zM6.09,13H4.07c0.17,1.39 0.72,2.73 1.62,3.89l1.41,-1.42c-0.52,-0.75 -0.87,-1.59 -1.01,-2.47zM7.1,18.32c1.16,0.9 2.51,1.44 3.9,1.61V17.9c-0.87,-0.15 -1.71,-0.49 -2.46,-1.03L7.1,18.32zM13,4.07V1L8.45,5.55 13,10V6.09c2.84,0.48 5,2.94 5,5.91s-2.16,5.43 -5,5.91v2.02c3.95,-0.49 7,-3.85 7,-7.93s-3.05,-7.44 -7,-7.93z")
    }
    val Print by lazy {
        svgIcon("Print", "M19,8H5c-1.66,0 -3,1.34 -3,3v6h4v4h12v-4h4v-6c0,-1.66 -1.34,-3 -3,-3zM16,19H8v-5h8v5zM19,12c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1zM18,3H6v4h12V3z")
    }
    val Extract by lazy {
        svgIcon("Extract", "M14,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,15h-3v3h-2v-3H8v-2h3v-3h2v3h3v2zM13,9V3.5L18.5,9H13z")
    }
    val ArrowLeft by lazy { svgIcon("ArrowLeft", "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z") }
    val ArrowRight by lazy { svgIcon("ArrowRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
    val SelectAll by lazy {
        svgIcon("SelectAll", "M19,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.11,0 2,-0.9 2,-2V5c0,-1.1 -0.89,-2 -2,-2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    }

    private fun materialIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

    /** Nougat, le chat, en logo. */
    val NougatLogo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Nougat",
            defaultWidth = 108.dp,
            defaultHeight = 108.dp,
            viewportWidth = 108f,
            viewportHeight = 108f,
        ).apply {
            // Oreilles
            path(fill = SolidColor(NougatColors.CaramelDark)) {
                moveTo(20f, 52f); lineTo(26f, 12f); lineTo(52f, 34f); close()
                moveTo(88f, 52f); lineTo(82f, 12f); lineTo(56f, 34f); close()
            }
            path(fill = SolidColor(NougatColors.Pink)) {
                moveTo(28f, 44f); lineTo(31f, 22f); lineTo(45f, 34f); close()
                moveTo(80f, 44f); lineTo(77f, 22f); lineTo(63f, 34f); close()
            }
            // Tete
            path(fill = SolidColor(NougatColors.Caramel)) {
                ellipse(54f, 62f, 38f, 32f)
            }
            // Museau creme
            path(fill = SolidColor(NougatColors.Cream)) {
                ellipse(54f, 76f, 18f, 13f)
            }
            // Yeux
            path(fill = SolidColor(NougatColors.Cocoa)) {
                ellipse(40f, 60f, 5f, 6.5f)
                ellipse(68f, 60f, 5f, 6.5f)
            }
            path(fill = SolidColor(Color.White)) {
                ellipse(41.6f, 57.8f, 1.6f, 1.6f)
                ellipse(69.6f, 57.8f, 1.6f, 1.6f)
            }
            // Nez
            path(fill = SolidColor(NougatColors.Pink)) {
                moveTo(49f, 70f); lineTo(59f, 70f); lineTo(54f, 76f); close()
            }
            // Bouche et moustaches
            path(
                stroke = SolidColor(NougatColors.Cocoa),
                strokeLineWidth = 2f,
            ) {
                moveTo(54f, 76f); lineTo(54f, 80f)
                moveTo(54f, 80f); curveTo(51f, 84f, 47f, 83f, 46f, 80f)
                moveTo(54f, 80f); curveTo(57f, 84f, 61f, 83f, 62f, 80f)
                moveTo(36f, 74f); lineTo(14f, 70f)
                moveTo(36f, 78f); lineTo(15f, 80f)
                moveTo(72f, 74f); lineTo(94f, 70f)
                moveTo(72f, 78f); lineTo(93f, 80f)
            }
        }.build()
    }

    private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
        moveTo(cx - rx, cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + rx, y1 = cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - rx, y1 = cy)
        close()
    }
}
