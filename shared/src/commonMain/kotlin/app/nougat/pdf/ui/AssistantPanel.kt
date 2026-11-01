package app.nougat.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.nougat.pdf.assistant.AssistantModels
import app.nougat.pdf.assistant.ChatItem
import app.nougat.pdf.viewer.ViewerState
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

/** Idees de demandes, dans la langue choisie (Claude repond dans la meme langue). */
private val Suggestions = listOf(Str.Suggest1, Str.Suggest2, Str.Suggest3, Str.Suggest4, Str.Suggest5)

/** Le panneau de l'assistant Claude : conversation, ou reglages (cle API, modele). */
@Composable
fun AssistantPanel(state: ViewerState, modifier: Modifier) {
    val a = state.assistant
    val colors = MaterialTheme.colorScheme
    val p = LocalNougatPalette.current
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .shadow(24.dp, shape, ambientColor = p.shadow, spotColor = p.shadow)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, p.islandBorder, shape),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GradientBadge(NougatIcons.Sparkle, size = 40.dp, iconSize = 22.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Claude", style = MaterialTheme.typography.titleMedium)
                    Text(AssistantModels.label(state.assistantModel), style = MaterialTheme.typography.labelMedium, color = p.muted)
                }
                IslandButton(Icons.Filled.Refresh, tr(Str.NewConversation), onClick = state::resetAssistant, enabled = a.items.isNotEmpty() && !a.running, size = 42.dp, iconSize = 22.dp)
                IslandButton(Icons.Filled.Settings, tr(Str.AssistantSettings), onClick = { a.showSettings = !a.showSettings }, active = a.showSettings, size = 42.dp, iconSize = 22.dp)
                IslandButton(Icons.Filled.Close, tr(Str.CloseAssistant), onClick = { a.open = false }, size = 42.dp, iconSize = 22.dp)
            }
            HorizontalDivider(color = p.islandBorder)
            if (a.showSettings || state.apiKey.isBlank()) {
                AssistantSettingsForm(state, Modifier.weight(1f).fillMaxWidth())
            } else {
                Conversation(state, Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider()
                InputRow(state)
            }
        }
    }
}

@Composable
private fun Conversation(state: ViewerState, modifier: Modifier) {
    val a = state.assistant
    val listState = rememberLazyListState()
    LaunchedEffect(a.items.size, a.running) {
        val last = a.items.size + (if (a.running) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (a.items.isEmpty()) {
            item { Welcome(onPick = { a.input = it }) }
        }
        items(a.items) { ChatBubble(it) }
        if (a.running) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(tr(Str.ClaudeWorking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Welcome(onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr(Str.AssistantIntro),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        for (key in Suggestions) {
            val s = tr(key)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().clickable { onPick(s) },
            ) {
                Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ChatBubble(item: ChatItem) {
    val colors = MaterialTheme.colorScheme
    when (item) {
        is ChatItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(NougatGradient)) {
                Text(item.text, color = Color.White, modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        is ChatItem.Assistant -> Surface(shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), color = colors.surfaceContainerHigh) {
            SelectionContainer {
                Text(item.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        is ChatItem.Action -> Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
            val failed = item.ok == false
            Icon(
                if (failed) Icons.Filled.Warning else Icons.Filled.Check,
                contentDescription = null,
                tint = if (failed) colors.error else NougatColors.StatusOk,
                modifier = Modifier.size(16.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                item.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
            }
        }
        is ChatItem.Error -> Surface(shape = RoundedCornerShape(12.dp), color = colors.errorContainer, contentColor = colors.onErrorContainer) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun InputRow(state: ViewerState) {
    val a = state.assistant
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = a.input,
            onValueChange = { a.input = it },
            placeholder = { Text(tr(Str.AskClaude)) },
            maxLines = 5,
            enabled = !a.running,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                // Entree envoie, Maj+Entree va a la ligne (ordinateur).
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                    state.sendToAssistant(a.input)
                    true
                } else false
            },
        )
        Spacer(Modifier.width(8.dp))
        if (a.running) {
            OutlinedButton(onClick = state::stopAssistant, modifier = Modifier.padding(bottom = 4.dp)) { Text(tr(Str.Stop)) }
        } else {
            Box(
                Modifier
                    .padding(bottom = 4.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (a.input.isNotBlank()) NougatGradient else Brush.linearGradient(listOf(Color.LightGray, Color.LightGray)))
                    .clickable(enabled = a.input.isNotBlank(), onClickLabel = tr(Str.Send)) { state.sendToAssistant(a.input) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(NougatIcons.Send, contentDescription = tr(Str.Send), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** La cle API personnelle et le choix du modele. */
@Composable
private fun AssistantSettingsForm(state: ViewerState, modifier: Modifier) {
    var key by remember { mutableStateOf(state.apiKey) }
    var model by remember { mutableStateOf(state.assistantModel) }
    val focus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(Str.OwnAccess), style = MaterialTheme.typography.titleSmall)
        Text(
            tr(Str.ApiKeyExplain),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHigh) {
            Text(
                tr(Str.ApiKeySteps),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text(tr(Str.ApiKeyLabel)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Text(tr(Str.Model), style = MaterialTheme.typography.titleSmall)
        for ((id, name, description) in AssistantModels.choices) {
            Row(
                Modifier.fillMaxWidth().clickable { model = id }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = model == id, onClick = { model = id })
                Column {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(tr(description), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { state.saveAssistantSettings(key, model) }, enabled = key.isNotBlank()) { Text(tr(Str.Save)) }
            if (state.apiKey.isNotBlank()) {
                TextButton(onClick = { state.assistant.showSettings = false }) { Text(tr(Str.Cancel)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { key = ""; state.saveAssistantSettings("", model) }) {
                    Text(tr(Str.ForgetKey), color = colors.error)
                }
            }
        }
    }
}
