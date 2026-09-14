package ru.ymlstudio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
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

private val Green = Color(0xFF236C50)
private val Muted = Color(0xFF738279)
private val Background = Color(0xFFF5F8F6)
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
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Green, background = Background, surface = Color.White)) {
                CompositionLocalProvider(LocalImageDirectory provides repo.directory) {
                    Studio(repo, initial, closeRequest, { closeRequest = false }) { repo.close(); exitApplication() }
                }
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
    var copyName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val dirty = draft != saved
    fun navigate(next: Int) {
        val action = { draft = saved; page = next; productId = null; templateId = null }
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
    fun commit(next: Project = draft) {
        work("Изменения сохранены") {
            withContext(Dispatchers.IO) { repo.save(next) }
            saved = next; draft = next; productId = null; templateId = null
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
    Row(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.width(235.dp).fillMaxHeight().background(Color.White).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YML Студия", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Green)
            Text("КАТАЛОГ ПОСТАВЩИКА", style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.height(18.dp))
            Surface(color = Background, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(saved.settings.name.ifBlank { "Мой каталог" }, fontWeight = FontWeight.Medium)
                    Text("${saved.products.size} товаров · RUB", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            pages.forEachIndexed { i, label ->
                TextButton(onClick = { navigate(i) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(containerColor = if (page == i) Color(0xFFE7F1EB) else Color.Transparent)) {
                    Text(label, Modifier.fillMaxWidth().padding(vertical = 6.dp), color = if (page == i) Green else Muted)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("●  Локальное приложение", color = Green, style = MaterialTheme.typography.bodySmall)
            Text("Данные сохраняются на вашем компьютере", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Версия 2.2.2 · Kotlin KMP", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Рабочее пространство  /  ${pages[page]}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(if (busy) "Выполняется операция…" else if (dirty) "Есть несохранённые изменения" else "Все изменения сохранены", color = if (dirty) Color(0xFF9C6B25) else Green, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.weight(1f)) {
                when {
                    productId != null -> {
                        val p = draft.products.first { it.id == productId }
                        val t = draft.templates.first { it.id == p.templateId }
                        ProductEditor(p, t, draft, busy, { updated -> draft = draft.copy(products = draft.products.map { if (it.id == p.id) updated else it }) },
                            { commit() }, { navigate(page) }, {
                                if (p.pictures.size >= 10) message = "Можно добавить максимум 10 фото"
                                else chooseFile("Выберите JPEG или PNG")?.let { file -> work {
                                    val pic = withContext(Dispatchers.IO) { repo.addImage(file) }
                                    draft = draft.copy(products = draft.products.map { if (it.id == p.id) it.copy(pictures = it.pictures + pic) else it })
                                } }
                            }, { title, action -> confirm = title to action }, { field, name ->
                                val category = newSupplierCategory(draft.categories, name)
                                draft = draft.copy(categories = draft.categories + category, products = draft.products.map {
                                    if (it.id == p.id) it.copy(values = it.values + (field.id to category.id)) else it
                                })
                            })
                    }
                    templateId != null -> {
                        val t = draft.templates.first { it.id == templateId }
                        TemplateEditor(t, busy, { updated -> draft = draft.copy(templates = draft.templates.map { if (it.id == t.id) updated else it }) },
                            { commit() }, { navigate(page) }, { title, action -> confirm = title to action })
                    }
                    page == 0 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Heading("Товары", "Создавайте карточки, добавляйте фотографии и готовьте каталог к экспорту.") {
                            Button(onClick = { createProduct = true }, enabled = !busy && saved.templates.isNotEmpty()) { Text("+ Добавить товар") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Input(search, { search = it }, "Поиск по названию или артикулу", Modifier.weight(1f))
                            Choice(filter, listOf("" to "Все формы") + saved.templates.map { it.id to it.name }, "Форма", Modifier.width(260.dp)) { filter = it }
                        }
                        val products = saved.products.filter { p ->
                            val v = p.valuesFor(saved.templates.first { it.id == p.templateId })
                            (filter.isEmpty() || filter == p.templateId) && (search.isBlank() || listOf(v["name"], v["id"]).any { it.orEmpty().contains(search, true) })
                        }
                        if (products.isEmpty()) Empty("Товары не найдены", if (saved.templates.isEmpty()) "Создайте форму в конструкторе, затем добавьте первый товар." else "Добавьте первый товар или измените условия поиска.")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(products, key = { it.id }) { p ->
                                val t = saved.templates.first { it.id == p.templateId }; val v = p.valuesFor(t)
                                Panel {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(v["name"].orEmpty().ifBlank { "Без названия" }, fontWeight = FontWeight.SemiBold)
                                            Text("${v["id"].orEmpty().ifBlank { "Без артикула" }}  ·  ${t.name}  ·  ${p.pictures.size} фото", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(v["price"].orEmpty().ifBlank { "—" } + " ₽", fontWeight = FontWeight.Medium)
                                        OutlinedButton(onClick = { draft = saved; productId = p.id }, enabled = !busy) { Text("Изменить") }
                                        OutlinedButton(onClick = {
                                            copyName = v["name"].orEmpty()
                                            copySource = p
                                        }, enabled = !busy) { Text("Копировать") }
                                        TextButton(onClick = { confirm = "Удалить товар из каталога?" to { commit(saved.copy(products = saved.products.filter { it.id != p.id })) } }, enabled = !busy) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                    page == 1 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Heading("Конструктор форм", "Шаблоны полей для ваших товаров.") {
                            Button(onClick = { val t = defaultTemplate().copy(id = newId(), name = "Новая форма"); draft = saved.copy(templates = saved.templates + t); templateId = t.id }, enabled = !busy) { Text("+ Создать форму") }
                        }
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
                                        else confirm = "Удалить форму «${t.name}»?" to { commit(saved.copy(templates = saved.templates.filter { it.id != t.id })) }
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
                if (busy) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.65f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    copySource?.let { source ->
        AlertDialog(onDismissRequest = { if (!busy) copySource = null },
            title = { Text("Копировать товар") },
            text = { OutlinedTextField(value = copyName, onValueChange = { copyName = it },
                label = { Text("Новое название товара") }, singleLine = true, enabled = !busy) },
            confirmButton = { Button(enabled = !busy && copyName.isNotBlank(), onClick = {
                val name = copyName.trim()
                work {
                    val template = saved.templates.first { it.id == source.templateId }
                    val nameField = template.fields.firstOrNull { it.target == "name" }
                        ?: error("В форме товара отсутствует поле названия")
                    val copy = saved.duplicateProduct(source, newId()).let {
                        it.copy(values = it.values + (nameField.id to name))
                    }
                    val next = saved.copy(products = saved.products + copy)
                    withContext(Dispatchers.IO) { repo.save(next) }
                    saved = next
                    draft = next
                    search = ""
                    copySource = null
                }
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { copySource = null }) { Text("Отмена") } })
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

@Composable private fun Heading(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium) }
        actions()
    }
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFE0E8E2))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun Empty(title: String, text: String) { Panel { Text(title, style = MaterialTheme.typography.titleLarge); Text(text, color = Muted) } }
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

@Composable private fun ProductEditor(p: Product, t: Template, project: Project, busy: Boolean, update: (Product) -> Unit, save: () -> Unit, cancel: () -> Unit, upload: () -> Unit, confirm: (String, () -> Unit) -> Unit, createCategory: (Field, String) -> Unit) {
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
            item { Text("Звёздочкой отмечены обязательные поля. Укажите реальные регионы, стоимость и срок доставки. Неполные карточки можно сохранять как черновики.", color = Muted) }
            items(t.cardFields(project.settings), key = { it.id }) { f ->
                val value = p.values[f.id].orEmpty(); val label = f.label + (if (f.required) " *" else "") + if (f.unit.isNotEmpty()) " (${f.unit})" else ""
                val change: (String) -> Unit = { update(p.copy(values = p.values + (f.id to it))) }
                FieldValueInput(f, value, label, onChange = change)
                f.catalogHint()?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
            item { Panel {
                Text("Фотографии (${p.pictures.size}/10)", style = MaterialTheme.typography.titleLarge)
                Text("Первое фото — основное. JPEG/PNG, 250–3500 px, до 15 МБ. Для файлов укажите публичный адрес папки в настройках или отдельную ссылку.", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(upload, enabled = !busy && p.pictures.size < 10) { Text("Загрузить файл") }
                    OutlinedButton({ update(p.copy(pictures = p.pictures + Picture())) }, enabled = !busy && p.pictures.size < 10) { Text("Добавить ссылку") }
                }
            } }
            items(p.pictures.size) { index ->
                val pic = p.pictures[index]
                Panel {
                    Text(if (index == 0) "Основное фото" else "Фото ${index + 1}", fontWeight = FontWeight.Medium)
                    if (pic.file.isNotEmpty()) {
                        LocalPhoto(picture = pic)
                        Text("${pic.name.ifBlank { pic.file }} · ${pic.width} × ${pic.height} px", color = Muted)
                    }
                    Input(pic.url, { url -> update(p.copy(pictures = p.pictures.mapIndexed { i, item -> if (i == index) item.copy(url = url) else item })) }, "Публичная ссылка HTTP/HTTPS")
                    if (pic.file.isNotEmpty() && pic.url.isBlank()) Text("Адрес для экспорта: ${imageUrl(pic, project.settings).ifEmpty { "не задан" }}", color = Muted)
                    Row {
                        TextButton({ update(p.copy(pictures = p.pictures.move(index, -1))) }, enabled = index > 0) { Text("↑ Выше") }
                        TextButton({ update(p.copy(pictures = p.pictures.move(index, 1))) }, enabled = index < p.pictures.lastIndex) { Text("↓ Ниже") }
                        TextButton({ confirm("Убрать фотографию из карточки?") { update(p.copy(pictures = p.pictures.filterIndexed { i, _ -> i != index })) } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
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
private val LocalImageDirectory = staticCompositionLocalOf { defaultDataDirectory() }

@Composable private fun TemplateEditor(t: Template, busy: Boolean, update: (Template) -> Unit, save: () -> Unit, cancel: () -> Unit, confirm: (String, () -> Unit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Редактор формы", "Изменения применяются ко всем товарам этой формы.") { OutlinedButton(cancel, enabled = !busy) { Text("Отмена") }; Button(save, enabled = !busy) { Text("Сохранить") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Input(t.name, { update(t.copy(name = it)) }, "Название формы") }
            item { Input(t.description, { update(t.copy(description = it)) }, "Описание формы", multiline = true) }
            items(t.fields.size, key = { t.fields[it].id }) { index ->
                val f = t.fields[index]
                fun change(next: Field) { update(t.copy(fields = t.fields.mapIndexed { i, field -> if (i == index) next else field })) }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${f.label}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton({ update(t.copy(fields = t.fields.move(index, -1))) }, enabled = index > 0) { Text("↑") }
                        TextButton({ update(t.copy(fields = t.fields.move(index, 1))) }, enabled = index < t.fields.lastIndex) { Text("↓") }
                        TextButton({ confirm("Удалить поле? После сохранения оно перестанет выводиться и экспортироваться у товаров этой формы.") { update(t.copy(fields = t.fields.filterIndexed { i, _ -> i != index })) } }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Input(f.label, { change(f.copy(label = it)) }, "Название поля", Modifier.weight(1f))
                        Choice(f.target, listOf("param" to "Характеристика (param)") + fieldDefinitions.filter { it.target != "categoryId" }.map { it.target to it.label }, "Поле YML", Modifier.weight(1f)) { target ->
                            val def = fieldDefinitions.firstOrNull { it.target == target }
                            change(f.copy(target = target, type = def?.type ?: f.type, required = def?.required ?: f.required, dictionary = "", inputMode = "auto"))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Choice(f.type, listOf("text" to "Текст", "number" to "Число", "textarea" to "Многострочный текст", "boolean" to "Да / Нет", "datetime-local" to "Дата и время", "category" to "Категория"), "Тип данных", Modifier.weight(1f)) { change(f.copy(type = it)) }
                        if (f.target == "param") FieldValueInput(Field("unit-" + f.id, "param", "Единица характеристики", dictionary = "unit"), f.unit, modifier = Modifier.weight(1f)) { change(f.copy(unit = it)) }
                    }
                    FieldChoiceConfiguration(f, ::change)
                    Toggle(f.required, "Требовать заполнение в этой форме") { change(f.copy(required = it)) }
                    if (fieldDefinitions.any { it.target == f.target && it.required }) Text("Это поле входит в обязательные данные импорта. Снятие требования формы не отключает проверку экспорта.", color = Muted)
                    f.catalogHint()?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { AddFieldButtons(t.fields, ::newId) { update(t.copy(fields = t.fields + it)) } }
        }
    }
}

@Composable private fun SettingsEditor(project: Project, busy: Boolean, update: (Project) -> Unit, save: () -> Unit, directory: String, backup: () -> Unit, import: () -> Unit) {
    val s = project.settings
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Настройки каталога", "Магазин, категории и хранение данных.") { Button(save, enabled = !busy) { Text("Сохранить") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Panel {
                Text("Магазин", style = MaterialTheme.typography.titleLarge)
                Input(s.name, { update(project.copy(settings = s.copy(name = it))) }, "Название магазина")
                Input(s.company, { update(project.copy(settings = s.copy(company = it))) }, "Полное название компании")
                Input(s.url, { update(project.copy(settings = s.copy(url = it))) }, "Сайт магазина")
                Input(s.imageBase, { update(project.copy(settings = s.copy(imageBase = it))) }, "Публичный адрес папки фотографий, например https://example.ru/images")
                Toggle(s.useVat, "Передавать НДС (значение из справочника портала)") { update(project.copy(settings = s.copy(useVat = it))) }
                Text("В каждой карточке укажите ID категории из справочника портала. Категория поставщика создаётся автоматически; её ID вводить не нужно.", color = Muted)
            } }
            item { Panel {
                Text("Рабочие данные", style = MaterialTheme.typography.titleLarge)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(directory, color = Muted) }
                Text("Резервная копия содержит сохранённый проект и все загруженные фотографии. Для переноса старого проекта выберите project.json; папка images должна находиться рядом с ним.", color = Muted)
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
            text = { Text("В каталоге ошибок: ${report.errors.size}. Файл будет сохранён, но портал может его не принять.") },
            confirmButton = { Button({ pendingBundle = null; export(bundle, true) }, enabled = !busy) { Text("Сохранить всё равно") } },
            dismissButton = { TextButton({ pendingBundle = null }) { Text("Отмена") } })
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Экспорт YML", "Проверка сохранённых товаров перед загрузкой на портал.")
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
            items(report.warnings) { Text("• $it", color = Color(0xFF90651F)) }
            item { Panel {
                Text("После экспорта", style = MaterialTheme.typography.titleMedium)
                Text("Разместите содержимое images/ из архива по указанному публичному адресу, сохранив имена файлов, затем загрузите catalog.yml на портал. Программа не размещает фото в интернете.", color = Muted)
                Text("Сохранены правила исходной версии проекта. Полная XSD-валидация и проверка кодов по справочникам портала не выполняются; окончательная проверка происходит при импорте на портал.", color = Muted)
            } }
        }
    }
}

@Composable internal fun VatSelector(value: String, label: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    Choice(vatSelection(value), vatChoices(value), label, modifier, onChange)
}
