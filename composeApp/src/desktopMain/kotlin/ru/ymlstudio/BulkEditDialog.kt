package ru.ymlstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun BulkEditDialog(project: Project, ids: Set<String>, busy: Boolean,
    dismiss: () -> Unit, apply: (Map<String, String>, Boolean) -> Unit) {
    val selected = remember(project, ids) { project.selectedProducts(ids) }
    val template = project.templates.first { it.id == selected.first().templateId }
    val fields = template.bulkFields(project.settings)
    var changes by remember(project, ids) { mutableStateOf(emptyMap<String, String>()) }
    var regenerateArticles by remember(project, ids) { mutableStateOf(false) }
    val initial = remember(project, ids) {
        fields.associate { field -> field.id to selected.map { it.values[field.id].orEmpty() }.distinct().singleOrNull() }
    }
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { window.width.toDp() * 0.67f }
    val height = with(density) { window.height.toDp() * 0.67f }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.size(width, height).testTag("bulk-dialog"), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Изменить выбранные товары (${selected.size})", style = MaterialTheme.typography.titleLarge)
                Text(template.name, color = MaterialTheme.colorScheme.primary)
                if (template.fields.any { it.target == "id" }) Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Артикул", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        OutlinedButton({ regenerateArticles = true }, enabled = !busy && !regenerateArticles,
                            modifier = Modifier.testTag("bulk-generate-articles")) {
                            Text(if (regenerateArticles) "Генерация выбрана" else "Сгенерировать")
                        }
                        if (regenerateArticles) TextButton({ regenerateArticles = false }, enabled = !busy,
                            modifier = Modifier.testTag("bulk-cancel-articles")) { Text("Сбросить") }
                    }

                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(fields, key = { it.id }) { field ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(field.id in changes, { enabled ->
                                    changes = if (enabled) changes + (field.id to initial[field.id].orEmpty()) else changes - field.id
                                }, enabled = !busy, modifier = Modifier.testTag("bulk-field-${field.id}"))
                                Text(field.label, style = MaterialTheme.typography.titleSmall)
                            }
                            if (field.id in changes) {
                                FieldValueInput(field, changes.getValue(field.id)) { value ->
                                    if (!busy) changes = changes + (field.id to value)
                                }
                            } else Text(initial[field.id]?.ifEmpty { "Не заполнено" } ?: "Разные значения",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(dismiss, enabled = !busy) { Text("Отмена") }
                    Button({ apply(changes, regenerateArticles) }, enabled = !busy && (changes.isNotEmpty() || regenerateArticles), modifier = Modifier.testTag("bulk-apply")) {
                        Text(if (busy) "Сохранение…" else "Применить к ${selected.size} товарам")
                    }
                }
            }
        }
    }
}
