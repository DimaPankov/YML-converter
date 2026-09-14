package ru.ymlstudio


const val YML_NAMESPACE = "http://market.zakupki.mos.ru/spIntegration/Yml/1.0"
expect fun httpUrl(value: String, limit: Int = 512, public: Boolean = false): Boolean
expect fun validDate(value: String): Boolean
expect fun decimalCompare(left: String, right: String): Int?
expect fun currentCatalogDate(): String

data class ValidationReport(val errors: List<String>, val warnings: List<String>)
fun validate(project: Project): ValidationReport {
    checkShape(project)
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val s = project.settings.copy(url = project.settings.url.trim(), imageBase = project.settings.imageBase.trim())
    if (s.name.isBlank()) errors += "Магазин: укажите название."
    if (s.name.length > 20) warnings += "Магазин: инструкция рекомендует короткое название до 20 символов; XSD не ограничивает длину."
    if (s.company.isBlank()) errors += "Магазин: укажите полное название компании."
    if (s.url.isNotEmpty() && !httpUrl(s.url, 512)) errors += "Магазин: адрес сайта должен быть HTTP/HTTPS и не длиннее 512 символов."
    if (s.imageBase.isNotEmpty() && (!httpUrl(s.imageBase, public = true) || s.imageBase.any { it == '?' || it == '#' })) errors += "Адрес папки фотографий: нужен публичный HTTP/HTTPS URL без параметров и фрагмента."
    if (project.products.isEmpty()) errors += "Добавьте хотя бы один товар."
    val seen = mutableSetOf<String>()
    project.products.forEachIndexed { index, p ->
        val t = project.templates.first { it.id == p.templateId }
        val v = p.valuesFor(t, s)
        fun value(key: String) = v[key].orEmpty()
        val prefix = "Товар ${index + 1} (${value("name").ifEmpty { "без названия" }}): "
        fun error(message: String) { errors += prefix + message }
        fieldDefinitions.forEach { f ->
            if (f.target == "regions" && value("region").isNotEmpty()) return@forEach
            if ((f.required || (f.target == "vat" && s.useVat)) && value(f.target).isEmpty()) error("заполните «${f.label}».")
        }
        if (s.useVat && value("vat").isNotEmpty() && canonicalVat(value("vat")) == null)
            error("НДС: значение «${value("vat")}» не допускается типом ndsType в XSD портала. Выберите ставку из списка.")
        t.cardFields(s).forEach { f ->
            if (f.target == "vat" && !s.useVat) return@forEach
            val text = p.values[f.id].orEmpty().trim()
            if (f.effectiveInputMode() == "select" && text.isNotEmpty() && !f.acceptsChoice(text)) error("«${f.label}»: выберите значение из списка.")
            // A form can make an optional portal field mandatory as well.
            if (f.required && text.isEmpty() && (f.target != "categoryId" || value("ppCategory").isEmpty())) error("заполните «${f.label}».")
            if (f.target !in listOf("vat", "okei") && text.isNotEmpty() && f.type == "number" && decimalCompare(text, "0") == null) error("«${f.label}»: требуется число.")
        }
        if (value("id").isBlank()) error("укажите артикул.")
        if (value("id").isNotBlank() && !Regex("[A-Za-z0-9]{1,20}").matches(value("id")))
            error("Артикул: по инструкции портала нужны только латинские буквы и цифры, не более 20 символов. Дефисы и пробелы недопустимы.")
        if (!seen.add(value("id"))) error("артикул повторяется.")
        if (currencyValue(value("currencyId")) !in supportedCurrencies) error("Валюта не поддерживается XSD портала. Выберите RUB, RUR, USD, EUR, BYR, BYN, KZT или UAH.")
        if (value("ppCategory").isNotEmpty()) {
            val category = Dictionaries.category.firstOrNull { it.id == value("ppCategory") }
            if (category != null && Dictionaries.category.any { it.parentId == category.id }) error("Категория портала: выберите конечную категорию, а не общий раздел.")
            warnings += prefix + "ppCategory — ID из справочника портала; его существование и соответствие товару автоматически не проверены. Это не ID категории поставщика."
            if (value("categoryId").isNotEmpty()) warnings += prefix + "указаны обе категории: портал использует ppCategory, а категорию поставщика игнорирует."
        }
        if (value("isVisibleToStateCustomers") == "false" && value("isAvailableToIndividuals") == "false")
            warnings += prefix + "товар недоступен обеим группам покупателей: оба признака доступности установлены в false."
        listOf("ppCategory", "ste", "region", "packageType").forEach { key ->
            if (value(key).isNotEmpty() && !Regex("[1-9][0-9]*").matches(value(key))) error("$key: требуется положительный числовой ID из справочника.")
        }
        if (value("okei").isNotEmpty() && okeiValue(value("okei")) == null)
            error("ОКЕИ: нужен код и название единицы. Для штуки введите 796; для другой единицы — код | точное название из справочника портала.")
        if (okeiValue(value("okei"))?.let { unit -> Dictionaries.okei.none { it.code == unit.id && it.name == unit.name } } == true)
            warnings += prefix + "ОКЕИ: соответствие введённых кода и названия справочнику портала автоматически не проверено."
        if (value("oksm").isNotEmpty() && countryValue(value("oksm")) == null)
            error("Страна происхождения: укажите трёхзначный код ОКСМ, например 156 для Китая, либо код | название.")
        if (value("oksm").isNotEmpty() && countryValue(value("oksm"))?.let { country -> Dictionaries.country.none { it.code == country.code } } == true) warnings += prefix + "код страны отсутствует в загруженном справочнике ОКСМ."
        if (value("regions").isNotEmpty() && regionNames(value("regions")).isEmpty()) error("укажите хотя бы один регион поставки.")
        if (value("regions").isNotEmpty() && value("region").isNotEmpty()) warnings += prefix + "используются названия из поля «Регионы поставки»; прежний ID региона не выгружается."
        if (value("deliveryCost").isNotEmpty() && (!Regex("[0-9]+").matches(value("deliveryCost")) || (decimalCompare(value("deliveryCost"), "0") ?: -1) < 0))
            error("Стоимость доставки: целое число рублей, 0 — бесплатно.")
        if (value("deliveryDays").isNotEmpty() && !validDeliveryDays(value("deliveryDays"))) error("Срок доставки: число или диапазон рабочих дней, например 1-30; начало не больше окончания.")
        listOf("price", "weight", "min-quantity", "max-quantity").forEach { key ->
            if (value(key).isNotEmpty() && (decimalCompare(value(key), "0") ?: -1) <= 0) error("$key: требуется число больше нуля.")
        }
        if ((decimalCompare(value("min-quantity"), value("max-quantity")) ?: 0) > 0) error("максимум поставки меньше минимума.")
        listOf("beginDate", "endDate").forEach { if (!validDate(value(it))) error("$it: укажите корректные дату и время (ГГГГ-ММ-ДДTчч:мм).") }
        fun dateValue(key: String) = value(key).let { if (it.length == 16) it + ":00" else it }
        if (dateValue("endDate") <= dateValue("beginDate")) error("окончание предложения должно быть позже начала.")
        fieldDefinitions.filter { it.type == "boolean" }.forEach { if (value(it.target).isNotEmpty() && value(it.target) !in listOf("true", "false")) error("${it.target}: допустимы true / false.") }
        if (value("description").length > 3000) error("описание длиннее 3000 символов.")
        val dimensions = value("dimensions")
        if (dimensions.isNotEmpty() && (!Regex("[0-9]+(\\.[0-9]{1,3})?/[0-9]+(\\.[0-9]{1,3})?/[0-9]+(\\.[0-9]{1,3})?").matches(dimensions) || dimensions.split('/').any { (decimalCompare(it, "0") ?: -1) <= 0 })) error("габариты: три положительных числа через /, до 3 знаков после точки.")
        if (value("barcode").isNotEmpty() && !Regex("([0-9]{8}|[0-9]{12}|[0-9]{13})").matches(value("barcode"))) error("штрихкод должен содержать 8, 12 или 13 цифр.")
        if (t.fields.count { it.target == "param" && p.values[it.id].orEmpty().isNotBlank() && it.label.lowercase() !in listOf("модель", "производитель", "model", "vendor") } < 4) warnings += prefix + "для части категорий инструкция требует минимум 4 характеристики; заполните характеристики, подходящие вашему товару."
        if (p.pictures.size !in 1..10) error("нужно от 1 до 10 изображений.")
        p.pictures.forEach { pic ->
            if (!httpUrl(imageUrl(pic, s), public = true)) error("для каждого фото нужна публичная HTTP/HTTPS-ссылка до 512 символов; для файлов задайте адрес папки в настройках.")
            if (pic.url.contains("expires=", ignoreCase = true)) warnings += prefix + "ссылка на фото содержит срок действия (expires); используйте постоянную ссылку для повторных загрузок."
            if (pic.file.isEmpty()) warnings += prefix + "для фото по ссылке формат, размеры и доступность автоматически не проверены."
            else {
                warnings += prefix + "локальное фото: наличие изображения по публичной ссылке не проверено. До загрузки на портал разместите файл по этому адресу."
                if (minOf(pic.width, pic.height) < 600) warnings += prefix + "рекомендуется фото не менее 600 пикселей по меньшей стороне."
            }
        }
    }
    // Scan actual strings rather than escaped JSON, allowing valid surrogate pairs (emoji).
    val strings = listOf(s.name, s.company, s.url, s.imageBase) +
        project.templates.flatMap { listOf(it.name, it.description) + it.fields.flatMap { f -> listOf(f.label, f.unit, f.default) + f.options.flatMap { listOf(it.value, it.label) } } } +
        project.products.flatMap { it.values.values + it.pictures.flatMap { pic -> listOf(pic.url, pic.file, pic.name) } }
    if (strings.any(::invalidXml)) errors += "В тексте есть недопустимые для XML символы."
    return ValidationReport(errors.distinct(), warnings.distinct())
}
private fun invalidXml(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.code in 0..8 || c.code in 11..12 || c.code in 14..31 || c == '\uFFFE' || c == '\uFFFF') return true
        if (c.isHighSurrogate()) {
            if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return true
            i++
        } else if (c.isLowSurrogate()) return true
        i++
    }
    return false
}
private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
fun buildYml(project: Project, date: String = currentCatalogDate(), allowInvalid: Boolean = false): String {
    if (allowInvalid) checkShape(project)
    else {
        val report = validate(project)
        require(report.errors.isEmpty()) { report.errors.joinToString("\n") }
    }
    return buildString {
        fun tag(name: String, value: String, indent: String = "      ", attrs: String = "") { appendLine("$indent<$name$attrs>${xml(value)}</$name>") }
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<yml_catalog date=\"${xml(date)}\" xmlns=\"$YML_NAMESPACE\">")
        appendLine("  <shop>")
        tag("name", project.settings.name, "    "); tag("company", project.settings.company, "    ")
        if (project.settings.url.isNotBlank()) tag("url", project.settings.url.trim(), "    ")
        val currencies = project.products.map { product -> currencyValue(product.valuesFor(project.templates.first { it.id == product.templateId }, project.settings)["currencyId"].orEmpty()) }.distinct().ifEmpty { listOf("RUB") }
        appendLine("    <currencies>")
        currencies.forEach { appendLine("      <currency id=\"${xml(it)}\"/>") }
        appendLine("    </currencies>")
        appendLine("  </shop>\n  <categories>")
        tag("category", "Товары", "    ", " id=\"1\"")
        appendLine("  </categories>\n  <offers>")
        project.products.forEach { p ->
            val t = project.templates.first { it.id == p.templateId }; val v = p.valuesFor(t, project.settings)
            append("    <offer id=\"${xml(v["id"].orEmpty())}\"")
            v["available"]?.takeIf { it.isNotEmpty() }?.let { append(" available=\"${xml(it)}\"") }
            appendLine(">")
            tag("currencyId", currencyValue(v["currencyId"].orEmpty())); tag("name", v["name"].orEmpty())
            p.pictures.forEach { tag("picture", imageUrl(it, project.settings)) }
            listOf("ste", "isVisibleToStateCustomers", "isAvailableToIndividuals", "ppCategory", "okei", "min-quantity", "max-quantity", "beginDate", "endDate", "packageType", "price", "model", "vendor", "oksm", "vendorCode", "vat", "delivery", "manufacturer_warranty", "barcode", "weight", "dimensions", "description").forEach { key ->
                var value = v[key].orEmpty()
                if (value.isNotEmpty() && (key != "vat" || project.settings.useVat)) {
                    if (key == "okei") {
                        val unit = okeiValue(value)
                        val parts = value.split('|', limit = 2).map { it.trim() }
                        tag("okei", unit?.name ?: parts.getOrElse(1) { "" }, attrs = " id=\"${xml(unit?.id ?: parts[0])}\"")
                    } else if (key == "packageType") {
                        tag("package", Dictionaries.packaging.firstOrNull { it.id == value }?.name.orEmpty(), attrs = " id=\"${xml(value)}\"")
                    }
                    else if (key == "oksm") {
                        val country = countryValue(value)
                        if (country == null) tag(key, value)
                        else tag(key, country.name, attrs = " code=\"${xml(country.code)}\"")
                    } else {
                        if (key == "vat") value = canonicalVat(value) ?: value
                        if (key in listOf("price", "weight", "min-quantity", "max-quantity")) value = value.replace(',', '.')
                        if (key in listOf("beginDate", "endDate") && value.length == 16) value += ":00"
                        tag(key, value)
                    }
                }
            }
            val regions = regionNames(v["regions"].orEmpty())
            val oldRegion = v["region"].orEmpty()
            if (regions.isNotEmpty() || oldRegion.isNotEmpty()) {
                appendLine("      <regions>")
                if (regions.isNotEmpty()) regions.forEach { tag("region", it, "        ") }
                else tag("region", "", "        ", " id=\"${xml(oldRegion)}\"")
                appendLine("      </regions>")
            }
            val cost = v["deliveryCost"].orEmpty()
            val days = v["deliveryDays"].orEmpty()
            if (cost.isNotEmpty() || days.isNotEmpty()) {
                appendLine("      <delivery-options>")
                appendLine("        <option cost=\"${xml(cost)}\" days=\"${xml(days)}\"/>")
                appendLine("      </delivery-options>")
            }
            t.fields.filter { it.target == "param" && p.values[it.id].orEmpty().isNotBlank() }.forEach { f ->
                tag("param", p.values.getValue(f.id).trim(), attrs = " name=\"${xml(f.label)}\"" + if (f.unit.isNotEmpty()) " unit=\"${xml(f.unit)}\"" else "")
            }
            appendLine("    </offer>")
        }
        appendLine("  </offers>\n</yml_catalog>")
    }
}
