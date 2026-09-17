package ru.ymlstudio

import kotlin.test.*

fun bulkAutomationProject(): Project {
    val base = validProject()
    val template = base.templates.single().let { t -> t.copy(fields = t.fields.map {
        when (it.id) { "name" -> it.copy(copyVariable = "title"); "param0" -> it.copy(copyVariable = "color"); else -> it }
    } + Field("size", "param", "Размер", copyVariable = "size"), copyPattern = "title{Футболка color, size}") }
    return base.copy(templates = listOf(template), products = (1..3).map { index ->
        base.products.single().copy(id = "item$index", values = base.products.single().values + mapOf(
            "id" to "SKU$index", "name" to "Футболка синий, ${index * 10}", "param0" to "синий", "size" to "${index * 10}"
        ), pictures = listOf(Picture(url = "https://example.com/$index.jpg"), Picture(url = "https://example.com/back$index.jpg")))
    })
}

class BulkAutomationTest {
    @Test fun templateChangesColorAndRebuildsNamesKeepingDifferentSizes() {
        val p = bulkAutomationProject()
        val text = "title{Футболка color{красный}, size{10}}"
        val changed = p.updateProducts(setOf("item1", "item2"), emptyMap(), templateChange = BulkTemplateChange(text))
        assertEquals(listOf("10", "20"), changed.products.take(2).map { it.values["size"] })
        assertEquals(listOf("Футболка красный, 10", "Футболка красный, 20"), changed.products.take(2).map { it.values["name"] })
        assertEquals(p.products[2], changed.products[2])
        assertEquals(p.products.map { it.values["id"] }, changed.products.map { it.values["id"] })
        assertEquals(p.products.map { it.pictures }, changed.products.map { it.pictures })
    }

    @Test fun explicitAllModeCanUnifyValuesAndMalformedTextIsRejected() {
        val p = bulkAutomationProject()
        val text = p.templates.single().copyText(p.products.first())
        val changed = p.updateProducts(setOf("item1", "item2"), emptyMap(), templateChange = BulkTemplateChange(text, applyAll = true))
        assertEquals(listOf("10", "10"), changed.products.take(2).map { it.values["size"] })
        assertFailsWith<IllegalArgumentException> { p.updateProducts(setOf("item1", "item2"), emptyMap(), templateChange = BulkTemplateChange("title{color, size}")) }
        assertFailsWith<IllegalArgumentException> { p.updateProducts(setOf("item1"), mapOf("price" to "5"), templateChange = BulkTemplateChange(text)) }
    }

    @Test fun editsOnlySelectedPhotoAndRejectsMissingPhotoOrInvalidLink() {
        val p = bulkAutomationProject()
        val photo = BulkPhotoChange(BulkPhotoMode.EDIT_URL, photoIndex = 1, replacementUrl = " https://example.com/new.jpg ")
        val result = p.updateProducts(setOf("item1", "item2"), emptyMap(), photos = photo)
        for (index in 0..1) {
            assertEquals(p.products[index].pictures[0], result.products[index].pictures[0])
            assertEquals(Picture(url = "https://example.com/new.jpg"), result.products[index].pictures[1])
            assertEquals(p.products[index].values, result.products[index].values)
        }
        assertEquals(p.products[2], result.products[2])
        assertFailsWith<IllegalArgumentException> { p.updateProducts(setOf("item1"), emptyMap(), photos = photo.copy(photoIndex = 2)) }
        assertFailsWith<IllegalArgumentException> { p.updateProducts(setOf("item1"), emptyMap(), photos = photo.copy(replacementUrl = "file:///local.jpg")) }
    }

    @Test fun combinesTemplateAndPhotoUpdateAtomically() {
        val p = bulkAutomationProject()
        val result = p.updateProducts(setOf("item1", "item2"), emptyMap(), photos = BulkPhotoChange(BulkPhotoMode.EDIT_URL, replacementUrl = "https://example.com/new.jpg"),
            templateChange = BulkTemplateChange("title{Футболка color{белый}, size{10}}"))
        assertEquals("20", result.products[1].values["size"])
        assertEquals("белый", result.products[1].values["param0"])
        assertEquals("https://example.com/new.jpg", result.products[1].pictures.first().url)
        assertEquals(p.products[2], result.products[2])
    }
}
