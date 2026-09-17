package ru.ymlstudio

import kotlin.test.*

class DeliveryDaysMappingTest {
    private fun brokenProject(): Project {
        val project = validProject()
        val template = project.templates.single().copy(fields = project.templates.single().fields +
            Field("legacyDays", "param", "Срок доставки, рабочие дни", default = "60"))
        return project.copy(templates = listOf(template), products = (0 until 15).map { index ->
            project.products.single().copy(id = "product$index", values = project.products.single().values +
                mapOf("id" to "SKU$index", "deliveryDays" to if (index < 4) "60" else "", "legacyDays" to "60"))
        })
    }

    @Test fun restoresFilledParameterForElevenProductsAndExportsDaysForAllFifteen() {
        val before = brokenProject()
        assertTrue(validate(before).errors.isEmpty())
        assertEquals(15, Regex("days=\"60\"").findAll(buildYml(before)).count())
        val after = before.withDeliveryDaysMapping()
        assertTrue(validate(after).errors.isEmpty(), validate(after).errors.toString())
        assertTrue(after.products.all { it.values["deliveryDays"] == "60" })
        assertFalse(after.templates.single().fields.any { it.isDeliveryDaysParameter() })
        assertEquals("60", after.templates.single().fields.first { it.target == "deliveryDays" }.default)
        val yml = buildYml(after)
        assertEquals(15, Regex("days=\"60\"").findAll(yml).count())
        assertFalse("param name=\"Срок доставки, рабочие дни\"" in yml)
        assertEquals(after, after.withDeliveryDaysMapping())
        assertEquals("", before.products.last().values["deliveryDays"])
    }

    @Test fun preservesExplicitDifferentDaysAndConflictingParameters() {
        val before = brokenProject().let { it.copy(products = listOf(it.products.first().copy(values = it.products.first().values + ("deliveryDays" to "5")))) }
        val after = before.withDeliveryDaysMapping()
        assertEquals("5", after.products.single().values["deliveryDays"])
        assertEquals("60", after.products.single().values["legacyDays"])
        assertTrue(after.templates.single().fields.any { it.isDeliveryDaysParameter() })
        assertTrue(buildYml(after).contains("days=\"5\""))
        assertTrue(validate(after).warnings.any { "deliveryDays" in it })
    }

    @Test fun doesNotInventDaysFromDefaultOrInvalidOrAmbiguousValues() {
        for (value in listOf("", "завтра", "30 дней", "60-1")) {
            val before = brokenProject().let { it.copy(products = listOf(it.products.last().copy(values = it.products.last().values + ("legacyDays" to value)))) }
            assertEquals("", before.withDeliveryDaysMapping().products.single().values["deliveryDays"], value)
        }
        val before = brokenProject().let { it.copy(
            templates = it.templates.map { t -> t.copy(fields = t.fields + Field("otherDays", "param", "Срок доставки, рабочие дни")) },
            products = listOf(it.products.last().copy(values = it.products.last().values + ("otherDays" to "7")))) }
        val after = before.withDeliveryDaysMapping()
        assertEquals("", after.products.single().values["deliveryDays"])
        assertEquals(2, after.templates.single().fields.count { it.isDeliveryDaysParameter() })
    }

    @Test fun supportsCustomFieldIdsAndMissingStandardField() {
        val before = brokenProject().let { it.copy(
            templates = it.templates.map { t -> t.copy(fields = t.fields.map { f -> if (f.target == "deliveryDays") f.copy(id = "custom-days") else f }) },
            products = it.products.map { p -> p.copy(values = p.values - "deliveryDays") }) }
        assertTrue(before.withDeliveryDaysMapping().products.all { it.values["custom-days"] == "60" })
        val missing = before.copy(templates = before.templates.map { t -> t.copy(fields = t.fields.filter { it.target != "deliveryDays" }) })
        val repaired = missing.withDeliveryDaysMapping()
        assertEquals(1, repaired.templates.single().fields.count { it.target == "deliveryDays" })
        assertTrue(repaired.products.all { it.values["deliveryDays"] == "60" })
    }
}
