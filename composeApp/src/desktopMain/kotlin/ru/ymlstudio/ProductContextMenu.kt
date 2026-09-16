package ru.ymlstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun ProductContextMenu(id: String, selected: Boolean, canSelect: Boolean, busy: Boolean,
    selectionMode: Boolean,
    toggleSelection: () -> Unit, edit: () -> Unit, copy: () -> Unit, delete: () -> Unit,
    content: @Composable () -> Unit) {
    var position by remember(id) { mutableStateOf<Offset?>(null) }
    Box(Modifier.testTag("product-row-$id").onPointerEvent(PointerEventType.Press) { event ->
        if (!busy && event.buttons.isSecondaryPressed) {
            position = event.changes.first().position
            event.changes.forEach { it.consume() }
        }
    }.clickable(enabled = !busy && (!selectionMode || canSelect)) {
        if (selectionMode) toggleSelection() else edit()
    }) {
        content()
        position?.let { point ->
            Box(Modifier.offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { position = null }, modifier = Modifier.testTag("product-menu-$id")) {
                    DropdownMenuItem(text = { Text(if (selected) "Снять выбор" else "Выбрать товар") },
                        leadingIcon = { Checkbox(checked = selected, onCheckedChange = null, enabled = canSelect && !busy) },
                        enabled = canSelect && !busy, modifier = Modifier.testTag("select-$id"),
                        onClick = { position = null; toggleSelection() })
                    if (!canSelect) Text("Можно выбрать товары только одной формы")
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Изменить") }, enabled = !busy, onClick = { position = null; edit() })
                    DropdownMenuItem(text = { Text("Копировать") }, enabled = !busy, onClick = { position = null; copy() })
                    DropdownMenuItem(text = { Text("Удалить") }, enabled = !busy, onClick = { position = null; delete() })
                }
            }
        }
    }
}
