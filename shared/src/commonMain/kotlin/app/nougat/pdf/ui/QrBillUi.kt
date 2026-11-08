package app.nougat.pdf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nougat.pdf.engine.QrLanguage
import app.nougat.pdf.engine.QrProblem
import app.nougat.pdf.engine.SwissQr
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.FoundQrBill
import app.nougat.pdf.viewer.QrBillForm
import app.nougat.pdf.viewer.ViewerState

/*
 * QR-facture suisse : ce qui a ete trouve dans le document (a copier pour l'e-banking), et le
 * formulaire pour ajouter la section paiement a un devis ou une facture.
 */

@Composable
fun QrBillDialog(state: ViewerState) {
    val qr = state.qrBill
    if (qr.creating) QrBillCreateDialog(state) else QrBillFoundDialog(state)
}

@Composable
private fun QrBillFoundDialog(state: ViewerState) {
    val qr = state.qrBill
    AlertDialog(
        onDismissRequest = state::closeQrBill,
        icon = { Icon(NougatIcons.QrCode, contentDescription = null) },
        title = { Text(tr(Str.QrBill)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (found in qr.found) FoundBillCard(state, found, showPage = state.session?.pageCount?.let { it > 1 } == true)
                if (qr.searching) {
                    val (done, total) = qr.progress
                    Text(tr(Str.QrBillSearching, (done + 1).coerceAtMost(total), total), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { if (total > 0) done / total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                } else if (qr.found.isEmpty()) {
                    Text(tr(Str.QrBillNone), style = MaterialTheme.typography.bodyMedium, color = LocalNougatPalette.current.muted)
                }
            }
        },
        confirmButton = { Button(onClick = state::startQrBillCreate) { Text(tr(Str.QrBillCreate)) } },
        dismissButton = { TextButton(onClick = state::closeQrBill) { Text(tr(Str.Close)) } },
    )
}

@Composable
private fun FoundBillCard(state: ViewerState, found: FoundQrBill, showPage: Boolean) {
    val bill = found.bill
    val amount = bill.amount?.let { "${bill.currency} ${SwissQr.formatAmount(it)}" }
    val rows = buildList<Triple<String, String, String?>> {
        add(Triple(tr(Str.QrBillAmount), amount ?: "${bill.currency} (${tr(Str.QrBillAmountOpen)})", bill.amount?.let(SwissQr::formatAmount)))
        add(Triple(tr(Str.QrBillAccount), SwissQr.formatIban(bill.iban), SwissQr.formatIban(bill.iban)))
        add(Triple(tr(Str.QrBillPayableTo), bill.creditor.lines().joinToString("\n"), bill.creditor.name))
        if (bill.reference.isNotBlank()) add(Triple(tr(Str.QrBillReference), SwissQr.formatReference(bill.reference), SwissQr.formatReference(bill.reference)))
        if (bill.message.isNotBlank()) add(Triple(tr(Str.QrBillMessage), bill.message, bill.message))
        bill.debtor?.takeIf { !it.isEmpty }?.let { add(Triple(tr(Str.QrBillPayableBy), it.lines().joinToString("\n"), null)) }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showPage) Text(tr(Str.QrBillPage, found.page + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (!found.valid) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Text(tr(Str.QrBillWarning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            for ((label, shown, copy) in rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalNougatPalette.current.muted)
                        Text(shown, style = MaterialTheme.typography.bodyLarge, fontWeight = if (label == tr(Str.QrBillAmount)) FontWeight.SemiBold else null)
                    }
                    if (copy != null) {
                        IconButton(onClick = { state.copyValue(label, copy) }) {
                            Icon(NougatIcons.Copy, contentDescription = tr(Str.QrBillCopy), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            TextButton(onClick = { state.copyValue(tr(Str.QrBill), rows.joinToString("\n") { (label, shown, _) -> "$label : ${shown.replace("\n", ", ")}" }) }) {
                Icon(NougatIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(tr(Str.QrBillCopyAll), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QrBillCreateDialog(state: ViewerState) {
    val qr = state.qrBill
    val f = qr.form
    fun update(change: (QrBillForm) -> QrBillForm) {
        qr.form = change(qr.form)
    }

    @Composable
    fun field(
        value: String,
        label: String,
        modifier: Modifier = Modifier.fillMaxWidth(),
        hint: String? = null,
        keyboard: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        onChange: (String) -> Unit,
    ) = OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = keyboard,
        modifier = modifier,
    )

    @Composable
    fun heading(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

    AlertDialog(
        onDismissRequest = state::closeQrBill,
        icon = { Icon(NougatIcons.QrCode, contentDescription = null) },
        title = { Text(tr(Str.QrBillCreateTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(Str.QrBillCreateHint), style = MaterialTheme.typography.bodyMedium, color = LocalNougatPalette.current.muted)
                heading(tr(Str.QrBillYou))
                field(
                    f.iban, tr(Str.QrBillIban),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                ) { v -> update { it.copy(iban = v.take(42)) } }
                field(f.name, tr(Str.QrBillName)) { v -> update { it.copy(name = v.take(70)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(f.street, tr(Str.QrBillStreet), Modifier.weight(3f)) { v -> update { it.copy(street = v.take(70)) } }
                    field(f.number, tr(Str.QrBillNumber), Modifier.weight(1f)) { v -> update { it.copy(number = v.take(16)) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(f.postalCode, tr(Str.QrBillPostalCode), Modifier.weight(1f)) { v -> update { it.copy(postalCode = v.take(16)) } }
                    field(f.town, tr(Str.QrBillTown), Modifier.weight(2f)) { v -> update { it.copy(town = v.take(35)) } }
                    field(
                        f.country, tr(Str.QrBillCountry), Modifier.weight(1f),
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    ) { v -> update { it.copy(country = v.filter(Char::isLetter).take(2).uppercase()) } }
                }

                heading(tr(Str.QrBillAmount))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    field(
                        f.amount, tr(Str.QrBillAmount), Modifier.weight(1f), hint = tr(Str.QrBillAmountHint),
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    ) { v -> update { it.copy(amount = v.take(20)) } }
                    for (currency in listOf("CHF", "EUR")) {
                        FilterChip(selected = f.currency == currency, onClick = { update { it.copy(currency = currency) } }, label = { Text(currency) })
                    }
                }
                field(
                    f.reference, tr(Str.QrBillReference), hint = tr(Str.QrBillReferenceHint),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                ) { v -> update { it.copy(reference = v.take(40)) } }
                field(
                    f.message, tr(Str.QrBillMessageField),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                ) { v -> update { it.copy(message = v.take(140)) } }

                CheckRow(tr(Str.QrBillAddDebtor), f.withDebtor) { on -> update { it.copy(withDebtor = on) } }
                if (f.withDebtor) {
                    val d = f.debtor
                    field(d.name, tr(Str.QrBillName)) { v -> update { it.copy(debtor = it.debtor.copy(name = v.take(70))) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(d.street, tr(Str.QrBillStreet), Modifier.weight(3f)) { v -> update { it.copy(debtor = it.debtor.copy(street = v.take(70))) } }
                        field(d.number, tr(Str.QrBillNumber), Modifier.weight(1f)) { v -> update { it.copy(debtor = it.debtor.copy(number = v.take(16))) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(d.postalCode, tr(Str.QrBillPostalCode), Modifier.weight(1f)) { v -> update { it.copy(debtor = it.debtor.copy(postalCode = v.take(16))) } }
                        field(d.town, tr(Str.QrBillTown), Modifier.weight(2f)) { v -> update { it.copy(debtor = it.debtor.copy(town = v.take(35))) } }
                        field(
                            d.country, tr(Str.QrBillCountry), Modifier.weight(1f),
                            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        ) { v -> update { it.copy(debtor = it.debtor.copy(country = v.filter(Char::isLetter).take(2).uppercase())) } }
                    }
                }

                if (qr.lastPageFits) {
                    ChoiceRow(tr(Str.QrBillPlaceLast), !f.newPage) { update { it.copy(newPage = false) } }
                    ChoiceRow(tr(Str.QrBillPlaceNew), f.newPage) { update { it.copy(newPage = true) } }
                }
                heading(tr(Str.QrBillLanguage))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (lang in QrLanguage.entries) {
                        FilterChip(selected = f.language == lang, onClick = { update { it.copy(language = lang) } }, label = { Text(lang.code) })
                    }
                }
                for (problem in qr.problems) {
                    Text(problemText(problem), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = state::addQrBill) { Text(tr(Str.QrBillAdd)) } },
        dismissButton = { TextButton(onClick = state::closeQrBill) { Text(tr(Str.Cancel)) } },
    )
}

private fun problemText(p: QrProblem) = tr(
    when (p) {
        QrProblem.Iban -> Str.QrErrIban
        QrProblem.IbanCountry -> Str.QrErrCountry
        QrProblem.NeedQrr -> Str.QrErrNeedQrr
        QrProblem.QrrNeedsQrIban -> Str.QrErrQrrNeedsQrIban
        QrProblem.Reference -> Str.QrErrReference
        QrProblem.Amount -> Str.QrErrAmount
        QrProblem.Creditor -> Str.QrErrCreditor
        QrProblem.Debtor -> Str.QrErrDebtor
    },
)
