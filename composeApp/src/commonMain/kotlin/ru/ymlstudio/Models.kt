package ru.ymlstudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class Settings(val name: String = "", val company: String = "", val url: String = "", val imageBase: String = "", val useVat: Boolean = false, val usePortalCategories: Boolean = true)
@Serializable data class Category(val id: String = "", val name: String = "", val parentId: String = "")
@Serializable data class Field(val id: String, val target: String, val label: String, val type: String = "text", val required: Boolean = false, val unit: String = "", val default: String = "", val inputMode: String = "auto", val dictionary: String = "", val options: List<FieldOption> = emptyList())
@Serializable data class Template(val id: String, val name: String, val description: String = "", val fields: List<Field>)
@Serializable data class Picture(val url: String = "", val file: String = "", val width: Int = 0, val height: Int = 0, val name: String = "")
@Serializable data class Product(val id: String, val templateId: String, val values: Map<String, String> = emptyMap(), val pictures: List<Picture> = emptyList())
@Serializable data class Project(val version: Int = 1, val settings: Settings, val categories: List<Category>, val templates: List<Template>, val products: List<Product>, val minimalPresetInstalled: Boolean = false, val importRulesVersion: Int = 0, val universalFormUnified: Boolean = false)
data class FieldDefinition(val target: String, val label: String, val type: String, val required: Boolean)
val fieldDefinitions = listOf(
    FieldDefinition("id", "Артикул / ID предложения", "text", true),
    FieldDefinition("name", "Название товара", "text", true),
    FieldDefinition("currencyId", "Валюта", "text", false),
    FieldDefinition("price", "Цена", "number", true),
    FieldDefinition("categoryId", "Категория поставщика (старое поле)", "category", false),
    FieldDefinition("vendor", "Производитель", "text", false),
    FieldDefinition("model", "Модель", "text", false),
    FieldDefinition("oksm", "Страна происхождения (код ОКСМ)", "text", true),
    FieldDefinition("beginDate", "Начало предложения", "datetime-local", true),
    FieldDefinition("endDate", "Окончание предложения", "datetime-local", true),
    FieldDefinition("delivery", "Курьерская доставка", "boolean", false),
    FieldDefinition("vat", "НДС (значение из справочника портала)", "text", false),
    FieldDefinition("description", "Описание", "textarea", false),
    FieldDefinition("vendorCode", "Код производителя", "text", false),
    FieldDefinition("ppCategory", "ID категории портала", "text", true),
    FieldDefinition("ste", "ID СТЕ", "text", false),
    FieldDefinition("okei", "Код единицы измерения ОКЕИ", "text", true),
    FieldDefinition("min-quantity", "Минимальное количество", "number", true),
    FieldDefinition("max-quantity", "Максимальное количество", "number", false),
    FieldDefinition("regions", "Регионы поставки", "textarea", true),
    FieldDefinition("deliveryCost", "Стоимость доставки", "number", true),
    FieldDefinition("deliveryDays", "Срок доставки, рабочие дни", "text", true),
    FieldDefinition("region", "ID региона поставки", "text", false),
    FieldDefinition("packageType", "ID типа упаковки", "text", false),
    FieldDefinition("weight", "Вес с упаковкой, кг", "number", false),
    FieldDefinition("dimensions", "Габариты, см (Д/Ш/В)", "text", false),
    FieldDefinition("barcode", "Штрихкод", "text", false),
    FieldDefinition("available", "В наличии", "boolean", false),
    FieldDefinition("isVisibleToStateCustomers", "Для государственных заказчиков", "boolean", false),
    FieldDefinition("isAvailableToIndividuals", "Для негосударственных заказчиков", "boolean", false),
    FieldDefinition("manufacturer_warranty", "Гарантия производителя", "boolean", false),
)
val projectJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
fun defaultTemplate() = Template("basic", "Универсальная форма",
    "Минимальные данные импорта. Характеристики, необходимые вашей категории, добавьте в конструкторе.",
    fieldDefinitions.filter { it.required || it.target == "vat" }
        .map { Field(if (it.target == "ppCategory") "_portal_category" else it.target, it.target, it.label, it.type, it.required) })
fun defaultProject() = Project(settings = Settings(), categories = emptyList(), templates = listOf(defaultTemplate()), products = emptyList(),
    minimalPresetInstalled = true, importRulesVersion = 1, universalFormUnified = true)
fun Product.valuesFor(template: Template, settings: Settings? = null) = (if (settings == null) template.fields else template.cardFields(settings)).filter { it.target != "param" }.associate { it.target to values[it.id].orEmpty().trim() }
fun newProduct(id: String, template: Template) = Product(id, template.id, template.fields.associate { it.id to it.default })

fun Project.createProduct(id: String, template: Template): Product {
    require(id.isNotBlank() && products.none { it.id == id }) { "ID товара должен быть уникальным" }
    val idField = template.fields.firstOrNull { it.target == "id" }
        ?: error("В форме товара отсутствует поле артикула")
    val used = products.map { product ->
        product.valuesFor(templates.first { it.id == product.templateId })["id"].orEmpty()
    }.toSet()
    var number = 1L
    while (number.toString() in used) number++
    return newProduct(id, template).let { it.copy(values = it.values + (idField.id to number.toString())) }
}

/** Create an unsaved independent card, reusing immutable photo references. */
fun Project.duplicateProduct(source: Product, id: String): Product {
    require(id.isNotBlank() && products.none { it.id == id }) { "ID копии должен быть уникальным" }
    val template = templates.first { it.id == source.templateId }
    val idField = template.fields.firstOrNull { it.target == "id" }
        ?: error("В форме товара отсутствует поле артикула")
    val used = products.map { product ->
        product.valuesFor(templates.first { it.id == product.templateId })["id"].orEmpty()
    }.toSet()
    val base = source.values[idField.id].orEmpty().filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }.ifEmpty { "item" }
    var number = 1L
    var article: String
    do {
        val suffix = "C$number"
        article = base.take((20 - suffix.length).coerceAtLeast(0)) + suffix
        number++
    } while (article in used)
    return source.copy(id = id, values = source.values + (idField.id to article))
}

fun imageUrl(picture: Picture, settings: Settings): String = picture.url.trim().ifEmpty {
    if (picture.file.isNotEmpty() && settings.imageBase.isNotBlank()) settings.imageBase.trim().trimEnd('/') + "/" + picture.file else ""
}
fun checkShape(project: Project) {
    require(project.version == 1) { "Неизвестный формат проекта" }
    require(project.templates.map { it.id }.distinct().size == project.templates.size) { "Повторяющийся ID формы" }
    project.templates.forEach { t ->
        require(t.id.isNotBlank() && t.name.isNotBlank()) { "Укажите название формы" }
        require(t.fields.map { it.id }.distinct().size == t.fields.size) { "Повторяющийся ID поля" }
        val targets = t.fields.filter { it.target != "param" }.map { it.target }
        require(targets.distinct().size == targets.size) { "Поле YML повторяется" }
        t.fields.forEach {
            require(it.inputMode in listOf("auto", "text", "select", "both")) { "Неизвестный режим ввода поля" }
            require(it.dictionary in listOf("", "custom") + dictionaryTitles.map { d -> d.first }) { "Неизвестный справочник" }
            require(it.options.all { o -> o.value.isNotBlank() } && it.options.map { o -> o.value }.distinct().size == it.options.size) { "Варианты поля: заполните уникальные значения" }
            require(it.id.isNotBlank() && it.label.isNotBlank() && (it.target == "param" || fieldDefinitions.any { d -> d.target == it.target }) &&
                it.type in listOf("text", "number", "textarea", "boolean", "datetime-local", "category")) { "Некорректное поле формы" }
        }
    }
    require(project.products.map { it.id }.distinct().size == project.products.size) { "Повторяющийся ID товара" }
    project.products.forEach { p ->
        require(p.id.isNotBlank() && project.templates.any { it.id == p.templateId }) { "Товар ссылается на отсутствующую форму" }
        p.pictures.forEach { require(it.file.isEmpty() || Regex("[a-f0-9]{32}\\.(jpg|png)").matches(it.file)) { "Некорректное имя изображения" } }
    }
}

/** Allocate a supplier category ID without depending on portal dictionaries. */
fun newSupplierCategory(categories: List<Category>, name: String): Category {
    require(name.isNotBlank()) { "Укажите название категории" }
    val used = categories.map { it.id }.toSet()
    var id = 1L
    while (id.toString() in used) id++
    return Category(id.toString(), name.trim())
}

/** Compatibility alias: there is only one built-in universal preset now. */
fun minimalTemplate() = defaultTemplate()

fun Project.withMinimalPreset(): Project = withImportRules().withUniversalForm().withDirectPortalCategories()

fun Project.withDirectPortalCategories(): Project = copy(
    settings = settings.copy(usePortalCategories = true),
    templates = templates.map { template ->
        val portal = template.cardFields(settings).first { it.target == "ppCategory" }
        val firstCategory = template.fields.indexOfFirst { it.target in listOf("categoryId", "ppCategory") }
        val fields = template.fields.filter { it.target !in listOf("categoryId", "ppCategory") }.toMutableList()
        fields.add(if (firstCategory < 0) fields.size else firstCategory.coerceAtMost(fields.size), portal)
        template.copy(fields = fields)
    }
)

/** Consolidate the two old built-in forms once, preserving product values and custom forms. */
fun Project.withUniversalForm(): Project {
    if (universalFormUnified) return this
    val oldForms = templates.filter { it.id in listOf("basic", "universal-minimal-v1") }
    if (oldForms.isEmpty()) return copy(universalFormUnified = true, minimalPresetInstalled = true)
    val preferred = oldForms.firstOrNull { it.id == "universal-minimal-v1" } ?: oldForms.first()
    val fields = defaultTemplate().fields.map { field ->
        // A custom field may already own a standard key; do not overwrite its stored value.
        var id = field.id
        while (oldForms.any { t -> t.fields.any { it.id == id && it.target != field.target } }) id += "_"
        field.copy(id = id, default = preferred.fields.firstOrNull { it.target == field.target }?.default.orEmpty())
    }
    val unified = defaultTemplate().copy(fields = fields)
    val oldById = oldForms.associateBy { it.id }
    val nextProducts = products.map { product ->
        val source = oldById[product.templateId] ?: return@map product
        val oldFields = source.fields + source.cardFields(settings.copy(usePortalCategories = true)).filter { it.target == "ppCategory" }
        val destinationFields = unified.fields + unified.cardFields(settings.copy(usePortalCategories = true)).filter { it.target == "ppCategory" }
        val mapped = destinationFields.mapNotNull { field ->
            val old = oldFields.firstOrNull { it.target == field.target } ?: return@mapNotNull null
            product.values[old.id]?.let { field.id to it }
        }.toMap()
        product.copy(templateId = unified.id, values = product.values + mapped)
    }
    return copy(templates = listOf(unified) + templates.filter { it.id !in oldById }, products = nextProducts,
        universalFormUnified = true, minimalPresetInstalled = true)
}

fun Template.cardFields(settings: Settings): List<Field> {
    var result = fields
    run {
        val existing = fields.firstOrNull { it.target == "ppCategory" }
        // Keep portal IDs separate: never reinterpret a supplier category ID.
        var id = "_portal_category"
        while (fields.any { it.id == id }) id += "_"
        val portal = (existing ?: Field(id, "ppCategory", "ID категории портала")).copy(type = "text", required = true)
        result = fields.filter { it.target != "categoryId" && it.target != "ppCategory" }.toMutableList().apply {
            add(fields.indexOfFirst { it.target in listOf("categoryId", "ppCategory") }.let { if (it < 0) size else it.coerceAtMost(size) }, portal)
        }
    }
    return result.map { field ->
        if (field.target == "price" && field.label == "Цена, ₽") field.copy(label = "Цена")
        else if (field.target == "deliveryCost" && field.label == "Стоимость доставки, ₽") field.copy(label = "Стоимость доставки")
        else field
    }.filter { it.target != "vat" || settings.useVat }
        .map { if ((it.target == "vat" && settings.useVat) || fieldDefinitions.any { d -> d.target == it.target && d.required }) it.copy(required = true) else it }
}

fun Field.catalogHint(): String? = when (target) {
    "param" -> if (isVatIncludedParameter()) "Передаётся как характеристика товара. В предоставленной YML-схеме отдельного признака включения НДС в цену нет." else null
    "categoryId" -> "Ваша категория из настроек каталога. На портале её нужно сопоставить с категорией справочника."
    "regions" -> "Названия из справочника портала, по одному на строку или через ;. Например: Москва; Московская область."
    "region" -> "Прежнее поле ID региона. Выгружается внутри regions. Для нескольких регионов используйте «Регионы поставки»."
    "deliveryCost" -> "Стоимость в валюте предложения (по умолчанию RUB). 0 означает бесплатно."
    "deliveryDays" -> "Число или диапазон рабочих дней: 1 или 1-30. Включите время изготовления, если товар на заказ."
    "okei" -> "Введите код ОКЕИ или выберите единицу по названию. В YML передаются код и полное название."
    "oksm" -> "Трёхзначный код ОКСМ: 156 — Китай, 643 — Россия. Можно указать код | название."
    "currencyId" -> "Без этого поля используется RUB. Валюта из справочника должна также поддерживаться XSD; GBP схема не поддерживает."
    "available" -> "Да — в наличии, нет — на заказ. Не определяет видимость оферты."
    "ppCategory" -> "ID из справочника Портала поставщиков. Для экипировки баскетбола/стритбола — 793363662. Собственная категория создаётся автоматически."
    else -> null
}

/** Upgrade form definitions once without inventing delivery terms for existing products. */
fun Project.withImportRules(): Project {
    if (importRulesVersion >= 1) return this
    return copy(importRulesVersion = 1, templates = templates.map { template ->
        var fields = template.fields
        if (template.id in listOf("basic", "universal-minimal-v1")) {
            fields = fields.map { field ->
                val definition = fieldDefinitions.firstOrNull { it.target == field.target }
                if (field.id == field.target && field.target in listOf("vendor", "model", "delivery")) field.copy(required = false)
                else if (field.id in listOf("param0", "param1", "param2", "param3") && field.label in listOf("Цвет", "Материал", "Ширина", "Высота")) field.copy(required = false)
                else if (definition != null && definition.required) field.copy(required = true)
                else field
            }
        }
        for (target in listOf("regions", "deliveryCost", "deliveryDays", "okei", "min-quantity")) {
            if (fields.none { it.target == target } && !(target == "regions" && fields.any { it.target == "region" })) {
                val d = fieldDefinitions.first { it.target == target }
                var id = target
                while (fields.any { it.id == id }) id += "_"
                fields = fields + Field(id, target, d.label, d.type, d.required)
            }
        }
        template.copy(fields = fields)
    })
}

data class CountryValue(val code: String, val name: String)
fun countryValue(raw: String): CountryValue? {
    val value = raw.trim()
    val normalized = when(value.lowercase()) { "российская федерация" -> "643"; "кнр" -> "156"; else -> value }
    val known = Dictionaries.country.firstOrNull { it.code == normalized || it.name.equals(normalized, true) }?.let { CountryValue(it.code, it.name) }
    if (known != null) return known
    val parts = value.split('|', limit = 2).map { it.trim() }
    return if (Regex("[0-9]{3}").matches(parts[0])) CountryValue(parts[0], parts.getOrElse(1) { "" }) else null
}
fun regionNames(raw: String) = raw.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { regionEntry(it)?.name ?: it }.distinct()
fun validDeliveryDays(raw: String): Boolean {
    if (!Regex("[0-9]{1,3}(-[0-9]{1,3})?").matches(raw)) return false
    val parts = raw.split('-').map { it.toInt() }
    return parts.size == 1 || parts[0] <= parts[1]
}

/** Names taken from the user's own portal export, not inferred from country codes. */
val knownOkeiNames = mapOf("796" to "Штука", "839" to "Комплект", "715" to "Пара (2 шт.)", "778" to "Упаковка")
data class OkeiValue(val id: String, val name: String)
fun okeiValue(raw: String): OkeiValue? {
    val parts = raw.trim().split('|', limit = 2).map { it.trim() }
    val id = parts[0]
    if (!Regex("[0-9]+").matches(id) || id.all { it == '0' }) return null
    val supplied = parts.getOrElse(1) { "" }
    val matches = Dictionaries.okei.filter { it.code == id && it.name.isNotBlank() }
    if (supplied.isNotEmpty() && matches.any { it.name.equals(supplied, true) }) return OkeiValue(id, matches.first { it.name.equals(supplied, true) }.name)
    val names = matches.map { it.name }.distinct()
    val known = knownOkeiNames[id] ?: names.singleOrNull()
    if (known != null) {
        if (supplied.isNotEmpty() && !supplied.equals(known, ignoreCase = true)) return null
        return OkeiValue(id, known)
    }
    return if (supplied.isNotBlank()) OkeiValue(id, supplied) else null
}
