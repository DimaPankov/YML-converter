package ru.ymlstudio

fun Project.selectedProducts(ids: Set<String>): List<Product> {
    require(ids.isNotEmpty()) { "Отметьте товары" }
    val selected = products.filter { it.id in ids }
    require(selected.size == ids.size) { "Некоторые выбранные товары больше не существуют" }
    require(selected.map { it.templateId }.distinct().size == 1) { "Можно выбрать товары только одной формы" }
    return selected
}

fun Template.bulkFields(settings: Settings): List<Field> = cardFields(settings).filter { it.target != "id" }

enum class BulkPhotoMode(val label: String) {
    KEEP("Не менять"), ADD("Добавить"), REPLACE("Заменить все"), CLEAR("Удалить все")
}

data class BulkPhotoChange(val mode: BulkPhotoMode = BulkPhotoMode.KEEP, val pictures: List<Picture> = emptyList()) {
    fun applyTo(current: List<Picture>): List<Picture> = when (mode) {
        BulkPhotoMode.KEEP -> current
        BulkPhotoMode.ADD -> current + pictures
        BulkPhotoMode.REPLACE -> pictures
        BulkPhotoMode.CLEAR -> emptyList()
    }
}

fun Project.updateProducts(ids: Set<String>, changes: Map<String, String>, regenerateArticles: Boolean = false,
    photos: BulkPhotoChange = BulkPhotoChange()): Project {
    val selected = selectedProducts(ids)
    val template = templates.first { it.id == selected.first().templateId }
    val allowed = template.bulkFields(settings).map { it.id }.toSet()
    require(changes.isNotEmpty() || regenerateArticles || photos.mode != BulkPhotoMode.KEEP) { "Выберите поля для изменения" }
    if (photos.mode in listOf(BulkPhotoMode.ADD, BulkPhotoMode.REPLACE)) {
        require(photos.pictures.isNotEmpty()) { "Добавьте фотографии" }
        require(photos.pictures.all { it.file.isNotBlank() || it.url.isNotBlank() }) { "Укажите файл или ссылку фотографии" }
    }
    require(selected.all { photos.applyTo(it.pictures).size <= 10 }) { "У товара может быть не больше 10 фотографий" }
    require(changes.keys.all { it in allowed }) { "Выбрано недоступное для массового изменения поле" }
    val articleFields = template.fields.filter { it.target == "id" }
    require(!regenerateArticles || articleFields.isNotEmpty()) { "В форме нет поля артикула" }
    val generator = if (regenerateArticles) ArticleGenerator(usedArticles()) else null
    return copy(products = products.map { product ->
        if (product.id !in ids) product else {
            val article = generator?.next()
            val articles = if (article == null) emptyMap() else articleFields.associate { it.id to article }
            product.copy(values = product.values + changes + articles, pictures = photos.applyTo(product.pictures))
        }
    })
}

fun Project.deleteProducts(ids: Set<String>): Project {
    selectedProducts(ids)
    return copy(products = products.filter { it.id !in ids })
}
