package ru.ymlstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductQuickDetails(product: Product, template: Template, settings: Settings) {
    val fields = template.cardFields(settings).filter {
        it.quickAccess && it.target !in listOf("id", "name", "price")
    }
    if (fields.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fields.forEach { field ->
            val raw = product.values[field.id].orEmpty().trim()
            val missing = raw.isEmpty()
            val display = when {
                missing -> "не заполнено"
                field.type == "boolean" -> when (raw.lowercase()) { "true" -> "Да"; "false" -> "Нет"; else -> raw }
                field.target == "vat" -> canonicalVat(raw)?.let {
                    if (it == "NO_VAT") "Без НДС" else it.removePrefix("VAT_") + "%"
                } ?: raw
                field.target == "oksm" -> countryValue(raw)?.name?.takeIf { it.isNotBlank() } ?: raw
                field.target == "okei" -> okeiValue(raw)?.name ?: raw
                field.target in listOf("beginDate", "endDate") -> raw.replace('T', ' ')
                field.options.isNotEmpty() -> field.options.firstOrNull { it.value == raw }?.label?.takeIf { it.isNotBlank() } ?: raw
                else -> raw
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), shape = RoundedCornerShape(5.dp)) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(field.label); append(": ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium,
                        color = if (missing && field.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
                        append(display)
                        if (!missing && field.unit.isNotBlank()) { append(" "); append(field.unit) }
                    }
                }, modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
