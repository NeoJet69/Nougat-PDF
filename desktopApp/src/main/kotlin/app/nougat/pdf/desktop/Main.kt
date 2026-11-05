package app.nougat.pdf.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import app.nougat.pdf.assistant.ClaudeAssistantEngine
import app.nougat.pdf.engine.DesktopPdfEngine
import app.nougat.pdf.ui.NougatApp
import app.nougat.pdf.ui.NougatIcons
import app.nougat.pdf.ui.PlatformInfo
import app.nougat.pdf.viewer.DesktopFiles
import app.nougat.pdf.viewer.ViewerState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.SwingUtilities
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

fun main(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    // La fenetre, pour y rattacher les boites de dialogue.
    var frame by remember { mutableStateOf<Frame?>(null) }
    val files = remember { DesktopFiles { frame } }
    val state = remember { ViewerState(DesktopPdfEngine(), scope, files, ClaudeAssistantEngine()) }
    val pickAndOpen = { files.chooseFile(tr(Str.OpenPdf), FileDialog.LOAD, "*.pdf")?.let(state::openFile); Unit }

    // Double-clic sur un PDF dans l'Explorateur Windows : le fichier arrive ici.
    LaunchedEffect(Unit) {
        args.firstOrNull()?.let { File(it) }?.takeIf { it.isFile }?.let { state.openFile(it) }
    }

    Window(
        onCloseRequest = { state.runAfterConfirm(::exitApplication) },
        title = state.session?.let { (if (state.modified) "• " else "") + "${it.name} – Nougat PDF" } ?: "Nougat PDF",
        icon = rememberVectorPainter(NougatIcons.NougatLogo),
        // Fenetre agrandie : rien ne passe sous la barre des taches de Windows.
        state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1180.dp, height = 840.dp),
        onPreviewKeyEvent = { handleShortcut(it, state) { state.runAfterConfirm(pickAndOpen) } },
    ) {
        LaunchedEffect(window) {
            frame = window
            installDropTarget(window, state)
        }
        NougatApp(
            state = state,
            platform = PlatformInfo(isDesktop = true, version = System.getProperty("jpackage.app-version")),
            onOpenFile = pickAndOpen,
            onOpenRecent = { recent ->
                val file = File(recent.ref)
                if (file.isFile) {
                    state.runAfterConfirm { state.openFile(file) }
                } else {
                    state.forgetRecent(recent.ref)
                    state.showError(tr(Str.RecentNotFound, recent.name))
                }
            },
        )
    }
}

private fun ViewerState.openFile(file: File) = open(file.absolutePath, file.name)

/** Raccourcis clavier. Renvoie true si la touche a ete utilisee. */
private fun handleShortcut(event: KeyEvent, state: ViewerState, openDialog: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val session = state.session
    if (event.isCtrlPressed) {
        if (event.key == Key.O) {
            openDialog(); return true
        }
        if (session == null) return false
        when (event.key) {
            Key.S -> if (event.isShiftPressed) state.saveAs() else state.save()
            Key.Z -> state.undo()
            Key.P -> state.print()
            Key.E -> state.tools.editing = !state.tools.editing
            Key.W -> state.runAfterConfirm(state::closeDocument)
            Key.F -> session.openSearch()
            Key.Equals, Key.Plus, Key.NumPadAdd -> session.zoomIn()
            Key.Minus, Key.NumPadSubtract -> session.zoomOut()
            Key.Zero, Key.NumPad0 -> session.resetZoom()
            Key.MoveHome -> session.goToPage(0)
            Key.MoveEnd -> session.goToPage(session.pageCount - 1)
            else -> return false
        }
        return true
    }
    if (session == null) return false
    // Lignes choisies en mode Éditer (Ctrl + clic, Maj + clic) : Suppr les efface, Echap annule.
    if (session.selectedBlocks.isNotEmpty() && session.editingBlock == null && !session.searchOpen) {
        when (event.key) {
            Key.Delete, Key.Backspace -> { state.deleteSelectedText(); return true }
            Key.Escape -> { session.clearSelection(); return true }
            else -> Unit
        }
    }
    return when (event.key) {
        Key.F3 -> { if (session.hits.isNotEmpty()) session.nextHit(); true }
        Key.PageDown -> { session.nextPage(); true }
        Key.PageUp -> { session.previousPage(); true }
        else -> false
    }
}

/** Glisser un fichier PDF sur la fenetre pour l'ouvrir. */
private fun installDropTarget(window: java.awt.Window, state: ViewerState) {
    val target = (window as? javax.swing.JFrame)?.contentPane ?: window
    target.dropTarget = DropTarget(target, object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            }.getOrNull().orEmpty()
            val pdf = files.firstOrNull { it.name.lowercase().endsWith(".pdf") }
            event.dropComplete(pdf != null)
            if (pdf != null) SwingUtilities.invokeLater { state.runAfterConfirm { state.openFile(pdf) } }
        }
    })
}
