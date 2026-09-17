package ru.ymlstudio

/** Empty folder ID is the desktop. Folders organize cards without changing their data. */
fun Project.productsInFolder(folderId: String): List<Product> {
    require(folderId.isEmpty() || folders.any { it.id == folderId }) { "Папка не найдена" }
    return products.filter { it.folderId == folderId }
}

private fun Project.folderName(name: String, exceptId: String? = null): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Укажите название папки" }
    require(!trimmed.equals("Рабочий стол", ignoreCase = true)) { "Выберите другое название папки" }
    require(folders.none { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) }) { "Папка с таким названием уже есть" }
    return trimmed
}

fun Project.createFolder(id: String, name: String): Project {
    require(id.isNotBlank() && folders.none { it.id == id }) { "ID папки должен быть уникальным" }
    return copy(folders = folders + ProductFolder(id, folderName(name)))
}

fun Project.renameFolder(id: String, name: String): Project {
    require(folders.any { it.id == id }) { "Папка не найдена" }
    val nextName = folderName(name, id)
    return copy(folders = folders.map { if (it.id == id) it.copy(name = nextName) else it })
}

fun Project.deleteEmptyFolder(id: String): Project {
    require(id.isNotEmpty() && folders.any { it.id == id }) { "Папка не найдена" }
    require(products.none { it.folderId == id }) { "Сначала переместите товары из папки" }
    return copy(folders = folders.filter { it.id != id })
}

fun Project.moveProducts(ids: Set<String>, destination: String): Project {
    require(ids.isNotEmpty()) { "Отметьте товары" }
    require(products.count { it.id in ids } == ids.size) { "Некоторые выбранные товары больше не существуют" }
    require(destination.isEmpty() || folders.any { it.id == destination }) { "Папка не найдена" }
    return copy(products = products.map { if (it.id in ids) it.copy(folderId = destination) else it })
}

internal fun Project.checkFolders() {
    require(folders.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Некорректная папка" }
    require(folders.map { it.id }.distinct().size == folders.size) { "Повторяющийся ID папки" }
    val known = folders.map { it.id }.toSet()
    require(products.all { it.folderId.isEmpty() || it.folderId in known }) { "Товар ссылается на отсутствующую папку" }
}
