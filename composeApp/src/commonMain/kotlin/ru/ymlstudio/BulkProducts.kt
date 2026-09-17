package ru.ymlstudio

fun Project.selectedProducts(ids: Set<String>): List<Product> {
    require(ids.isNotEmpty()) { "Отметьте товары" }
    val selected = products.filter { it.id in ids }
    require(selected.size == ids.size) { "Некоторые выбранные товары больше не существуют" }
    require(selected.map { it.templateId }.distinct().size == 1) { "Можно выбрать товары только одной формы" }
    return selected
}

fun Template.bulkFields(settings: Settings): List<Field> = cardFields(settings).filter { it.target != "id" }

data class BulkTemplateChange(val text: String, val applyAll: Boolean = false)

enum class BulkPhotoMode(val label: String) {
    KEEP("Не менять"), EDIT_URL("Изменить ссылку"), ADD("Добавить"), REPLACE("Заменить все"), CLEAR("Удалить все")
}

data class BulkPhotoChange(val mode: BulkPhotoMode = BulkPhotoMode.KEEP, val pictures: List<Picture> = emptyList(),
    val photoIndex: Int = 0, val replacementUrl: String = "") {
    fun applyTo(current: List<Picture>): List<Picture> = when (mode) {
        BulkPhotoMode.KEEP -> current
        BulkPhotoMode.EDIT_URL -> current.mapIndexed { index, picture -> if (index == photoIndex) Picture(url = replacementUrl.trim()) else picture }
        BulkPhotoMode.ADD -> current + pictures
        BulkPhotoMode.REPLACE -> pictures
        BulkPhotoMode.CLEAR -> emptyList()
    }

    fun errorFor(products: List<Product>): String? = when {
        mode == BulkPhotoMode.EDIT_URL && products.any { photoIndex !in it.pictures.indices } -> "У некоторых товаров нет фотографии №${photoIndex + 1}"
        mode == BulkPhotoMode.EDIT_URL && !httpUrl(replacementUrl.trim(), public = true) -> "Укажите публичную HTTP/HTTPS-ссылку фотографии"
        mode in listOf(BulkPhotoMode.ADD, BulkPhotoMode.REPLACE) && pictures.isEmpty() -> "Добавьте фотографии"
        mode in listOf(BulkPhotoMode.ADD, BulkPhotoMode.REPLACE) && pictures.any { it.file.isBlank() && it.url.isBlank() } -> "Укажите ссылку фотографии"
        products.any { applyTo(it.pictures).size > 10 } -> "Максимум 10 фотографий у товара"
        else -> null
    }
}

fun Project.updateProducts(ids: Set<String>, changes: Map<String, String>, regenerateArticles: Boolean = false,
    photos: BulkPhotoChange = BulkPhotoChange(), templateChange: BulkTemplateChange? = null): Project {
    val selected = selectedProducts(ids)
    val template = templates.first { it.id == selected.first().templateId }
    val allowed = template.bulkFields(settings).map { it.id }.toSet()
    require(changes.isNotEmpty() || regenerateArticles || photos.mode != BulkPhotoMode.KEEP || templateChange != null) { "Выберите поля для изменения" }
    require(templateChange == null || template.copyPattern.isNotBlank()) { "В форме не задан шаблон быстрого заполнения" }
    require(templateChange == null || changes.isEmpty()) { "Выберите изменение полей или заполнение по шаблону" }
    val templateValues = if (templateChange == null) emptyMap() else {
        val baseline = template.copyText(selected.first())
        selected.associate { it.id to template.bulkCopyValues(templateChange.text, baseline, it, templateChange.applyAll) }
    }
    require(photos.errorFor(selected) == null) { photos.errorFor(selected).orEmpty() }
    require(changes.keys.all { it in allowed }) { "Выбрано недоступное для массового изменения поле" }
    val articleFields = template.fields.filter { it.target == "id" }
    require(!regenerateArticles || articleFields.isNotEmpty()) { "В форме нет поля артикула" }
    val generator = if (regenerateArticles) ArticleGenerator(usedArticles()) else null
    return copy(products = products.map { product ->
        if (product.id !in ids) product else {
            val article = generator?.next()
            val articles = if (article == null) emptyMap() else articleFields.associate { it.id to article }
            product.copy(values = product.values + changes + templateValues[product.id].orEmpty() + articles, pictures = photos.applyTo(product.pictures))
        }
    })
}

fun Project.deleteProducts(ids: Set<String>): Project {
    selectedProducts(ids)
    return copy(products = products.filter { it.id !in ids })
}
