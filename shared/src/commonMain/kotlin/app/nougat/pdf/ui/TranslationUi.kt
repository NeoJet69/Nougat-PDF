package app.nougat.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.nougat.pdf.engine.DeviceAiStatus
import app.nougat.pdf.engine.PageColors
import app.nougat.pdf.engine.PageSize
import app.nougat.pdf.engine.ParagraphAlign
import app.nougat.pdf.engine.StandardFont
import app.nougat.pdf.engine.TRANSLATION_LANGUAGES
import app.nougat.pdf.engine.TranslatedParagraph
import app.nougat.pdf.engine.translationLanguage
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.PageTranslation
import app.nougat.pdf.viewer.TranslationEngine
import app.nougat.pdf.viewer.TranslationSettings
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/*
 * Traduire : la fenetre de depart (langue, moteur), le bandeau (original ou traduction, enregistrer
 * la copie traduite) et la traduction posee sur chaque page, a la place des paragraphes d'origine.
 */

/** « Traduire le document » : vers quelle langue, et avec quoi. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslateDialog(state: ViewerState, onDismiss: () -> Unit) {
    val t = state.translation
    var target by remember { mutableStateOf(t.target) }
    var engine by remember { mutableStateOf(t.preferredEngine) }
    var polish by remember { mutableStateOf(t.polish) }
    LaunchedEffect(Unit) { state.refreshDeviceAi() }
    val p = LocalNougatPalette.current
    val needsKey = engine == TranslationEngine.Claude && state.apiKey.isBlank()
    val languages = TRANSLATION_LANGUAGES.filter { engine == TranslationEngine.Claude || it.code in t.deviceLanguages }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NougatIcons.Translate, contentDescription = null) },
        title = { Text(tr(Str.TranslateTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(Str.TranslateExplain), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                if (t.canDevice && t.canClaude) {
                    Column {
                        EngineRow(tr(Str.TranslateDevice), tr(Str.TranslateDeviceDesc), engine == TranslationEngine.Device) { engine = TranslationEngine.Device }
                        EngineRow(tr(Str.TranslateClaude), tr(Str.TranslateClaudeDesc), engine == TranslationEngine.Claude) { engine = TranslationEngine.Claude }
                    }
                }
                if (needsKey) {
                    Text(tr(Str.TranslateNeedKey), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = state::askApiKey) { Text(tr(Str.TranslateAddKey)) }
                }
                if (engine == TranslationEngine.Device && state.deviceAi != DeviceAiStatus.Unavailable) {
                    val label = tr(Str.TranslateGemini) + if (state.deviceAi == DeviceAiStatus.Available) "" else " " + tr(Str.OcrAiDownload)
                    CheckRow(label, polish) { polish = it }
                }
                Text(tr(Str.TranslateInto), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (lang in languages) {
                        val selected = lang.code == target
                        FilterChip(
                            selected = selected,
                            onClick = { target = lang.code },
                            label = { Text(lang.nativeName) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                if (engine == TranslationEngine.Device) {
                    Text(tr(Str.TranslateDownloadNote), style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !needsKey && languages.any { it.code == target },
                onClick = { state.startTranslation(TranslationSettings(target, engine, polish)) },
            ) { Text(tr(Str.TranslateStart)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}

@Composable
private fun EngineRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) colors.primary else colors.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = LocalNougatPalette.current.muted)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Le bandeau de la traduction : la langue, original ou traduction, enregistrer, fermer. */
@Composable
fun TranslationBar(state: ViewerState, compact: Boolean, phone: Boolean, modifier: Modifier) {
    val t = state.translation
    val settings = t.settings ?: return
    val language = translationLanguage(settings.target)?.nativeName ?: settings.target
    val p = LocalNougatPalette.current
    val hint = tr(if (phone) Str.TranslateHintTap else Str.TranslateHintClick)
    if (compact) {
        Island(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), padding = 8.dp) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientBadge(NougatIcons.Translate, size = 34.dp, iconSize = 19.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { t.dialogOpen = true }) {
                        Text("→ $language", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IslandButton(Icons.Filled.Close, tr(Str.TranslateStop), onClick = t::stop, size = 36.dp, iconSize = 18.dp)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OriginalToggle(t.showOriginal, { t.showOriginal = it }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    GradientButton(tr(Str.Save), onClick = state::saveTranslation, icon = NougatIcons.Save, height = 38.dp)
                }
            }
        }
        return
    }
    Island(modifier.widthIn(max = 820.dp), shape = RoundedCornerShape(26.dp), padding = 6.dp) {
        GradientBadge(NougatIcons.Translate, size = 36.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f, fill = false).widthIn(max = 260.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { t.dialogOpen = true }) {
            Text("→ $language", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OriginalToggle(t.showOriginal, { t.showOriginal = it }, Modifier.width(250.dp))
        Spacer(Modifier.width(8.dp))
        GradientButton(tr(Str.TranslateSave), onClick = state::saveTranslation, icon = NougatIcons.Save, height = 40.dp)
        IslandButton(Icons.Filled.Close, tr(Str.TranslateStop), onClick = t::stop, size = 40.dp, iconSize = 18.dp)
    }
}

/** Deux boutons colles : « Original » et « Traduction ». */
@Composable
private fun OriginalToggle(original: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(modifier.height(38.dp).clip(shape).background(colors.surfaceVariant).padding(3.dp)) {
        for ((label, isOriginal) in listOf(tr(Str.TranslateOriginal) to true, tr(Str.TranslateTranslated) to false)) {
            val selected = original == isOriginal
            Box(
                Modifier.weight(1f).fillMaxSize().clip(shape)
                    .background(if (selected) colors.primary else Color.Transparent)
                    .clickable { onChange(isOriginal) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Paragraphe traduit et couleurs lues sur l'image de la page (papier, encre). */
private class ShownParagraph(val item: TranslatedParagraph, val paper: Color, val ink: Color)

/**
 * La traduction posee sur la page : chaque paragraphe d'origine est recouvert de la couleur du
 * papier, et sa traduction s'ecrit dans son cadre, un peu plus petite si elle est plus longue.
 * Toucher un paragraphe montre l'original (et inversement).
 */
@Composable
fun TranslationOverlay(state: ViewerState, session: DocumentSession, page: Int, pageSize: PageSize, image: ImageBitmap?) {
    val t = state.translation
    val result = t.pages[page]
    if (result is PageTranslation.Done && !t.showOriginal) {
        val shown by produceState<List<ShownParagraph>?>(null, result, image) {
            value = withContext(Dispatchers.Default) { colorsOf(result.paragraphs, image) }
        }
        val list = shown
        if (list != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val dpPerPoint = maxWidth / pageSize.width
                list.forEachIndexed { index, para ->
                    val peeked = (page to index) in t.peeked
                    TranslatedBlock(para, peeked, dpPerPoint.value, maxWidth.value, maxHeight.value, density.fontScale) { t.togglePeek(page, index) }
                }
            }
        }
    }
    PageStatusChip(state, session, page, result)
}

private fun colorsOf(items: List<TranslatedParagraph>, image: ImageBitmap?): List<ShownParagraph> = items.map { item ->
    val r = item.paragraph.rect
    if (image == null) return@map ShownParagraph(item, Color.White, Color(0xFF202020))
    val iw = image.width
    val ih = image.height
    // On lit un peu autour du paragraphe : le papier est pris sur ce pourtour.
    val margin = max(3, ((r.bottom - r.top) * ih / 4).toInt())
    val x0 = ((r.left * iw).toInt() - margin).coerceIn(0, iw - 1)
    val y0 = ((r.top * ih).toInt() - margin).coerceIn(0, ih - 1)
    val x1 = ((r.right * iw).toInt() + margin + 1).coerceIn(x0 + 1, iw)
    val y1 = ((r.bottom * ih).toInt() + margin + 1).coerceIn(y0 + 1, ih)
    val w = x1 - x0
    val h = y1 - y0
    val pixels = IntArray(w * h)
    runCatching { image.readPixels(pixels, x0, y0, w, h) }
    val (paper, ink) = PageColors.sample(
        pixels, w, h,
        (r.left * iw).toInt() - x0, (r.top * ih).toInt() - y0, (r.right * iw).toInt() + 1 - x0, (r.bottom * ih).toInt() + 1 - y0,
    )
    ShownParagraph(item, Color(paper or (0xFF shl 24)), Color(ink or (0xFF shl 24)))
}

@Composable
private fun TranslatedBlock(
    para: ShownParagraph,
    peeked: Boolean,
    dpPerPoint: Float,
    pageWidthDp: Float,
    pageHeightDp: Float,
    fontScale: Float,
    onToggle: () -> Unit,
) {
    val p = para.item.paragraph
    val r = p.rect
    val pad = p.fontSize * 0.12f * dpPerPoint
    val boxHeight = max((r.bottom - r.top) * pageHeightDp, p.fontSize * (1f + (p.lines.size - 1) * p.lineSpacing) * dpPerPoint)
    val modifier = Modifier
        .offset(x = (r.left * pageWidthDp - pad).dp, y = (r.top * pageHeightDp - pad).dp)
        .size(width = (p.layoutWidth * pageWidthDp + 2 * pad).dp, height = (boxHeight + 2 * pad).dp)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
    if (peeked) {
        // L'original est visible : un fin cadre rappelle qu'il y a une traduction.
        Box(modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(3.dp)))
        return
    }
    // Taille d'origine en sp (la taille du texte du systeme ne doit pas l'agrandir).
    val maxSize = (p.fontSize * dpPerPoint / fontScale).coerceAtLeast(1f)
    val family = when (p.standardFont) {
        StandardFont.TimesRoman, StandardFont.TimesBold, StandardFont.TimesItalic, StandardFont.TimesBoldItalic -> FontFamily.Serif
        StandardFont.Courier, StandardFont.CourierBold, StandardFont.CourierOblique, StandardFont.CourierBoldOblique -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    Box(modifier.background(para.paper).padding(pad.dp)) {
        BasicText(
            text = para.item.text,
            modifier = Modifier.fillMaxSize(),
            style = TextStyle(
                color = para.ink,
                fontFamily = family,
                fontWeight = if (p.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (p.italic) FontStyle.Italic else FontStyle.Normal,
                textAlign = when (p.align) {
                    ParagraphAlign.Start -> TextAlign.Start
                    ParagraphAlign.Center -> TextAlign.Center
                    ParagraphAlign.End -> TextAlign.End
                },
                textDirection = TextDirection.Content,
                lineHeight = max(1.1f, p.lineSpacing).em,
            ),
            overflow = TextOverflow.Clip,
            autoSize = TextAutoSize.StepBased(minFontSize = (maxSize * 0.35f).coerceAtLeast(1f).sp, maxFontSize = maxSize.sp, stepSize = 0.25.sp),
        )
    }
}

/** En haut de la page : ce qui se passe (traduction, telechargement, deja dans la langue, erreur). */
@Composable
private fun PageStatusChip(state: ViewerState, session: DocumentSession, page: Int, result: PageTranslation?) {
    val t = state.translation
    if (t.settings == null) return
    val (text, busy) = when (result) {
        null, PageTranslation.Working -> tr(Str.TranslateWorking) to true
        is PageTranslation.Downloading -> tr(Str.TranslateDownloading, result.language) to true
        PageTranslation.Same -> tr(Str.TranslateSame) to false
        PageTranslation.Unknown -> tr(Str.TranslateUnknown) to false
        PageTranslation.NoText -> if (session.ocrPages > 0) tr(Str.TranslateScanned) to false else return
        is PageTranslation.Failed -> tr(Str.TranslateFailed, result.message) to false
        is PageTranslation.Done -> return
    }
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, start = 10.dp, end = 10.dp), contentAlignment = Alignment.TopCenter) {
        Island(shape = RoundedCornerShape(50), padding = 4.dp) {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                Icon(NougatIcons.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            when (result) {
                is PageTranslation.Failed -> TextButton(onClick = { t.retry(page) }) { Text(tr(Str.TranslateRetry)) }
                PageTranslation.NoText -> TextButton(onClick = { state.ocrDialog = true }) { Text(tr(Str.ScanBannerButton)) }
                else -> Unit
            }
        }
    }
}
