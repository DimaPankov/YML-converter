package ru.ymlstudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProductCopyTest {
    @Test fun newProductsGetUniqueArticlesAcrossFormsIgnoringFormDefault() {
        val firstForm = defaultTemplate()
        val secondForm = firstForm.copy(id = "other", fields = firstForm.fields.map {
            if (it.target == "id") it.copy(id = "customSku", default = "1") else it
        })
        val initial = defaultProject().copy(templates = listOf(firstForm, secondForm))
        val first = initial.createProduct("first", firstForm)
        assertEquals("1", first.values["id"])
        val pair = initial.copy(products = listOf(first))
        val second = pair.createProduct("second", secondForm)
        assertEquals("2", second.values["customSku"])
        val third = pair.copy(products = listOf(first, second)).createProduct("third", firstForm)
        assertEquals("3", third.values["id"])
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
        assertEquals("438C2", copy.values["sku"])
        assertEquals(source.values - "sku", copy.values - "sku")
        assertEquals(source.pictures, copy.pictures)
        assertEquals(source.templateId, copy.templateId)
        val edited = copy.copy(values = copy.values + ("title" to "Новое название"), pictures = emptyList())
        assertEquals("Футболка", source.values["title"])
        assertEquals(2, source.pictures.size)
        assertEquals("Новое название", edited.values["title"])
        val next = project.copy(products = project.products + edited).duplicateProduct(source, "next")
        assertEquals("438C3", next.values["sku"])
    }
}
