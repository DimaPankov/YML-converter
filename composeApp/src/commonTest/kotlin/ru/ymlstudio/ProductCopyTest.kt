package ru.ymlstudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductCopyTest {
    @Test fun steIsNotInheritedButCanBeEnteredExplicitlyForCopy() {
        val form = Template("t", "Форма", fields = listOf(
            Field("sku", "id", "Артикул"), Field("title", "name", "Название"),
            Field("portalId", "ste", "ID СТЕ", default = "38910216")))
        val source = Product("source", "t", mapOf("sku" to "A1", "title" to "Товар", "portalId" to "38910216"))
        val project = defaultProject().copy(templates = listOf(form), products = listOf(source))
        assertEquals("", project.createProduct("new", form).values["portalId"])
        val copy = project.duplicateProduct(source, "copy")
        assertEquals("", copy.values["portalId"])
        assertEquals("38910216", source.values["portalId"])
        assertEquals("38910217", project.finishProductCopy(copy, mapOf("portalId" to "38910217")).values["portalId"])
        val oldForm = form.copy(fields = form.fields.filter { it.target != "ste" })
        val oldProject = project.copy(templates = listOf(oldForm), products = listOf(source.copy(values = source.values - "portalId")))
        assertEquals("", oldProject.updateTemplate(form).products.single().values["portalId"])
    }
    @Test fun quickCopyChangesSelectedFieldsAndRejectsDuplicateArticle() {
        val template = Template("t", "Форма", fields = listOf(
            Field("sku", "id", "Артикул", quickAccess = true),
            Field("title", "name", "Название"),
            Field("cost", "price", "Цена", quickAccess = true),
            Field("color", "param", "Цвет", quickAccess = true),
            Field("hidden", "param", "Материал")
        ))
        val source = Product("source", "t", mapOf("sku" to "A1", "title" to "Майка", "cost" to "100", "color" to "Красный", "hidden" to "Хлопок"))
        val project = defaultProject().copy(templates = listOf(template), products = listOf(source))
        val draft = project.duplicateProduct(source, "copy")
        val edited = project.finishProductCopy(draft, mapOf("title" to " Майка XL ", "cost" to "200", "color" to "Белый", "hidden" to "Не менять"))
        assertEquals("Майка XL", edited.values["title"])
        assertEquals("200", edited.values["cost"])
        assertEquals("Белый", edited.values["color"])
        assertEquals("Хлопок", edited.values["hidden"])
        assertEquals("100", source.values["cost"])
        assertFailsWith<IllegalArgumentException> { project.finishProductCopy(draft, mapOf("sku" to "A1")) }
        assertFailsWith<IllegalArgumentException> { project.finishProductCopy(draft, mapOf("title" to " ")) }
        assertFailsWith<IllegalArgumentException> { project.finishProductCopy(draft, mapOf("sku" to "!")) }
    }

    @Test fun oldFormsDecodeWithoutQuickAccessAndVatFollowsSettings() {
        val field = projectJson.decodeFromString<Field>("""{"id":"price","target":"price","label":"Цена"}""")
        assertFalse(field.quickAccess)
        val template = defaultTemplate().copy(fields = defaultTemplate().fields.map { it.copy(quickAccess = it.target == "vat") })
        assertEquals(listOf("name"), template.copyFields(Settings()).map { it.target })
        assertEquals(listOf("name", "vat"), template.copyFields(Settings(useVat = true)).map { it.target })
    }

    @Test fun newProductsGetUniqueArticlesAcrossFormsIgnoringFormDefault() {
        val firstForm = defaultTemplate()
        val secondForm = firstForm.copy(id = "other", fields = firstForm.fields.map {
            if (it.target == "id") it.copy(id = "customSku", default = "1") else it
        })
        val initial = defaultProject().copy(templates = listOf(firstForm, secondForm))
        val first = initial.createProduct("first", firstForm)
        assertTrue(isValidArticle(first.values.getValue("id")))
        assertEquals(12, first.values.getValue("id").length)
        val pair = initial.copy(products = listOf(first))
        val second = pair.createProduct("second", secondForm)
        assertTrue(isValidArticle(second.values.getValue("customSku")))
        assertNotEquals("1", second.values["customSku"])
        val third = pair.copy(products = listOf(first, second)).createProduct("third", firstForm)
        assertEquals(3, setOf(first.values["id"], second.values["customSku"], third.values["id"]).size)
    }

    @Test fun copyPreservesCustomFieldsAndPhotosAndAllocatesUniqueArticleAcrossForms() {
        val form = Template("custom", "Товар", fields = listOf(
            Field("sku", "id", "Артикул"), Field("title", "name", "Название"),
            Field("tax", "vat", "НДС"), Field("color", "param", "Цвет")
        ))
        val source = Product("original", form.id,
            mapOf("sku" to "438", "title" to "Футболка", "tax" to "VAT_5", "color" to "Оранжевый"),
            listOf(Picture(url = "https://example.com/photo.jpg"), Picture(file = "a".repeat(32) + ".png")))
        val otherForm = form.copy(id = "other", fields = listOf(Field("otherSku", "id", "Артикул")))
        val project = defaultProject().copy(templates = listOf(form, otherForm), products = listOf(
            source, Product("existing", otherForm.id, mapOf("otherSku" to "438C1"))))
        val copy = project.duplicateProduct(source, "copy")
        assertNotEquals(source.id, copy.id)
        assertTrue(isValidArticle(copy.values.getValue("sku")))
        assertEquals(12, copy.values.getValue("sku").length)
        assertFalse(copy.values["sku"] in project.usedArticles())
        assertEquals(source.values - "sku", copy.values - "sku")
        assertEquals(source.pictures, copy.pictures)
        assertEquals(source.templateId, copy.templateId)
        val edited = copy.copy(values = copy.values + ("title" to "Новое название"), pictures = emptyList())
        assertEquals("Футболка", source.values["title"])
        assertEquals(2, source.pictures.size)
        assertEquals("Новое название", edited.values["title"])
        val next = project.copy(products = project.products + edited).duplicateProduct(source, "next")
        assertTrue(isValidArticle(next.values.getValue("sku")))
        assertFalse(next.values["sku"] in project.usedArticles() + copy.values.getValue("sku"))
    }
}
