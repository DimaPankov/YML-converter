package ru.ymlstudio

import kotlin.test.*

class BulkProductsTest {
    @Test fun bulkPhotosAppendReplaceClearAndRespectLimit() {
        val ids = setOf("1", "2")
        val picture = Picture(url = "https://example.com/new.jpg")
        val added = project.updateProducts(ids, emptyMap(), photos = BulkPhotoChange(BulkPhotoMode.ADD, listOf(picture)))
        assertEquals(project.products[0].pictures + picture, added.products[0].pictures)
        assertEquals(listOf(picture), added.products[1].pictures)
        assertEquals(project.products[2], added.products[2])
        val replaced = project.updateProducts(ids, mapOf("price" to "10"), photos = BulkPhotoChange(BulkPhotoMode.REPLACE, listOf(picture)))
        assertTrue(replaced.products.take(2).all { it.pictures == listOf(picture) && it.values["price"] == "10" })
        val cleared = added.updateProducts(ids, emptyMap(), photos = BulkPhotoChange(BulkPhotoMode.CLEAR))
        assertTrue(cleared.products.take(2).all { it.pictures.isEmpty() })
        assertEquals(1, project.products[0].pictures.size)
        assertFailsWith<IllegalArgumentException> {
            project.updateProducts(ids, emptyMap(), photos = BulkPhotoChange(BulkPhotoMode.ADD, List(10) { picture }))
        }
        assertFailsWith<IllegalArgumentException> {
            project.updateProducts(ids, emptyMap(), photos = BulkPhotoChange(BulkPhotoMode.REPLACE, listOf(Picture())))
        }
    }
    private val form = Template("a", "Первая", fields = listOf(
        Field("sku", "id", "Артикул"), Field("name", "name", "Название"),
        Field("price", "price", "Цена"), Field("color", "param", "Цвет")))
    private val project = defaultProject().copy(templates = listOf(form, form.copy(id = "b")), products = listOf(
        Product("1", "a", mapOf("sku" to "A1", "name" to "A", "price" to "100", "color" to "Белый"), listOf(Picture(url = "https://example.com/a.jpg"))),
        Product("2", "a", mapOf("sku" to "A2", "name" to "B", "price" to "200", "color" to "Чёрный")),
        Product("3", "b", mapOf("sku" to "A3", "name" to "C", "price" to "300"))))

    @Test fun editsOnlySelectedFieldsAndProductsAndAllowsExplicitClearing() {
        val result = project.updateProducts(setOf("1", "2"), mapOf("price" to "500", "color" to ""))
        assertEquals(listOf("500", "500", "300"), result.products.map { it.values["price"] })
        assertEquals(listOf("", ""), result.products.take(2).map { it.values["color"] })
        assertEquals(project.products[2], result.products[2])
        result.products.zip(project.products).forEach { (after, before) ->
            assertEquals(before.values["sku"], after.values["sku"])
            assertEquals(before.values["name"], after.values["name"])
            assertEquals(before.pictures, after.pictures)
        }
        assertEquals("100", project.products[0].values["price"])
    }

    @Test fun rejectsMixedFormsMissingProductsAndUniqueOrUnknownFields() {
        for (ids in listOf(emptySet(), setOf("1", "3"), setOf("missing"))) {
            assertFailsWith<IllegalArgumentException> { project.updateProducts(ids, mapOf("price" to "1")) }
            assertFailsWith<IllegalArgumentException> { project.deleteProducts(ids) }
        }
        for (changes in listOf(emptyMap(), mapOf("sku" to "DUPLICATE"), mapOf("missing" to "1"))) {
            assertFailsWith<IllegalArgumentException> { project.updateProducts(setOf("1", "2"), changes) }
        }
    }

    @Test fun deletionKeepsOtherProductsAndTemplates() {
        val result = project.deleteProducts(setOf("1", "2"))
        assertEquals(listOf(project.products[2]), result.products)
        assertEquals(project.templates, result.templates)
    }

    @Test fun generatesUniqueArticlesForSelectionAndCanAlsoUpdateFields() {
        val ids = setOf("1", "2")
        val result = project.updateProducts(ids, emptyMap(), regenerateArticles = true)
        val articles = result.products.take(2).map { it.values.getValue("sku") }
        assertEquals(2, articles.toSet().size)
        assertTrue(articles.all { isValidArticle(it) && it.length == GENERATED_ARTICLE_LENGTH && it !in project.usedArticles() })
        assertEquals(project.products[2], result.products[2])
        result.products.take(2).zip(project.products).forEach { (after, before) ->
            assertEquals(before.values - "sku", after.values - "sku")
            assertEquals(before.pictures, after.pictures)
        }
        val again = result.updateProducts(ids, mapOf("price" to "500"), regenerateArticles = true)
        assertTrue(again.products.take(2).all { it.values["sku"] !in result.usedArticles() && it.values["price"] == "500" })
        assertFailsWith<IllegalArgumentException> { project.updateProducts(setOf("1", "3"), emptyMap(), true) }
    }

    @Test fun themeDefaultsToLightForExistingSettingsAndSurvivesSerialization() {
        assertFalse(projectJson.decodeFromString<Settings>("{}").darkTheme)
        val dark = project.copy(settings = project.settings.copy(darkTheme = true))
        assertEquals(dark, projectJson.decodeFromString<Project>(projectJson.encodeToString(Project.serializer(), dark)))
    }
}
