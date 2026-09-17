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
    dismiss: () -> Unit,
    apply: (Map<String, String>, Boolean, BulkPhotoChange) -> Unit) {
    val selected = remember(project, ids) { project.selectedProducts(ids) }
    val template = project.templates.first { it.id == selected.first().templateId }
    val fields = template.bulkFields(project.settings)
    var changes by remember(project, ids) { mutableStateOf(emptyMap<String, String>()) }
    var regenerateArticles by remember(project, ids) { mutableStateOf(false) }
    var photos by remember(project, ids) { mutableStateOf(BulkPhotoChange()) }
    val needsPictures = photos.mode in listOf(BulkPhotoMode.ADD, BulkPhotoMode.REPLACE)
    val photoError = when {
        needsPictures && photos.pictures.isEmpty() -> "Добавьте фотографии"
        needsPictures && photos.pictures.any { it.file.isBlank() && it.url.isBlank() } -> "Укажите ссылку фотографии"
        selected.any { photos.applyTo(it.pictures).size > 10 } -> "Максимум 10 фотографий у товара"
        else -> null
    }
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
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("bulk-fields"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Фотографии", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                BulkPhotoMode.entries.forEach { mode ->
                                    FilterChip(selected = photos.mode == mode, onClick = { photos = photos.copy(mode = mode) },
                                        enabled = !busy, modifier = Modifier.testTag("bulk-photos-${mode.name}"), label = { Text(mode.label) })
                                }
                            }
                            if (photos.mode == BulkPhotoMode.KEEP) selected.flatMap { it.pictures }.distinct().forEach { PhotoLocation(it) }
                            if (needsPictures) {
                                photos.pictures.forEachIndexed { index, picture ->
                                    Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (picture.file.isNotEmpty()) Text(picture.name.ifBlank { picture.file }, Modifier.weight(1f))
                                        else OutlinedTextField(picture.url, { url ->
                                            photos = photos.copy(pictures = photos.pictures.mapIndexed { i, old -> if (i == index) old.copy(url = url) else old })
                                        }, enabled = !busy, label = { Text("Ссылка ${index + 1}") }, modifier = Modifier.weight(1f))
                                        TextButton({ photos = photos.copy(pictures = photos.pictures.filterIndexed { i, _ -> i != index }) }, enabled = !busy) { Text("Удалить") }
                                    }
                                    if (picture.file.isNotBlank()) PhotoLocation(picture)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ photos = photos.copy(pictures = photos.pictures + Picture()) },
                                        enabled = !busy && photos.pictures.size < 10) { Text("Добавить ссылку") }
                                }
                            }
                            photoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(dismiss, enabled = !busy) { Text("Отмена") }
                    Button({ apply(changes, regenerateArticles, photos) },
                        enabled = !busy && photoError == null && (changes.isNotEmpty() || regenerateArticles || photos.mode != BulkPhotoMode.KEEP), modifier = Modifier.testTag("bulk-apply")) {
                        Text(if (busy) "Сохранение…" else "Применить к ${selected.size} товарам")
                    }
                }
            }
        }
    }
}
