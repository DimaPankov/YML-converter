package ru.ymlstudio

import kotlin.test.*

class ExportValidationTest {
    private fun Project.values(vararg entries: Pair<String, String>) =
        copy(products = products.map { it.copy(values = it.values + entries) })

    @Test fun legacyRegionSatisfiesRequiredNamedRegions() {
        val p = validProject().let { it.copy(templates = it.templates.map { t ->
            t.copy(fields = t.fields + Field("oldRegion", "region", "Регион"))
        }) }.values("regions" to "", "oldRegion" to "50")
        assertEquals(emptyList(), validate(p).errors)
        assertTrue("<region id=\"50\"></region>" in buildYml(p))
    }

    @Test fun unusedLegacyRegionDoesNotBlockNamedRegions() {
        for (oldValue in listOf("", "не числовой ID")) {
            val p = validProject().let { it.copy(templates = it.templates.map { t ->
                t.copy(fields = t.fields + Field("oldRegion", "region", "Регион", required = true))
            }) }.values("oldRegion" to oldValue)
            assertEquals(emptyList(), validate(p).errors)
            assertTrue("<region>Москва</region>" in buildYml(p))
            assertFalse("<region id=" in buildYml(p))
        }
    }

    @Test fun missingFieldUsesFormLabelOnceAndIdentifiesProduct() {
        val p = validProject().let { it.copy(templates = it.templates.map { t ->
            t.copy(fields = t.fields.map { if (it.target == "price") it.copy(label = "Цена товара") else it })
        }) }.values("price" to "  ")
        val errors = validate(p).errors
        assertEquals(1, errors.size)
        assertTrue("артикул A001" in errors.single())
        assertTrue("заполните «Цена товара»" in errors.single())
        assertEquals(errors.joinToString("\n"), assertFailsWith<IllegalArgumentException> { buildYml(p) }.message)
    }

    @Test fun emptyArticlesAndDatesDoNotProduceSecondaryErrors() {
        val p = validProject().values("id" to "", "beginDate" to "", "endDate" to "")
            .let { it.copy(products = it.products + it.products.single().copy(id = "second")) }
        val errors = validate(p).errors
        assertEquals(6, errors.size, errors.toString())
        assertTrue(errors.all { "заполните" in it })
    }

    @Test fun customFieldIdsAndTrimmedValuesExportWithoutFalseMissingErrors() {
        val p = validProject().let { original -> original.copy(
            templates = original.templates.map { t -> t.copy(fields = t.fields.map { it.copy(id = "custom-${it.id}") }) },
            products = original.products.map { product -> product.copy(values = product.values.mapKeys { "custom-${it.key}" }.mapValues { " ${it.value} " }) }
        ) }.values("custom-delivery" to "false")
        assertEquals(emptyList(), validate(p).errors)
        val xml = buildYml(p)
        assertTrue("cost=\"0\" days=\"1-30\"" in xml)
        assertTrue("<delivery>false</delivery>" in xml)
    }

    @Test fun requiredCharacteristicStillBlocksExportDespiteDefault() {
        val p = validProject().let { it.copy(templates = it.templates.map { t ->
            t.copy(fields = t.fields.map { if (it.id == "param0") it.copy(required = true, default = "Белый") else it })
        }) }.values("param0" to "")
        assertEquals(1, validate(p).errors.size)
        assertTrue("Цвет" in validate(p).errors.single())
        assertFailsWith<IllegalArgumentException> { buildYml(p) }
    }
}
