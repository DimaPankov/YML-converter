package ru.ymlstudio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JOptionPane

private val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Background: Color @Composable get() = MaterialTheme.colorScheme.background
private val pages = listOf("Товары", "Конструктор форм", "Настройки каталога", "Экспорт YML")

fun main(args: Array<String>) {
    val path = args.indexOf("--data-dir").takeIf { it >= 0 }?.let {
        require(it + 1 < args.size) { "После --data-dir укажите папку данных" }; Path.of(args[it + 1])
    } ?: defaultDataDirectory()
    var repository: ProjectRepository? = null
    val initial = try {
        repository = ProjectRepository(path)
        repository.load().withMinimalPreset()
    } catch (e: Exception) {
        repository?.close()
        JOptionPane.showMessageDialog(null, "Не удалось открыть данные:\n$path\n\n${e.message}\n\nСуществующий проект не изменён.", "YML Студия", JOptionPane.ERROR_MESSAGE)
        return
    }
    val repo = repository
    // Used by packaging/CI to check bundled JVM modules and storage without opening a window.
    if ("--smoke-test" in args) {
        try { checkShape(initial); javax.imageio.ImageIO.getReaderFormatNames(); println("YML_STUDIO_SMOKE_OK") } finally { repo.close() }
        return
    }
    application {
        var closeRequest by remember { mutableStateOf(false) }
        Window(onCloseRequest = { closeRequest = true }, title = "YML Студия", state = rememberWindowState(width = 1280.dp, height = 860.dp)) {
            window.minimumSize = java.awt.Dimension(960, 650)
                CompositionLocalProvider(LocalImageDirectory provides repo.directory) {
                    Studio(repo, initial, closeRequest, { closeRequest = false }) { repo.close(); exitApplication() }
                }
        }
    }
}

private fun chooseFile(title: String, saveName: String? = null): Path? {
    val dialog = FileDialog(null as Frame?, title, if (saveName == null) FileDialog.LOAD else FileDialog.SAVE)
    return try {
        if (saveName != null) dialog.file = saveName
        dialog.isVisible = true
        dialog.file?.let { Path.of(dialog.directory, it) }
    } finally { dialog.dispose() }
}

@Composable
internal fun Studio(repo: ProjectRepository, initial: Project, closeRequest: Boolean, resetClose: () -> Unit, exit: () -> Unit) {
    var saved by remember { mutableStateOf(initial) }
    var draft by remember { mutableStateOf(initial) }
    var page by remember { mutableIntStateOf(0) }
    var productId by remember { mutableStateOf<String?>(null) }
    var templateId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var createProduct by remember { mutableStateOf(false) }
    var copySource by remember { mutableStateOf<Product?>(null) }
    var copyDraft by remember { mutableStateOf<Product?>(null) }
    var ymlImport by remember { mutableStateOf<YmlImport?>(null) }
    var importAsTemplate by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var bulkEdit by remember { mutableStateOf(false) }
    val selectedTemplateId = saved.products.firstOrNull { it.id in selectedIds }?.templateId
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val dirty = draft != saved
    fun navigate(next: Int) {
        val action = { draft = saved; page = next; productId = null; templateId = null; selectedIds = emptySet() }
        if (dirty) confirm = "Отменить несохранённые изменения и перейти в другой раздел?" to action else action()
    }
    fun work(success: String? = null, operation: suspend () -> Unit) {
        if (busy) return
        focusManager.clearFocus(force = true)
        busy = true
        scope.launch {
            try { operation(); if (success != null) message = success }
            catch (e: Exception) { message = e.message ?: "Не удалось выполнить операцию" }
            finally { busy = false }
        }
    }
    fun commit(next: Project = draft, showSuccess: Boolean = true) {
        work(if (showSuccess) "Изменения сохранены" else null) {
            val normalized = next.withDeliveryDaysMapping()
            withContext(Dispatchers.IO) { repo.save(normalized) }
            saved = normalized; draft = normalized; productId = null; templateId = null
            selectedIds = emptySet(); bulkEdit = false
        }
    }
    fun loadYml(asTemplate: Boolean) {
        chooseFile(if (asTemplate) "Загрузить форму из YML" else "Загрузить товары из YML")?.let { source -> work {
            val preview = withContext(Dispatchers.IO) { readYmlImport(source) }
            importAsTemplate = asTemplate
            ymlImport = preview
        } }
    }
    fun changeTheme(dark: Boolean) {
        work {
            val next = saved.copy(settings = saved.settings.copy(darkTheme = dark))
            withContext(Dispatchers.IO) { repo.save(next) }
            saved = next
            draft = draft.copy(settings = draft.settings.copy(darkTheme = dark))
        }
    }
    LaunchedEffect(closeRequest) {
        if (closeRequest) {
            resetClose()
            if (busy) message = "Дождитесь завершения операции."
            else if (dirty) confirm = "Закрыть программу без сохранения изменений?" to exit
            else exit()
        }
    }
    StudioTheme(draft.settings.darkTheme) {
    Row(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.width(235.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YML Студия", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Green)
            Spacer(Modifier.height(18.dp))
            Surface(color = Background, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(saved.settings.name.ifBlank { "Мой каталог" }, fontWeight = FontWeight.Medium)
                    Text("${saved.products.size} товаров · RUB", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            pages.forEachIndexed { i, label ->
                TextButton(onClick = { navigate(i) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(containerColor = if (page == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                    Text(label, Modifier.fillMaxWidth().padding(vertical = 6.dp), color = if (page == i) Green else Muted)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (draft.settings.darkTheme) "Тёмная тема" else "Светлая тема", Modifier.weight(1f), color = Muted)
                Switch(checked = draft.settings.darkTheme, onCheckedChange = ::changeTheme,
                    enabled = !busy, modifier = Modifier.testTag("sidebar-theme"))
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Рабочее пространство  /  ${pages[page]}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(if (busy) "Выполняется операция…" else if (dirty) "Есть несохранённые изменения" else "Все изменения сохранены", color = if (dirty) MaterialTheme.colorScheme.tertiary else Green, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.weight(1f)) {
                when {
                    productId != null -> {
                        val p = draft.products.first { it.id == productId }
                        val t = draft.templates.first { it.id == p.templateId }
                        ProductEditor(p, t, draft, busy, { updated -> draft = draft.copy(products = draft.products.map { if (it.id == p.id) updated else it }) },
                            { commit() }, { navigate(page) }, { field, name ->
                                val category = newSupplierCategory(draft.categories, name)
                                draft = draft.copy(categories = draft.categories + category, products = draft.products.map {
                                    if (it.id == p.id) it.copy(values = it.values + (field.id to category.id)) else it
                                })
                            })
                    }
                    templateId != null -> {
                        val t = draft.templates.first { it.id == templateId }
                        TemplateEditor(t, busy, { updated -> draft = draft.copy(templates = draft.templates.map { if (it.id == t.id) updated else it }) },
                            { commit(saved.updateTemplate(t)) }, { navigate(page) }, { title, action -> confirm = title to action })
                    }
                    page == 0 -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Heading("Товары") {
                            if (selectedIds.isNotEmpty()) {
                                OutlinedButton(onClick = { commit(saved.deleteProducts(selectedIds), showSuccess = false) }, enabled = !busy,
                                    modifier = Modifier.testTag("bulk-delete")) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                                Button(onClick = { bulkEdit = true }, enabled = !busy, modifier = Modifier.testTag("bulk-edit")) { Text("Изменить") }
                            } else Button(onClick = { createProduct = true }, enabled = !busy && saved.templates.isNotEmpty()) { Text("+ Добавить товар") }
                        }
                        OutlinedButton(onClick = { loadYml(false) }, enabled = !busy) { Text("Загрузить товары из YML") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Input(search, { search = it; selectedIds = emptySet() }, "Поиск по названию или артикулу", Modifier.weight(1f))
                            Choice(filter, listOf("" to "Все формы") + saved.templates.map { it.id to it.name }, "Форма", Modifier.width(260.dp)) { filter = it; selectedIds = emptySet() }
                        }
                        val products = saved.products.filter { p ->
                            val v = p.valuesFor(saved.templates.first { it.id == p.templateId })
                            (filter.isEmpty() || filter == p.templateId) && (search.isBlank() || listOf(v["name"], v["id"]).any { it.orEmpty().contains(search, true) })
                        }
                        if (selectedIds.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Выбрано: ${selectedIds.size}", color = Green)
                            TextButton({ selectedIds = products.filter { it.templateId == selectedTemplateId }.map { it.id }.toSet() }, enabled = !busy) { Text("Выбрать все этой формы") }
                            TextButton({ selectedIds = emptySet() }, enabled = !busy) { Text("Снять выбор") }
                        }
                        if (products.isEmpty()) Empty("Товары не найдены")
                        val productListState = rememberLazyListState()
                        LaunchedEffect(products.map { it.id }) {
                            if (products.isNotEmpty()) productListState.scrollToItem(products.lastIndex)
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("products-list"),
                            state = productListState, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(products, key = { it.id }) { p ->
                                val t = saved.templates.first { it.id == p.templateId }; val v = p.valuesFor(t)
                                ProductContextMenu(p.id, p.id in selectedIds, selectedTemplateId == null || selectedTemplateId == p.templateId, busy,
                                    selectionMode = selectedIds.isNotEmpty(),
                                    toggleSelection = { selectedIds = if (p.id in selectedIds) selectedIds - p.id else selectedIds + p.id },
                                    edit = { draft = saved; productId = p.id },
                                    copy = {
                                        try {
                                            copyDraft = saved.duplicateProduct(p, newId())
                                            copySource = p
                                        } catch (e: Exception) { message = e.message }
                                    },
                                    delete = { confirm = "Удалить товар из каталога?" to { commit(saved.copy(products = saved.products.filter { it.id != p.id }), showSuccess = false) } }) {
                                Panel(selected = p.id in selectedIds) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(v["name"].orEmpty().ifBlank { "Без названия" }, fontWeight = FontWeight.SemiBold)
                                            Text("${v["id"].orEmpty().ifBlank { "Без артикула" }}  ·  ${t.name}  ·  ${p.pictures.size} фото", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(v["price"].orEmpty().ifBlank { "—" } + " ₽", fontWeight = FontWeight.Medium)
                                    }
                                    ProductQuickDetails(p, t, saved.settings)
                                }
                                }
                            }
                        }
                    }
                    page == 1 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Heading("Конструктор форм") {
                            Button(onClick = { val t = defaultTemplate().copy(id = newId(), name = "Новая форма"); draft = saved.copy(templates = saved.templates + t); templateId = t.id }, enabled = !busy) { Text("+ Создать форму") }
                        }
                        OutlinedButton(onClick = { loadYml(true) }, enabled = !busy) { Text("Загрузить форму из YML") }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(saved.templates, key = { it.id }) { t -> Panel {
                                Text(t.name, style = MaterialTheme.typography.titleMedium)
                                Text(t.description, color = Muted)
                                Text("${t.fields.size} полей · ${saved.products.count { it.templateId == t.id }} товаров", color = Muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { draft = saved; templateId = t.id }, enabled = !busy) { Text("Изменить") }
                                    TextButton(onClick = { commit(saved.copy(templates = saved.templates + t.copy(id = newId(), name = t.name + " (копия)"))) }, enabled = !busy) { Text("Копировать") }
                                    TextButton(onClick = {
                                        if (saved.products.any { it.templateId == t.id }) message = "Форма используется товарами. Сначала удалите связанные товары."
                                        else confirm = "Удалить форму «${t.name}»?" to { commit(saved.copy(templates = saved.templates.filter { it.id != t.id }), showSuccess = false) }
                                    }, enabled = !busy) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                                }
                            } }
                        }
                    }
                    page == 2 -> SettingsEditor(draft, busy, { draft = it }, { commit() }, repo.directory.toString(), {
                        chooseFile("Сохранить резервную копию", "yml-project-backup.zip")?.let { destination -> work("Резервная копия сохранена") { withContext(Dispatchers.IO) { repo.backup(destination) } } }
                    }, {
                        confirm = "Импорт заменит текущий каталог. Будет создана резервная копия сохранённых данных. Несохранённые изменения будут отменены. Продолжить?" to {
                            chooseFile("Откройте project.json (рядом должна находиться папка images)")?.let { source -> work("Проект импортирован") {
                                val imported = withContext(Dispatchers.IO) { repo.importProject(source).withMinimalPreset() }; saved = imported; draft = imported
                            } }
                        }
                    })
                    else -> ExportScreen(saved, busy) { bundle, allowInvalid ->
                        chooseFile("Сохранить экспорт", if (bundle) "catalog-with-images.zip" else "catalog.yml")?.let { destination -> work("Экспорт сохранён: $destination") { withContext(Dispatchers.IO) { repo.export(saved, destination, bundle, allowInvalid) } } }
                    }
                }
                if (busy) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    if (bulkEdit && selectedIds.isNotEmpty()) BulkEditDialog(saved, selectedIds, busy, { bulkEdit = false }) { changes, regenerateArticles, photos ->
        work {
            val next = saved.updateProducts(selectedIds, changes, regenerateArticles, photos)
            withContext(Dispatchers.IO) { repo.save(next) }
            saved = next; draft = next; selectedIds = emptySet(); bulkEdit = false
        }
    }
    copySource?.let { source ->
        val template = saved.templates.first { it.id == source.templateId }
        val copy = copyDraft!!
        val quickMode = template.copyPattern.isNotBlank()
        var quickText by remember(copy.id) { mutableStateOf(if (quickMode) template.copyText(copy) else "") }
        val quickResult = remember(quickText, copy, template) {
            runCatching { copy.copy(values = copy.values + if (quickMode) template.copyValues(quickText) else emptyMap()) }
        }
        val preview = quickResult.getOrNull() ?: copy
        val nameField = template.fields.firstOrNull { it.target == "name" }
        AlertDialog(onDismissRequest = { if (!busy) copySource = null },
            title = { Text("Копировать товар") },
            text = {
                Column(Modifier.width(480.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState()).testTag("copy-fields"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (quickMode) {
                        Input(quickText, { if (!busy) quickText = it }, "Быстрое заполнение", multiline = true)
                        quickResult.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (quickResult.isSuccess) template.fields.filter { it.copyVariable.isNotBlank() }.forEach { field ->
                            Text("${field.label}: ${preview.values[field.id].orEmpty()}", Modifier.testTag("copy-preview-${field.id}"))
                        }
                    } else template.copyFields(saved.settings).forEach { field ->
                        key(field.id) {
                            FieldValueInput(field, copy.values[field.id].orEmpty(), if (field.target == "name") "Новое название товара" else field.label) { value ->
                                if (!busy) copyDraft = copy.copy(values = copy.values + (field.id to value))
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(enabled = !busy && quickResult.isSuccess && !preview.values[nameField?.id].isNullOrBlank(), onClick = {
                work {
                    val result = if (quickMode) saved.finishQuickProductCopy(copy, quickText) else saved.finishProductCopy(copy, copy.values)
                    val next = saved.copy(products = saved.products + result)
                    withContext(Dispatchers.IO) { repo.save(next) }
                    saved = next
                    draft = next
                    search = ""
                    copySource = null
                }
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { copySource = null }) { Text("Отмена") } })
    }
    ymlImport?.let { preview ->
        YmlImportDialog(preview, importAsTemplate, busy, { ymlImport = null }) { index, name ->
            work {
                if (importAsTemplate) {
                    val template = preview.asTemplate(index, name)
                    val next = saved.copy(templates = saved.templates + template)
                    withContext(Dispatchers.IO) { repo.save(next) }
                    saved = next; draft = next; templateId = template.id
                } else {
                    val result = preview.addProductsTo(saved)
                    withContext(Dispatchers.IO) { repo.save(result.project) }
                    saved = result.project; draft = result.project; search = ""; filter = ""
                    message = "Добавлено товаров: ${preview.products.size}." +
                        if (result.changedArticles > 0) " Новые артикулы назначены: ${result.changedArticles}." else ""
                }
                ymlImport = null
            }
        }
    }
    if (createProduct) AlertDialog(onDismissRequest = { createProduct = false }, title = { Text("Выберите форму товара") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            saved.templates.forEach { t -> TextButton(onClick = {
                try {
                    val p = saved.createProduct(newId(), t)
                    draft = saved.copy(products = saved.products + p)
                    productId = p.id
                    createProduct = false
                } catch (e: Exception) { message = e.message }
            }) { Text(t.name) } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { createProduct = false }) { Text("Отмена") } })
    confirm?.let { (text, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Подтверждение") }, text = { Text(text) },
        confirmButton = { Button(onClick = { confirm = null; action() }) { Text("Продолжить") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("YML Студия") }, text = { Text(text, Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { message = null }) { Text("ОК") } }) }
    }
}

@Composable private fun Heading(title: String, subtitle: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium) }
        actions()
    }
}
@Composable private fun Panel(selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun Empty(title: String) { Panel { Text(title, style = MaterialTheme.typography.titleLarge) } }
@Composable private fun Input(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), multiline: Boolean = false) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 3 else 1, shape = RoundedCornerShape(8.dp))
}
@Composable private fun Choice(value: String, options: List<Pair<String, String>>, label: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = Muted); Text(options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "Выберите…" }) }
            Text("▾")
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 400.dp)) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onChange(key); expanded = false }) }
        }
    }
}
@Composable private fun Toggle(value: Boolean, label: String, onChange: (Boolean) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value, onChange); Text(label) } }
private fun <T> List<T>.move(index: Int, delta: Int): List<T> = toMutableList().apply { if (index + delta in indices) add(index + delta, removeAt(index)) }

@Composable private fun ProductEditor(p: Product, t: Template, project: Project, busy: Boolean, update: (Product) -> Unit, save: () -> Unit, cancel: () -> Unit, createCategory: (Field, String) -> Unit) {
    var categoryField by remember(p.id) { mutableStateOf<Field?>(null) }
    var categoryName by remember(p.id) { mutableStateOf("") }
    categoryField?.let { field ->
        AlertDialog(onDismissRequest = { categoryField = null }, title = { Text("Новая категория поставщика") },
            text = { Input(categoryName, { categoryName = it }, "Название категории") },
            confirmButton = { Button(onClick = { createCategory(field, categoryName); categoryField = null }, enabled = categoryName.isNotBlank() && !busy) { Text("Создать и выбрать") } },
            dismissButton = { TextButton({ categoryField = null }) { Text("Отмена") } })
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Карточка товара", t.name) { OutlinedButton(cancel, enabled = !busy) { Text("Отмена") }; Button(save, enabled = !busy) { Text("Сохранить") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(t.cardFields(project.settings), key = { it.id }) { f ->
                val value = p.values[f.id].orEmpty(); val label = f.label + (if (f.required) " *" else "") + if (f.unit.isNotEmpty()) " (${f.unit})" else ""
                val change: (String) -> Unit = { update(p.copy(values = p.values + (f.id to it))) }
                FieldValueInput(f, value, label, onChange = change)
            }
            item { Panel {
                Text("Фотографии (${p.pictures.size}/10)", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ update(p.copy(pictures = p.pictures + Picture())) }, enabled = !busy && p.pictures.size < 10) { Text("Добавить ссылку") }
                }
            } }
            items(p.pictures.size) { index ->
                val pic = p.pictures[index]
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (index == 0) "Основное фото" else "Фото ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        TextButton({ update(p.copy(pictures = p.pictures.filterIndexed { i, _ -> i != index })) },
                            enabled = !busy, modifier = Modifier.testTag("delete-photo-$index")) {
                            Text("Удалить фото", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (pic.file.isNotEmpty()) {
                        LocalPhoto(picture = pic)
                        Text("${pic.name.ifBlank { pic.file }} · ${pic.width} × ${pic.height} px", color = Muted)
                        PhotoLocation(pic)
                    }
                    Input(pic.url, { url -> update(p.copy(pictures = p.pictures.mapIndexed { i, item -> if (i == index) item.copy(url = url) else item })) }, "Публичная ссылка HTTP/HTTPS")
                    if (pic.file.isNotEmpty() && pic.url.isBlank()) Text("Адрес для экспорта: ${imageUrl(pic, project.settings).ifEmpty { "не задан" }}", color = Muted)
                    Row {
                        TextButton({ update(p.copy(pictures = p.pictures.move(index, -1))) }, enabled = index > 0) { Text("↑ Выше") }
                        TextButton({ update(p.copy(pictures = p.pictures.move(index, 1))) }, enabled = index < p.pictures.lastIndex) { Text("↓ Ниже") }
                    }
                }
            }
        }
    }
}

@Composable private fun LocalPhoto(picture: Picture) {
    // The filename and dimensions remain visible even if an imported file is unavailable.
    // Previews are implemented through a composition-local repository directory below.
    val directory = LocalImageDirectory.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, directory, picture.file) {
        value = withContext(Dispatchers.IO) { runCatching {
            val source = javax.imageio.ImageIO.read(directory.resolve("images").resolve(picture.file).toFile())
            val scale = minOf(1.0, 360.0 / maxOf(source.width, source.height))
            val thumb = java.awt.image.BufferedImage((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB)
            thumb.createGraphics().let { g -> try { g.drawImage(source, 0, 0, thumb.width, thumb.height, null) } finally { g.dispose() } }
            val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(thumb, "png", it) }.toByteArray()
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull() }
    }
    bitmap?.let { Image(it, picture.name.ifBlank { "Фотография товара" }, Modifier.heightIn(max = 180.dp).widthIn(max = 360.dp)) }
}
internal val LocalImageDirectory = staticCompositionLocalOf { defaultDataDirectory() }

@Composable internal fun PhotoLocation(picture: Picture) {
    val location = if (picture.file.isNotBlank()) LocalImageDirectory.current.resolve("images").resolve(picture.file).toAbsolutePath().normalize().toString()
        else picture.url
    if (location.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
        Text(location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun YmlImportDialog(preview: YmlImport, asTemplate: Boolean, busy: Boolean,
    dismiss: () -> Unit, apply: (Int, String) -> Unit) {
    var selected by remember(preview) { mutableIntStateOf(0) }
    var name by remember(preview) { mutableStateOf(preview.template.name) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (asTemplate) "Загрузить форму" else "Загрузить товары") },
        text = {
            Column(Modifier.width(480.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("В файле товаров: ${preview.products.size}.")
                if (asTemplate) {
                    Input(name, { name = it }, "Название новой формы")
                    Text("Форм: ${preview.formGroups.size}")
                    if (preview.formGroups.size > 1) {
                        Choice(selected.toString(), preview.formGroups.mapIndexed { index, group ->
                            group.productIndex.toString() to "Форма ${index + 1} · ${group.productCount} товаров · ${preview.products[group.productIndex].values["name"].orEmpty().ifBlank { "Без названия" }}"
                        }, "Форма из файла") { selected = it.toInt() }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { apply(selected, name) }, enabled = !busy && (!asTemplate || name.isNotBlank())) { Text(if (asTemplate) "Добавить форму" else "Добавить товары") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Отмена") } })
}

@Composable private fun TemplateEditor(t: Template, busy: Boolean, update: (Template) -> Unit, save: () -> Unit, cancel: () -> Unit, confirm: (String, () -> Unit) -> Unit) {
    val patternError = remember(t) { t.copyPatternError() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Редактор формы") { OutlinedButton(cancel, enabled = !busy) { Text("Отмена") }; Button(save, enabled = !busy && patternError == null) { Text("Сохранить") } }
        if (patternError != null) Text(patternError, color = MaterialTheme.colorScheme.error)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Input(t.name, { update(t.copy(name = it)) }, "Название формы") }
            item { Panel {
                Text("Заполнение одной строкой", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = t.copyPattern,
                    onValueChange = { update(t.copy(copyPattern = it)) },
                    label = { Text("Шаблон для копирования товара") },
                    placeholder = { Text("p1{Футболка RLS, p2, p3/p4}") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !busy
                )
                Text("Свяжите p1, p2… с полями ниже.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } }
            item { Input(t.description, { update(t.copy(description = it)) }, "Описание формы", multiline = true) }
            if (t.defaultPictures.isNotEmpty()) item { Panel {
                Text("Фотографии новых товаров: ${t.defaultPictures.size}", fontWeight = FontWeight.Medium)
                t.defaultPictures.forEach { Text(it.url.ifBlank { it.name }, color = Muted) }
                TextButton({ update(t.copy(defaultPictures = emptyList())) }, enabled = !busy) { Text("Убрать фотографии из формы") }
            } }
            items(t.fields.size, key = { t.fields[it].id }) { index ->
                val f = t.fields[index]
                fun change(next: Field) { update(t.copy(fields = t.fields.mapIndexed { i, field -> if (i == index) next else field })) }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${f.label}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton({ update(t.copy(fields = t.fields.move(index, -1))) }, enabled = index > 0) { Text("↑") }
                        TextButton({ update(t.copy(fields = t.fields.move(index, 1))) }, enabled = index < t.fields.lastIndex) { Text("↓") }
                        TextButton({ confirm("Удалить поле из формы и её товаров?") { update(t.copy(fields = t.fields.filterIndexed { i, _ -> i != index })) } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Input(f.label, { change(f.copy(label = it)) }, "Название поля", Modifier.weight(1f))
                        Choice(f.target, listOf("param" to "Характеристика (param)") + fieldDefinitions.filter { it.target != "categoryId" }.map { it.target to it.label }, "Поле YML", Modifier.weight(1f)) { target ->
                            val def = fieldDefinitions.firstOrNull { it.target == target }
                            change(f.copy(target = target, type = def?.type ?: f.type, required = def?.required ?: f.required, dictionary = "", inputMode = "auto", copyVariable = if (target in listOf("id", "ste")) "" else f.copyVariable))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Choice(f.type, listOf("text" to "Текст", "number" to "Число", "textarea" to "Многострочный текст", "boolean" to "Да / Нет", "datetime-local" to "Дата и время", "category" to "Категория"), "Тип данных", Modifier.weight(1f)) { change(f.copy(type = it)) }
                        if (f.target == "param") FieldValueInput(Field("unit-" + f.id, "param", "Единица характеристики", dictionary = "unit"), f.unit, modifier = Modifier.weight(1f)) { change(f.copy(unit = it)) }
                    }
                    FieldChoiceConfiguration(f, ::change)
                    Toggle(f.required, "Требовать заполнение в этой форме") { change(f.copy(required = it)) }
                    if (f.target != "name") Toggle(f.quickAccess, "Быстрый доступ: в списке и при копировании товара") { change(f.copy(quickAccess = it)) }
                    if (f.target !in listOf("id", "ste")) {
                        Input(f.copyVariable, { change(f.copy(copyVariable = it.trim())) }, "Переменная быстрого заполнения (p1, p2…)")
                    }
                }
            }
            item { AddFieldButtons(t.fields, ::newId) { update(t.copy(fields = t.fields + it)) } }
        }
    }
}

@Composable private fun SettingsEditor(project: Project, busy: Boolean, update: (Project) -> Unit, save: () -> Unit, directory: String, backup: () -> Unit, import: () -> Unit) {
    val s = project.settings
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Настройки каталога") { Button(save, enabled = !busy) { Text("Сохранить") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Panel {
                Text("Оформление", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !s.darkTheme, onClick = { update(project.copy(settings = s.copy(darkTheme = false))) }, label = { Text("Светлая") }, enabled = !busy)
                    FilterChip(selected = s.darkTheme, onClick = { update(project.copy(settings = s.copy(darkTheme = true))) }, label = { Text("Тёмная") }, enabled = !busy)
                }
            } }
            item { Panel {
                Text("Магазин", style = MaterialTheme.typography.titleLarge)
                Input(s.name, { update(project.copy(settings = s.copy(name = it))) }, "Название магазина")
                Input(s.company, { update(project.copy(settings = s.copy(company = it))) }, "Полное название компании")
                Input(s.url, { update(project.copy(settings = s.copy(url = it))) }, "Сайт магазина")
                Input(s.imageBase, { update(project.copy(settings = s.copy(imageBase = it))) }, "Публичный адрес папки фотографий, например https://example.ru/images")
                Toggle(s.useVat, "Передавать НДС (значение из справочника портала)") { update(project.copy(settings = s.copy(useVat = it))) }
            } }
            item { Panel {
                Text("Рабочие данные", style = MaterialTheme.typography.titleLarge)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(directory, color = Muted) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(backup, enabled = !busy) { Text("Резервная копия ZIP") }
                    OutlinedButton(import, enabled = !busy) { Text("Импорт project.json") }
                }
            } }
        }
    }
}

@Composable internal fun ExportScreen(project: Project, busy: Boolean, export: (Boolean, Boolean) -> Unit) {
    val report = remember(project) { validate(project) }
    var pendingBundle by remember(project) { mutableStateOf<Boolean?>(null) }
    fun requestExport(bundle: Boolean) {
        if (report.errors.isEmpty()) export(bundle, false) else pendingBundle = bundle
    }
    pendingBundle?.let { bundle ->
        AlertDialog(onDismissRequest = { pendingBundle = null }, title = { Text("Сохранить с ошибками?") },
            text = { Text("Ошибок: ${report.errors.size}. Портал может отклонить файл.") },
            confirmButton = { Button({ pendingBundle = null; export(bundle, true) }, enabled = !busy) { Text("Сохранить всё равно") } },
            dismissButton = { TextButton({ pendingBundle = null }) { Text("Отмена") } })
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Экспорт YML")
        Panel {
            Text(if (report.errors.isEmpty()) "Каталог готов к экспорту" else "Обнаружены ошибки: ${report.errors.size}", color = if (report.errors.isEmpty()) Green else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
            Text("${project.products.size} товаров · UTF-8 · RUB", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ requestExport(false) }, enabled = !busy) { Text("Сохранить YML") }
                OutlinedButton({ requestExport(true) }, enabled = !busy) { Text("YML + фотографии ZIP") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(report.errors) { Text("• $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable internal fun VatSelector(value: String, label: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    Choice(vatSelection(value), vatChoices(value), label, modifier, onChange)
}
