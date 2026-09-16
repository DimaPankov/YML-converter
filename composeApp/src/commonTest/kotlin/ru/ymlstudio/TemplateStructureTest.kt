package ru.ymlstudio

import kotlin.test.*

class TemplateStructureTest {
    private val first = Template("a", "Первая", fields = listOf(
        Field("sku", "id", "Артикул"), Field("title", "name", "Название"),
        Field("cost", "price", "Цена", type = "number", default = "100"),
        Field("color", "param", "Цвет")))
    private val second = first.copy(id = "b", name = "Вторая", fields = first.fields.reversed().map { it.copy(id = "b-${it.id}", default = "Другое") })

    @Test fun structureIgnoresIdsOrderValuesAndEditorSettingsButDistinguishesFields() {
        assertEquals(first.structure(), second.structure())
        assertEquals(first.structure(), first.copy(fields = first.fields.map { it.copy(quickAccess = true, required = true) }).structure())
        assertNotEquals(first.structure(), first.copy(fields = first.fields.dropLast(1)).structure())
        assertNotEquals(first.structure(), first.copy(fields = first.fields + Field("another", "param", "Цвет")).structure())
        assertNotEquals(first.structure(), first.copy(fields = first.fields.map { if (it.target == "param") it.copy(unit = "см") else it }).structure())
    }

    @Test fun editUpdatesEquivalentTemplatesAndRemapsValuesWithoutOverwritingThem() {
        val different = first.copy(id = "c", fields = first.fields + Field("size", "param", "Размер"))
        val a = Product("p1", first.id, mapOf("sku" to "A1", "title" to "Красная", "cost" to "150", "color" to "Красный"))
        val b = Product("p2", second.id, mapOf("b-sku" to "A2", "b-title" to "Белая", "b-cost" to "200", "b-color" to "Белый"), listOf(Picture(url = "https://example.com/photo.jpg")))
        val c = Product("p3", different.id, mapOf("sku" to "A3", "size" to "XL"))
        val project = defaultProject().copy(templates = listOf(first, second, different), products = listOf(a, b, c))
        val edited = first.copy(fields = first.fields.filter { it.target != "price" }.map {
            if (it.id == "color") it.copy(label = "Оттенок", quickAccess = true, required = true) else it
        } + Field("fabric", "param", "Материал", default = "Хлопок"))
        val result = project.updateTemplate(edited)
        assertEquals(edited.fields, result.templates[1].fields)
        assertEquals("Вторая", result.templates[1].name)
        assertEquals(different, result.templates[2])
        assertEquals(c, result.products[2])
        assertEquals("b", result.products[1].templateId)
        assertEquals("Белый", result.products[1].values["color"])
        assertEquals("A2", result.products[1].values["sku"])
        assertEquals("Хлопок", result.products[0].values["fabric"])
        assertEquals("Хлопок", result.products[1].values["fabric"])
        assertEquals(b.pictures, result.products[1].pictures)
        assertFalse(result.products[1].valuesFor(result.templates[1]).containsKey("price"))
        checkShape(result)
        val again = result.updateTemplate(result.templates[1].copy(fields = result.templates[1].fields.map { it.copy(quickAccess = true) }))
        assertTrue(again.templates[0].fields.all { it.quickAccess })
        assertEquals(result.products, again.products)
    }

    @Test fun changingDefaultsPreservesExistingValuesAndPhotos() {
        val p = Product("p", first.id, mapOf("sku" to "1", "cost" to "100"))
        val project = defaultProject().copy(templates = listOf(first), products = listOf(p))
        val changed = project.updateTemplate(first.copy(fields = first.fields.map { it.copy(default = "999") }))
        assertEquals(p, changed.products.single())
        val restored = projectJson.decodeFromString<Project>(projectJson.encodeToString(Project.serializer(), changed))
        assertEquals(changed, restored)
    }
}
