package ru.ymlstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun FolderGrid(project: Project, busy: Boolean, modifier: Modifier = Modifier,
    disabledIds: Set<String> = emptySet(), tagPrefix: String = "folder-", includeDesktop: Boolean = true, open: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    val folders = remember(project.folders, search, includeDesktop) {
        ((if (includeDesktop) listOf(ProductFolder("", "Рабочий стол")) else emptyList()) + project.folders).filter { it.name.contains(search.trim(), ignoreCase = true) }
    }
    val counts = remember(project.products) { project.products.groupingBy { it.folderId }.eachCount() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(search, { search = it }, label = { Text("Поиск папки") }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (folders.isEmpty()) Text("Папки не найдены", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            items(folders, key = { it.id }) { folder ->
                val enabled = !busy && folder.id !in disabledIds
                OutlinedCard(onClick = { open(folder.id) }, enabled = enabled, modifier = Modifier.testTag(tagPrefix + folder.id)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FolderSymbol(folder.id.isEmpty(), enabled)
                        Text(folder.name, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("Товаров: ${counts[folder.id] ?: 0}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun FolderSymbol(desktop: Boolean, enabled: Boolean) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    Canvas(Modifier.size(56.dp, 44.dp)) {
        if (desktop) {
            drawRoundRect(color, size = Size(size.width, size.height * 0.7f), cornerRadius = CornerRadius(4.dp.toPx()))
            drawRect(color, topLeft = Offset(size.width * 0.44f, size.height * 0.7f), size = Size(size.width * 0.12f, size.height * 0.2f))
            drawRoundRect(color, topLeft = Offset(size.width * 0.25f, size.height * 0.9f), size = Size(size.width * 0.5f, size.height * 0.1f), cornerRadius = CornerRadius(2.dp.toPx()))
        } else {
            drawRoundRect(color, size = Size(size.width * 0.48f, size.height * 0.35f), cornerRadius = CornerRadius(4.dp.toPx()))
            drawRoundRect(color, topLeft = Offset(0f, size.height * 0.18f), size = Size(size.width, size.height * 0.82f), cornerRadius = CornerRadius(4.dp.toPx()))
        }
    }
}
