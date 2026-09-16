package ru.ymlstudio

import java.nio.file.Files
import kotlin.test.*

class YmlImportTest {
    @Test fun namedDeliveryParameterBecomesDeliveryOptionOnImportAndTemplateCreation() {
        val preview = parse("""<offers><offer id="1"><name>Товар</name>
            <delivery-options><option cost="0" days=""/></delivery-options>
            <param name="Срок доставки, рабочие дни">60</param></offer></offers>""")
        assertEquals("60", preview.products.single().values["deliveryDays"])
        assertFalse(preview.template.fields.any { it.isDeliveryDaysParameter() })
        val form = preview.asTemplate(0, "Образец")
        assertEquals("60", newProduct("new", form).values["deliveryDays"])
        assertTrue(buildYml(preview.addProductsTo(defaultProject()).project, allowInvalid = true).contains("cost=\"0\" days=\"60\""))
    }

    @Test fun invalidImportedArticlesAreReplacedButValidOriginalIsPreserved() {
        val preview = parse("""<offers>
            <offer id="A-1"><name>A</name></offer>
            <offer id="товар"><name>B</name></offer>
            <offer id="ABC123"><name>C</name></offer>
        </offers>""")
        val result = preview.addProductsTo(defaultProject())
        assertEquals(2, result.changedArticles)
        val articles = result.project.products.map { it.values.getValue("id") }
        assertTrue(articles.all(::isValidArticle))
        assertEquals(listOf(12, 12), articles.take(2).map { it.length })
        assertEquals("ABC123", articles.last())
        assertEquals(3, articles.distinct().size)
    }

    @Test fun sameFieldsWithDifferentValuesAreOneFormAndDifferentFieldsStaySeparate() {
        val preview = parse("""<offers>
            <offer id="1"><name>A</name><price>100</price><param name="Цвет">Белый</param></offer>
            <offer id="2"><param name="Цвет">Синий</param><price>200</price><name>B</name></offer>
            <offer id="3"><name>C</name><param name="Размер">XL</param></offer>
        </offers>""")
        assertEquals(listOf(YmlFormGroup(0, 2), YmlFormGroup(2, 1)), preview.formGroups)
        val result = preview.addProductsTo(defaultProject()).project
        assertEquals(result.products[0].templateId, result.products[1].templateId)
        assertNotEquals(result.products[0].templateId, result.products[2].templateId)
        assertEquals(3, result.templates.size)
    }

    @Test fun repeatedImportsReuseMatchingFormWithCustomFieldIdsAndKeepQuickAccess() {
        val preview = parse(filled)
        val existing = preview.asTemplate(0, "Моя форма").copy(id = "existing").let { t ->
            t.copy(fields = t.fields.map { it.copy(id = "custom-${it.id}", quickAccess = true) })
        }
        val project = defaultProject().copy(templates = listOf(existing))
        val once = preview.addProductsTo(project).project
        assertEquals(listOf(existing), once.templates)
        assertEquals("existing", once.products.single().templateId)
        assertEquals("438", once.products.single().values["custom-id"])
        assertEquals("Красный", once.products.single().values["custom-param0"])
        val twice = parse(filled).addProductsTo(once).project
        assertEquals(listOf(existing), twice.templates)
        assertEquals(2, twice.products.size)
        assertNotEquals(twice.products[0].values["custom-id"], twice.products[1].values["custom-id"])
    }

    private fun parse(xml: String) = parseYmlImport(xml.toByteArray(), "Импорт")
    private val filled = """
        <offer id="438" available="false">
          <name>Футболка &amp; майка</name><price>667.50</price><ppCategory>793363662</ppCategory>
          <oksm code="643">РОССИЯ</oksm><okei id="796">Штука</okei><vat>5</vat>
          <regions><region>Москва</region><region>Московская область</region></regions>
          <delivery-options><option cost="0" days="1-30"/></delivery-options>
          <picture>https://example.com/shirt.jpg</picture>
          <param name="Цвет">Красный</param><param name="Плотность" unit="г/м2">135</param>
        </offer>
    """.trimIndent()

    @Test fun importsNamespaceCatalogAndPreservesOfferValuesOnExport() {
        val result = parse("<yml_catalog xmlns=\"$YML_NAMESPACE\"><shop><offers>$filled</offers></shop></yml_catalog>")
        val p = result.products.single()
        assertEquals("Футболка & майка", p.values["name"])
        assertEquals("643", p.values["oksm"])
        assertEquals("796 | Штука", p.values["okei"])
        assertEquals("VAT_5", p.values["vat"])
        assertEquals("Москва; Московская область", p.values["regions"])
        assertEquals("1-30", p.values["deliveryDays"])
        assertEquals("false", p.values["available"])
        assertEquals("г/м2", result.template.fields.last().unit)
        val project = result.addProductsTo(defaultProject().copy(settings = Settings(useVat = true))).project
        val roundtrip = parse(buildYml(project, allowInvalid = true)).products.single()
        for (key in listOf("id", "name", "price", "ppCategory", "oksm", "okei", "regions", "deliveryCost", "deliveryDays", "available", "vat", "param0", "param1")) {
            assertEquals(p.values[key], roundtrip.values[key], key)
        }
        assertEquals(p.pictures, roundtrip.pictures)
    }

    @Test fun heterogeneousProductsAndRepeatedParametersKeepSeparateValues() {
        val result = parse("""<offers>
            <offer id="1"><name>A</name><param name="Цвет">Красный</param><param name="Цвет">Синий</param></offer>
            <offer id="2"><name>B</name><param name="Размер">XL</param><param name="Цвет">Белый</param></offer>
        </offers>""")
        val fields = result.template.fields.filter { it.target == "param" }
        assertEquals(listOf("Цвет", "Цвет", "Размер"), fields.map { it.label })
        assertEquals(listOf("Красный", "Синий", null), fields.map { result.products[0].values[it.id] })
        assertEquals(listOf("Белый", null, "XL"), fields.map { result.products[1].values[it.id] })
        val form = result.asTemplate(1, "Образец B")
        assertEquals(listOf("Белый", "XL"), form.fields.filter { it.target == "param" }.map { it.default })
    }

    @Test fun importAddsWithoutReplacingAndAssignsUniqueArticles() {
        val existing = defaultProject().let { it.copy(products = listOf(Product("existing", it.templates.single().id, mapOf("id" to "1")))) }
        val imported = parse("<offers><offer id=\"1\"><name>A</name></offer><offer id=\"1\"><name>B</name></offer><offer><name>C</name></offer></offers>")
        val result = imported.addProductsTo(existing)
        assertEquals(existing.products.single(), result.project.products.first())
        assertEquals(existing.settings, result.project.settings)
        assertEquals(4, result.project.products.size)
        assertEquals(3, result.changedArticles)
        val articles = result.project.products.map { it.values["id"] }
        assertEquals(4, articles.distinct().size)
        assertEquals(4, result.project.products.map { it.id }.distinct().size)
        checkShape(result.project)
    }

    @Test fun filledTemplateDefaultsAndQuickAccessSurviveSaveAndNewProduct() {
        val form = parse(filled).asTemplate(0, "Футболки").let { t ->
            t.copy(fields = t.fields.map { it.copy(quickAccess = it.target == "price") })
        }
        val project = defaultProject().copy(templates = listOf(form))
        ProjectRepository(Files.createTempDirectory("yml-import-form")).use { repo ->
            repo.save(project)
            val restored = repo.load().withMinimalPreset()
            val template = restored.templates.single()
            assertEquals(form, template)
            val product = restored.createProduct("new", template)
            assertTrue(isValidArticle(product.values.getValue("id")))
            assertEquals(12, product.values.getValue("id").length)
            assertNotEquals("438", product.values["id"])
            assertEquals("667.50", product.values["price"])
            assertEquals("Футболка & майка", product.values["name"])
            assertEquals(form.defaultPictures, product.pictures)
            assertEquals(listOf("name", "price"), template.copyFields(restored.settings).map { it.target })
        }
    }

    @Test fun malformedExternalEntityEmptyAndAmbiguousDeliveryAreRejected() {
        for (xml in listOf("<offer>", "<offers/>", "<project/>",
            """<!DOCTYPE offer [<!ENTITY secret SYSTEM "file:///etc/passwd">]><offer><name>&secret;</name></offer>""",
            """<offer><delivery-options><option cost="1"/><option cost="2"/></delivery-options></offer>""",
            "<offer><name>A</name><name>B</name></offer>")) {
            assertFails { parse(xml) }
        }
    }

    @Test fun unknownFieldsAreReportedAndXmlEncodingIsRespected() {
        val xml = """<?xml version="1.0" encoding="windows-1251"?><offer id="7"><name>Товар</name><unknown>42</unknown></offer>"""
        val result = parseYmlImport(xml.toByteArray(charset("windows-1251")), "Файл")
        assertEquals("Товар", result.products.single().values["name"])
        assertTrue(result.warnings.any { "unknown" in it })
    }
}
