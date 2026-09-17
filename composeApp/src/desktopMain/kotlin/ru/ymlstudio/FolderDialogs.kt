package ru.ymlstudio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun FolderNameDialog(project: Project, folderId: String, busy: Boolean,
    dismiss: () -> Unit, apply: (Project) -> Unit) {
    val existing = project.folders.firstOrNull { it.id == folderId }
    var name by remember(folderId) { mutableStateOf(existing?.name.orEmpty()) }
    val newId = remember(folderId) { java.util.UUID.randomUUID().toString() }
    val result = remember(project, name) { runCatching {
        if (existing == null) project.createFolder(newId, name) else project.renameFolder(folderId, name)
    } }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(if (existing == null) "Новая папка" else "Переименовать папку") },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("Название папки") }, singleLine = true, enabled = !busy)
            if (name.isNotBlank()) result.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(onClick = { apply(result.getOrThrow()) }, enabled = !busy && result.isSuccess) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Отмена") } })
}

@Composable internal fun MoveProductsDialog(project: Project, ids: Set<String>, busy: Boolean,
    dismiss: () -> Unit, apply: (String) -> Unit) {
    val currentFolders = project.products.filter { it.id in ids }.map { it.folderId }.toSet()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Переместить ${ids.size} товаров") },
        text = { FolderGrid(project, busy, Modifier.width(560.dp).height(400.dp),
            disabledIds = if (currentFolders.size == 1) currentFolders else emptySet(), tagPrefix = "move-to-", open = apply)
        }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Отмена") } })
}
