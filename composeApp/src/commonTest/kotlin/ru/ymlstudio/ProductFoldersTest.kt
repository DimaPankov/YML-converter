package ru.ymlstudio

import kotlinx.serialization.encodeToString
import kotlin.test.*

class ProductFoldersTest {
    @Test fun movesBothWaysWithoutChangingCardDataAndRoundTrips() {
        val original = validProject()
        val source = original.products.single()
        val organized = original.createFolder("sent", " Отправленные ").moveProducts(setOf(source.id), "sent")
        assertEquals("Отправленные", organized.folders.single().name)
        assertEquals(emptyList(), organized.productsInFolder(""))
        assertEquals(source.copy(folderId = "sent"), organized.productsInFolder("sent").single())
        checkShape(organized)
        val restored = projectJson.decodeFromString<Project>(projectJson.encodeToString(organized))
        assertEquals(organized, restored)
        assertEquals(original.products, restored.moveProducts(setOf(source.id), "").products)
        assertEquals(original.products, original.productsInFolder(""))
    }

    @Test fun folderNamesAndReferencesAreValidatedWithoutDeletingProducts() {
        val project = validProject().createFolder("a", "Архив")
        for (name in listOf("", "  ", "архив", "Рабочий стол")) {
            assertFailsWith<IllegalArgumentException> { project.createFolder("b", name) }
        }
        assertFailsWith<IllegalArgumentException> { project.moveProducts(setOf("unknown"), "a") }
        assertFailsWith<IllegalArgumentException> { project.moveProducts(setOf(project.products.single().id), "missing") }
        val full = project.moveProducts(setOf(project.products.single().id), "a")
        assertFailsWith<IllegalArgumentException> { full.deleteEmptyFolder("a") }
        assertEquals("Готовые", full.renameFolder("a", "Готовые").folders.single().name)
        val cleared = full.moveProducts(setOf(full.products.single().id), "").deleteEmptyFolder("a")
        assertEquals(emptyList(), cleared.folders)
        assertEquals(project.products, cleared.products)
        assertFailsWith<IllegalArgumentException> { checkShape(full.copy(folders = emptyList())) }
    }

    @Test fun copyBulkEditAndTemplateChangesPreserveFolder() {
        val original = validProject().createFolder("a", "Архив")
        val source = original.products.single()
        val project = original.moveProducts(setOf(source.id), "a")
        val copied = project.duplicateProduct(project.products.single(), "copy")
        assertEquals("a", copied.folderId)
        assertNotEquals(source.values["id"], copied.values["id"])
        val edited = project.updateProducts(setOf(source.id), mapOf("price" to "200"))
        assertEquals("a", edited.products.single().folderId)
        val changed = edited.updateTemplate(edited.templates.single().copy(name = "Новая форма"))
        assertEquals("a", changed.products.single().folderId)
    }

    @Test fun folderExportContainsOnlyItsProductsAndNoFolderMetadata() {
        val original = validProject().createFolder("a", "Отправленные")
        val copy = original.duplicateProduct(original.products.single(), "copy")
        val project = original.copy(products = original.products + copy).moveProducts(setOf(copy.id), "a")
        val xml = buildYml(project.copy(products = project.productsInFolder("a")))
        assertEquals(1, Regex("<offer id=").findAll(xml).count())
        assertTrue("<offer id=\"${copy.values["id"]}\"" in xml)
        assertFalse("Отправленные" in xml)
        assertEquals(1, project.productsInFolder("").size)
    }

    @Test fun legacyProjectHasAllProductsOnDesktop() {
        val json = projectJson.encodeToString(validProject())
            .replace(",\n    \"folders\": []", "")
            .replace(",\n            \"folderId\": \"\"", "")
        val decoded = projectJson.decodeFromString<Project>(json)
        assertTrue(decoded.products.all { it.folderId.isEmpty() })
        assertTrue(decoded.folders.isEmpty())
    }
}
