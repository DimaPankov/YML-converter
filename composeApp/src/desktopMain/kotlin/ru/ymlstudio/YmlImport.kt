package ru.ymlstudio

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A preview only: parsing does not write to the repository or fetch picture URLs. */
data class YmlImport(val template: Template, val products: List<Product>, val warnings: List<String>) {
    val formGroups: List<YmlFormGroup> by lazy {
        products.indices.groupBy { index -> asTemplate(index, template.name).structure() }.values.map { indices ->
            YmlFormGroup(indices.first(), indices.size)
        }
    }
    fun asTemplate(index: Int, name: String): Template {
        require(name.isNotBlank()) { "Укажите название формы" }
        val product = products[index]
        return template.copy(name = name.trim(), fields = template.fields.filter {
            it.required || it.id in product.values
        }.map { it.copy(default = product.values[it.id].orEmpty()) }, defaultPictures = product.pictures)
    }

    fun addProductsTo(project: Project): YmlImportResult {
        val templates = project.templates.toMutableList()
        val byStructure = templates.associateBy { it.structure() }.toMutableMap()
        val forms = mutableMapOf<Map<FieldStructure, Int>, Template>()
        formGroups.forEachIndexed { groupIndex, group ->
            val form = asTemplate(group.productIndex, template.name).let { candidate ->
                candidate.copy(id = if (groupIndex == 0) template.id else newId(),
                    name = if (formGroups.size == 1) template.name else "${template.name} · ${groupIndex + 1}",
                    fields = candidate.fields.map { it.copy(default = "") }, defaultPictures = emptyList())
            }
            val shape = form.structure()
            val destination = byStructure[shape] ?: form.also { templates += it; byStructure[shape] = it }
            forms[shape] = destination
        }
        val used = project.usedArticles().toMutableSet()
        // Reserve later rows too: a replacement must not take another imported article.
        val articleGenerator = ArticleGenerator(used + products.mapNotNull { it.values["id"] })
        var changed = 0
        val added = products.mapIndexed { index, source ->
            val form = asTemplate(index, template.name)
            val destination = forms.getValue(form.structure())
            val p = source.withTemplateFields(form, destination)
            val idField = destination.fields.first { it.target == "id" }.id
            val article = p.values[idField].orEmpty()
            if (isValidArticle(article) && used.add(article)) p else {
                val next = articleGenerator.next()
                used.add(next)
                changed++
                p.copy(values = p.values + (idField to next))
            }
        }
        return YmlImportResult(project.copy(templates = templates, products = project.products + added), changed)
    }
}

data class YmlFormGroup(val productIndex: Int, val productCount: Int)

data class YmlImportResult(val project: Project, val changedArticles: Int)
private data class ParameterKey(val name: String, val unit: String, val occurrence: Int)

fun readYmlImport(source: Path): YmlImport {
    require(Files.size(source) in 1..10L * 1024 * 1024) { "Размер YML должен быть от 1 байта до 10 МБ" }
    val bytes = Files.newInputStream(source).use { it.readNBytes(10 * 1024 * 1024 + 1) }
    require(bytes.size <= 10 * 1024 * 1024) { "YML больше 10 МБ" }
    return parseYmlImport(bytes, source.fileName.toString().substringBeforeLast('.'))
}

internal fun parseYmlImport(bytes: ByteArray, name: String): YmlImport {
    require(bytes.size <= 10 * 1024 * 1024) { "YML больше 10 МБ" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val root = try {
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException): Unit = throw e
            override fun fatalError(e: SAXParseException): Unit = throw e
        })
        builder.parse(ByteArrayInputStream(bytes)).documentElement
    } catch (e: SAXParseException) {
        throw IllegalArgumentException("Не удалось прочитать YML/XML, строка ${e.lineNumber}: ${e.message}", e)
    }
    val offers = when (root.tag()) {
        "offer" -> listOf(root)
        "offers" -> root.children("offer")
        "yml_catalog" -> root.children("offers").flatMap { it.children("offer") } +
            root.children("shop").flatMap { it.children("offers") }.flatMap { it.children("offer") }
        else -> error("Ожидается YML/XML с корневым элементом yml_catalog, offers или offer")
    }
    require(offers.isNotEmpty()) { "В файле нет товаров (элементов offer)" }
    require(offers.size <= 10_000) { "За один раз можно загрузить до 10 000 товаров" }
    val warnings = linkedSetOf<String>()
    val fields = defaultTemplate().fields.associateByTo(linkedMapOf()) { it.target }
    // Imported values use stable target keys, including ppCategory.
    fields.replaceAll { key, field -> field.copy(id = key) }
    val parameters = linkedMapOf<ParameterKey, Field>()
    val templateId = newId()
    val products = offers.map { offer ->
        val values = linkedMapOf<String, String>()
        val pictures = mutableListOf<Picture>()
        fun put(target: String, value: String) {
            val definition = fieldDefinitions.firstOrNull { it.target == target } ?: return
            require(target !in values) { "Товар ${offer.getAttribute("id")}: поле $target повторяется" }
            fields.getOrPut(target) { Field(target, target, definition.label, definition.type, definition.required) }
            values[target] = value.trim()
        }
        put("id", offer.getAttribute("id"))
        if (offer.hasAttribute("available")) put("available", offer.getAttribute("available"))
        if (offer.hasAttribute("type")) warnings += "Тип предложения (атрибут type) не переносится."
        val occurrences = mutableMapOf<Pair<String, String>, Int>()
        offer.children().forEach { element ->
            val text = element.textContent.trim()
            when (val tag = element.tag()) {
                "picture" -> pictures += Picture(url = text)
                "param" -> {
                    val label = element.getAttribute("name").trim()
                    require(label.isNotBlank()) { "В товаре ${offer.getAttribute("id")} есть характеристика без названия" }
                    val unit = element.getAttribute("unit").trim()
                    val pair = label to unit
                    val occurrence = occurrences.getOrDefault(pair, 0)
                    occurrences[pair] = occurrence + 1
                    val field = parameters.getOrPut(ParameterKey(label, unit, occurrence)) {
                        Field("param${parameters.size}", "param", label, unit = unit)
                    }
                    values[field.id] = text
                }
                "oksm" -> put(tag, element.getAttribute("code").ifBlank { text })
                "okei" -> {
                    val code = element.getAttribute("id")
                    put(tag, if (code.isBlank()) text else if (text.isBlank()) code else "$code | $text")
                }
                "package" -> put("packageType", element.getAttribute("id").ifBlank { text })
                "regions" -> {
                    val regions = element.children("region")
                    put("regions", regions.joinToString("; ") { it.textContent.trim().ifBlank { it.getAttribute("id") } })
                }
                "delivery-options" -> {
                    val options = element.children("option")
                    require(options.size <= 1) { "Товар ${offer.getAttribute("id")}: несколько вариантов доставки пока не поддерживаются" }
                    options.firstOrNull()?.let {
                        put("deliveryCost", it.getAttribute("cost"))
                        put("deliveryDays", it.getAttribute("days"))
                        if (it.hasAttribute("order-before")) warnings += "Ограничение времени заказа order-before не переносится."
                    }
                }
                "vat" -> put(tag, canonicalVat(text) ?: text)
                else -> if (fieldDefinitions.any { it.target == tag }) put(tag, text)
                    else warnings += "Поле <$tag> не поддерживается и не будет перенесено."
            }
        }
        if (values["vat"].orEmpty().isNotBlank()) warnings += "В файле есть НДС. Для его редактирования и экспорта включите передачу НДС в настройках каталога."
        if (values["categoryId"].orEmpty().isNotBlank()) warnings += "Категория поставщика сохранена в данных; для экспорта укажите категорию портала ppCategory."
        Product(newId(), templateId, values, pictures)
    }
    val template = Template(templateId, name.ifBlank { "Форма из YML" }, "Загружено из YML", fields.values.toList() + parameters.values)
    val normalized = Project(settings = Settings(), categories = emptyList(), templates = listOf(template), products = products).withDeliveryDaysMapping()
    if (template.fields.any { it.isDeliveryDaysParameter() }) {
        warnings += "Срок доставки из одноимённой характеристики перенесён в поле deliveryDays там, где оно пустое и значение однозначно."
        if (normalized.templates.single().fields.any { it.isDeliveryDaysParameter() }) {
            warnings += "Есть несовпадающие или некорректные значения срока доставки. Проверьте deliveryDays и одноимённую характеристику."
        }
    }
    return YmlImport(normalized.templates.single(), normalized.products, warnings.toList())
}

private fun Element.tag(): String = localName ?: tagName
private fun Element.children(name: String? = null): List<Element> = buildList {
    for (index in 0 until childNodes.length) {
        val node = childNodes.item(index)
        if (node is Element && (name == null || node.tag() == name)) add(node)
    }
}
