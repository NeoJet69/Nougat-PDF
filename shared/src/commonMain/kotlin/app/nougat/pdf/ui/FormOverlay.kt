package app.nougat.pdf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nougat.pdf.engine.FIELD_OFF
import app.nougat.pdf.engine.FieldKind
import app.nougat.pdf.engine.FormField
import app.nougat.pdf.engine.PageSize
import app.nougat.pdf.engine.nextFieldAfter
import app.nougat.pdf.viewer.DocumentSession
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Calque des formulaires (mode Lire) : les champs a remplir sont teintes. Toucher un champ texte
 * l'ouvre (sur la page sur ordinateur, dans une fenetre sur telephone), une case se coche ou se
 * decoche, un bouton radio se choisit, une liste deroule ses choix.
 */
@Composable
fun FormOverlay(
    session: DocumentSession,
    page: Int,
    pageSize: PageSize,
    /** Ordinateur : saisie posee sur le champ. Telephone : fenetre avec Suivant / Valider. */
    inlineEditor: Boolean,
    onSetValue: (field: FormField, value: String, next: FormField?) -> Unit,
    /** Ouvre la saisie d'un champ texte (le champ en cours est d'abord enregistre). */
    onStartEditing: (FormField) -> Unit,
    onCommitPending: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val fields by produceState(session.cachedFormFields(page) ?: emptyList(), page, session.hasForm) {
        if (session.hasForm) value = session.formFields(page)
    }
    if (fields.isEmpty()) return
    val current by rememberUpdatedState(fields)
    var choiceFor by remember { mutableStateOf<FormField?>(null) }
    val tint = MaterialTheme.colorScheme.primary

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        Canvas(
            Modifier.fillMaxSize().pointerInput(page) {
                detectTapGestures { o ->
                    val hit = fieldAt(current, o, size.width.toFloat(), size.height.toFloat(), 6.dp.toPx())
                    when {
                        // Toucher a cote : le champ en cours de saisie est enregistre.
                        hit == null -> if (session.editingField != null) onCommitPending()
                        hit.readOnly -> onMessage("Ce champ est en lecture seule.")
                        hit.kind == FieldKind.Text -> onStartEditing(hit)
                        hit.kind == FieldKind.CheckBox ->
                            onSetValue(hit, if (hit.checked) FIELD_OFF else hit.onValue ?: "Yes", null)
                        hit.kind == FieldKind.Radio -> if (!hit.checked && hit.onValue != null) onSetValue(hit, hit.onValue, null)
                        hit.kind == FieldKind.Choice -> if (hit.options.isNotEmpty()) choiceFor = hit
                    }
                }
            },
        ) {
            val editing = session.editingField
            val corner = CornerRadius(3.dp.toPx())
            for (f in fields) {
                if (f.readOnly || (editing != null && f.sameSpot(editing))) continue
                val topLeft = Offset(f.rect.left * size.width, f.rect.top * size.height)
                val box = Size((f.rect.right - f.rect.left) * size.width, (f.rect.bottom - f.rect.top) * size.height)
                drawRoundRect(tint.copy(alpha = 0.09f), topLeft, box, corner)
                drawRoundRect(tint.copy(alpha = 0.55f), topLeft, box, corner, style = Stroke(width = 1.2.dp.toPx()))
            }
        }

        // Saisie du champ texte en cours
        val editing = session.editingField
        if (editing != null && editing.page == page) {
            val next = nextFieldAfter(editing, fields)
            if (inlineEditor) {
                InlineFieldEditor(
                    field = editing,
                    initialText = session.editingFieldText,
                    pageSize = pageSize,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    hasNext = next != null,
                    onTextChange = { session.editingFieldText = it },
                    onDone = { text, goNext -> onSetValue(editing, text, if (goNext) next else null) },
                    onCancel = { session.editingField = null },
                )
            } else {
                FieldDialog(
                    field = editing,
                    text = session.editingFieldText,
                    hasNext = next != null,
                    onTextChange = { session.editingFieldText = it },
                    onDone = { goNext -> onSetValue(editing, session.editingFieldText, if (goNext) next else null) },
                    onCancel = { session.editingField = null },
                )
            }
        }

        // Liste de choix
        choiceFor?.let { f ->
            Box(
                Modifier.offset {
                    IntOffset((f.rect.left * widthPx).roundToInt(), (f.rect.bottom * heightPx).roundToInt())
                },
            ) {
                DropdownMenu(expanded = true, onDismissRequest = { choiceFor = null }, shape = RoundedCornerShape(16.dp)) {
                    f.options.forEachIndexed { i, label ->
                        val value = f.optionValues.getOrElse(i) { label }
                        DropdownMenuItem(
                            text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                            leadingIcon = {
                                if (value == f.value) Icon(Icons.Filled.Check, contentDescription = null, tint = tint)
                                else Box(Modifier.size(24.dp))
                            },
                            onClick = {
                                choiceFor = null
                                onSetValue(f, value, null)
                            },
                            modifier = Modifier.height(48.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun FormField.sameSpot(other: FormField) = name == other.name && rect == other.rect

/** Le champ sous le doigt (avec un peu de marge pour les petites cases). */
internal fun fieldAt(fields: List<FormField>, o: Offset, width: Float, height: Float, slop: Float): FormField? {
    var best: FormField? = null
    var bestArea = Float.MAX_VALUE
    for (f in fields) {
        val l = f.rect.left * width - slop
        val t = f.rect.top * height - slop
        val r = f.rect.right * width + slop
        val b = f.rect.bottom * height + slop
        if (o.x in l..r && o.y in t..b) {
            // Champs imbriques : le plus petit gagne (une case dans un cadre).
            val area = (r - l) * (b - t)
            if (area < bestArea) {
                best = f
                bestArea = area
            }
        }
    }
    return best
}

/** Ordinateur : la saisie se fait sur le champ lui-meme. Entree valide, Tab passe au suivant. */
@Composable
private fun InlineFieldEditor(
    field: FormField,
    initialText: String,
    pageSize: PageSize,
    widthPx: Float,
    heightPx: Float,
    hasNext: Boolean,
    onTextChange: (String) -> Unit,
    onDone: (text: String, goNext: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    var value by remember(field) { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(field) { focus.requestFocus() }

    val ptToPx = widthPx / pageSize.width
    val boxWidthPx = (field.rect.right - field.rect.left) * widthPx
    val boxHeightPx = (field.rect.bottom - field.rect.top) * heightPx
    val heightPt = boxHeightPx / ptToPx
    val sizePt = if (field.fontSize > 0f) field.fontSize else if (field.multiline) 11f else min(heightPt * 0.62f, 13f)
    val fontPx = max(sizePt * ptToPx, with(density) { 13.sp.toPx() })
    val width = with(density) { boxWidthPx.toDp() }.coerceAtLeast(140.dp)
    val height = with(density) { boxHeightPx.toDp() }
    val buttonsAbove = field.rect.bottom > 0.85f

    @Composable
    fun Buttons() {
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Annuler", colors.surfaceContainerHighest, colors.onSurface, onCancel)
            if (hasNext) Pill("Suivant", colors.secondaryContainer, colors.onSecondaryContainer) { onDone(value.text, true) }
            Pill("Valider", colors.primary, colors.onPrimary) { onDone(value.text, false) }
        }
    }

    Column(
        Modifier.offset {
            val left = (field.rect.left * widthPx).roundToInt() - 3
            val top = (field.rect.top * heightPx).roundToInt() - 3
            IntOffset(left, if (buttonsAbove) top - 46.dp.roundToPx() else top)
        },
    ) {
        if (buttonsAbove) Buttons()
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.White,
            border = BorderStroke(2.dp, colors.primary),
            shadowElevation = 6.dp,
        ) {
            BasicTextField(
                value = value,
                onValueChange = {
                    val limited = if (field.maxLength > 0 && it.text.length > field.maxLength) it.copy(text = it.text.take(field.maxLength)) else it
                    value = limited
                    onTextChange(limited.text)
                },
                singleLine = !field.multiline,
                textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = with(density) { fontPx.toSp() }),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = if (field.multiline) ImeAction.Default else if (hasNext) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(value.text, false) }, onNext = { onDone(value.text, true) }),
                modifier = Modifier
                    .width(width)
                    .heightIn(min = max(height.value, if (field.multiline) 56f else 24f).dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.Escape -> { onCancel(); true }
                            e.key == Key.Tab -> { onDone(value.text, true); true }
                            (e.key == Key.Enter || e.key == Key.NumPadEnter) && (!field.multiline || e.isCtrlPressed) -> {
                                onDone(value.text, false); true
                            }
                            else -> false
                        }
                    },
            )
        }
        if (!buttonsAbove) Buttons()
    }
}

@Composable
private fun Pill(text: String, background: Color, content: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = background, contentColor = content, shadowElevation = 3.dp) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** Telephone : le champ dans une fenetre. Le bouton OK du clavier valide (ou passe au suivant). */
@Composable
private fun FieldDialog(
    field: FormField,
    text: String,
    hasNext: Boolean,
    onTextChange: (String) -> Unit,
    onDone: (goNext: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var value by remember(field) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(field) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(field.label, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        val limited = if (field.maxLength > 0 && it.text.length > field.maxLength) it.copy(text = it.text.take(field.maxLength)) else it
                        value = limited
                        onTextChange(limited.text)
                    },
                    singleLine = !field.multiline,
                    minLines = if (field.multiline) 3 else 1,
                    supportingText = if (field.maxLength > 0) {
                        { Text("${value.text.length} / ${field.maxLength}") }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (field.multiline) ImeAction.Default else if (hasNext) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone(false) }, onNext = { onDone(true) }),
                    // Champ suivant : la fleche (ou le bouton « Suivant » du clavier).
                    trailingIcon = if (hasNext) {
                        { IconButton(onClick = { onDone(true) }) { Icon(NougatIcons.ArrowRight, contentDescription = "Champ suivant") } }
                    } else null,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (hasNext) {
                    Text(
                        "La flèche passe au champ suivant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onDone(false) }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Annuler") } },
    )
}
