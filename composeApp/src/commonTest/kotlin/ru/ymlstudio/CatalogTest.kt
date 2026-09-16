package ru.ymlstudio

import kotlin.test.*

fun validProject(): Project {
    val p = defaultProject().let { p -> p.copy(templates = listOf(defaultTemplate().let { t ->
        t.copy(fields = t.fields + fieldDefinitions.filter { it.target in listOf("vendor", "model", "delivery", "description") }.map { Field(it.target, it.target, it.label, it.type, it.required) } +
            listOf("Цвет", "Материал", "Ширина", "Высота").mapIndexed { i, label -> Field("param$i", "param", label) })
    })) }
    return p.copy(settings = Settings("Тест", "ООО «Тест & партнёры»", imageBase = "https://example.ru/images"),
        categories = listOf(Category("1", "Мебель")),
        products = listOf(Product("test-product", "basic", mapOf(
            "_portal_category" to "793363662", "id" to "A001", "name" to "Стол <Офис> & \"Дом\"", "price" to "1250,50", "categoryId" to "1", "vendor" to "Фабрика", "model" to "Стол 1", "oksm" to "643",
            "beginDate" to "2026-09-07T12:00", "endDate" to "2027-09-07T12:00", "delivery" to "true",
            "regions" to "Москва; Московская область", "deliveryCost" to "0", "deliveryDays" to "1-30", "okei" to "796", "min-quantity" to "1",
            "param0" to "Белый", "param1" to "Дерево", "param2" to "100", "param3" to "80"), listOf(Picture(url = "https://example.ru/table.png?a=1&b=2")))))
}
private fun Project.value(key: String, value: String) = copy(products = products.map { it.copy(values = it.values + (key to value)) })
class CatalogTest {
    @Test fun portalArticleRulesRejectHyphensAndCopiedOffersRemainDistinct() {
        for (id in listOf("438-1", "438-1-1", "товар", "A 1", "A".repeat(21))) {
            assertTrue(validate(validProject().value("id", id)).errors.any { "Артикул:" in it }, id)
        }
        val original = validProject().value("id", "438")
        val first = original.duplicateProduct(original.products.single(), "copy1")
        val pair = original.copy(products = original.products + first)
        val second = pair.duplicateProduct(first, "copy2")
        val triple = pair.copy(products = pair.products + second)
        assertTrue(validate(triple).errors.isEmpty())
        val yml = buildYml(triple)
        assertEquals(3, Regex("<offer id=").findAll(yml).count())
        val articles = triple.products.map { it.values.getValue("id") }
        assertEquals(3, articles.distinct().size)
        assertEquals("438", articles.first())
        for (id in articles) assertTrue("<offer id=\"$id\"" in yml)
    }

    @Test fun okeiAlwaysContainsNameForKnownCodesIncludingExistingProducts() {
        for ((id, name) in knownOkeiNames) {
            val p = validProject().value("okei", id)
            assertTrue(validate(p).errors.isEmpty())
            assertTrue(buildYml(p).contains("<okei id=\"$id\">$name</okei>"))
            assertEquals(OkeiValue(id, name), okeiValue(" $id | $name "))
        }
    }
    @Test fun unknownOkeiNeedsExplicitNameAndCannotBeConfusedWithCountry() {
        for (value in listOf("643", "12345", "796 | Россия", "0 | Штука", "abc | Штука")) {
            val p = validProject().value("okei", value)
            assertTrue(validate(p).errors.any { "ОКЕИ" in it }, value)
            assertFailsWith<IllegalArgumentException> { buildYml(p) }
        }
        val explicit = validProject().value("okei", "12345 | Тест & <единица>")
        assertTrue(validate(explicit).errors.isEmpty())
        assertTrue(validate(explicit).warnings.any { "ОКЕИ" in it })
        assertTrue(buildYml(explicit).contains("<okei id=\"12345\">Тест &amp; &lt;единица&gt;</okei>"))
        assertTrue(buildYml(validProject().value("okei", "643"), allowInvalid = true).contains("<okei id=\"643\"></okei>"))
    }

    @Test fun deliveryTermsAreRequiredAndValidatedIncludingFreeDelivery() {
        for (key in listOf("regions", "deliveryCost", "deliveryDays", "okei", "min-quantity")) {
            assertTrue(validate(validProject().value(key, "")).errors.isNotEmpty(), key)
            assertFailsWith<IllegalArgumentException>(key) { buildYml(validProject().value(key, "")) }
        }
        for (cost in listOf("-1", "1.5", "free", "NaN")) assertTrue(validate(validProject().value("deliveryCost", cost)).errors.any { "Стоимость доставки" in it })
        for (days in listOf("30-1", "1-", "1.5", "-1", "завтра")) assertTrue(validate(validProject().value("deliveryDays", days)).errors.any { "Срок доставки" in it })
        for (days in listOf("0", "1", "1-30")) assertTrue(validate(validProject().value("deliveryDays", days)).errors.isEmpty(), days)
        assertTrue(validate(validProject().value("regions", "; ;")).errors.any { "регион" in it })
        assertTrue(buildYml(validProject()).contains("cost=\"0\" days=\"1-30\""))
    }
    @Test fun removedRequiredMappingStillBlocksStrictExportButForcedXmlIsAvailable() {
        val p = validProject().let { it.copy(templates = it.templates.map { t -> t.copy(fields = t.fields.filter { f -> f.target != "deliveryCost" }) }) }
        assertTrue(validate(p).errors.any { "Стоимость доставки" in it })
        assertFailsWith<IllegalArgumentException> { buildYml(p) }
        assertTrue(buildYml(p, allowInvalid = true).contains("cost=\"\" days=\"1-30\""))
    }
    @Test fun countryAliasesAndExplicitCodesUseVocabularyAttributes() {
        for (input in listOf("Китай", "156", "156 | КИТАЙ")) assertTrue(buildYml(validProject().value("oksm", input)).contains("<oksm code=\"156\">КИТАЙ</oksm>"))
        assertTrue(buildYml(validProject().value("oksm", "398 | КАЗАХСТАН")).contains("<oksm code=\"398\">КАЗАХСТАН</oksm>"))
        assertTrue(validate(validProject().value("oksm", "неизвестно")).errors.any { "ОКСМ" in it })
        assertTrue(buildYml(validProject().value("oksm", "неизвестно"), allowInvalid = true).contains("<oksm>неизвестно</oksm>"))
    }
    @Test fun optionalFieldsAndCharacteristicsDoNotBlockMinimalImport() {
        var p = validProject()
        for (key in listOf("vendor", "model", "delivery", "param0", "param1", "param2", "param3")) p = p.value(key, "")
        assertTrue(validate(p).errors.isEmpty())
        assertTrue(validate(p).warnings.any { "4 характеристики" in it })
        assertTrue(validate(p.value("id", "БФ55И-01")).errors.any { "Артикул:" in it })
    }
    @Test fun legacyRegionMovesInsideContainerAndPackageUsesSchemaName() {
        val p = validProject().let { it.copy(templates = it.templates.map { t -> t.copy(fields = t.fields.filter { f -> f.target != "regions" } + Field("oldRegion", "region", "Регион") + Field("pack", "packageType", "Упаковка")) }) }.value("oldRegion", "50").value("pack", "4")
        assertTrue(validate(p).errors.isEmpty())
        val xml = buildYml(p)
        assertTrue(xml.contains("<regions>\n        <region id=\"50\"></region>\n      </regions>"))
        assertTrue(xml.contains("<package id=\"4\">коробка бумажная</package>"))
        assertFalse(xml.contains("<packageType"))
    }
    @Test fun migrationPreservesValuesAndDoesNotInventDeliveryTerms() {
        val original = validProject().let { it.copy(importRulesVersion = 0, templates = it.templates.map { t -> t.copy(fields = t.fields.filter { f -> f.target !in listOf("regions", "deliveryCost", "deliveryDays", "okei", "min-quantity") }) }, products = it.products.map { p -> p.copy(values = p.values.filterKeys { k -> k !in listOf("regions", "deliveryCost", "deliveryDays", "okei", "min-quantity") }) }) }
        val migrated = original.withImportRules()
        assertEquals(original.products, migrated.products)
        assertEquals(migrated, migrated.withImportRules())
        assertTrue(validate(migrated).errors.any { "Стоимость доставки" in it })
        assertTrue(migrated.templates.single().fields.any { it.target == "regions" })
        val removed = migrated.copy(templates = migrated.templates.map { it.copy(fields = it.fields.filter { f -> f.target != "regions" }) })
        assertEquals(removed, removed.withImportRules())
        assertEquals(migrated, projectJson.decodeFromString<Project>(projectJson.encodeToString(Project.serializer(), migrated)))
    }

    @Test fun portalCategoryIsAlwaysRequiredEvenWithOldFlagFalse() {
        val original = validProject()
        val p = original.copy(settings = original.settings.copy(usePortalCategories = false), categories = emptyList())
        assertTrue(validate(p).errors.isEmpty())
        val xml = buildYml(p)
        assertTrue(xml.contains("<ppCategory>793363662</ppCategory>"))
        assertFalse(xml.contains("<categoryId>"))
        assertTrue(xml.contains("<category id=\"1\">Товары</category>"))
        val missing = p.value("_portal_category", "")
        assertTrue(validate(missing).errors.any { "ID категории портала" in it })
        assertFailsWith<IllegalArgumentException> { buildYml(missing) }
    }
    @Test fun oldSupplierIdNeverBecomesPortalIdAndMigrationPreservesStoredPortalId() {
        val old = validProject().let { it.copy(settings = it.settings.copy(usePortalCategories = false), templates = it.templates.map { t -> t.copy(fields = t.fields.filter { f -> f.target != "ppCategory" } + Field("categoryId", "categoryId", "Категория", "category")) }) }
        val missing = old.value("_portal_category", "").value("categoryId", "793363662")
        val migrated = missing.withMinimalPreset()
        assertTrue(validate(migrated).errors.any { "ID категории портала" in it })
        assertEquals("793363662", migrated.products.single().values["categoryId"])
        assertTrue(migrated.templates.single().fields.none { it.target == "categoryId" })
        assertEquals(migrated, migrated.withMinimalPreset())
        val present = old.withMinimalPreset()
        assertEquals("793363662", present.products.single().valuesFor(present.templates.single(), present.settings)["ppCategory"])
    }

    @Test fun vat24IsRejectedButForcedExportStillPreservesIt() {
        val p = validProject().let { it.copy(settings = it.settings.copy(useVat = true)) }.value("vat", "24")
        assertTrue(validate(p).errors.any { "ndsType" in it && "24" in it })
        assertFailsWith<IllegalArgumentException> { buildYml(p) }
        assertTrue(buildYml(p, allowInvalid = true).contains("<vat>24</vat>"))
        assertFalse(buildYml(p.copy(settings = p.settings.copy(useVat = false))).contains("<vat>"))
    }
    @Test fun allSchemaVatAliasesExportAsSupportedCodes() {
        val p = validProject().let { it.copy(settings = it.settings.copy(useVat = true)) }
        vatAliases.forEach { (input, code) ->
            assertTrue(validate(p.value("vat", input)).errors.isEmpty(), input)
            assertTrue(buildYml(p.value("vat", input)).contains("<vat>$code</vat>"), input)
        }
        val numericField = p.copy(templates = p.templates.map { t -> t.copy(fields = t.fields.map { if (it.target == "vat") it.copy(type = "number") else it }) })
        assertTrue(validate(numericField.value("vat", "VAT_5")).errors.isEmpty())
        assertNull(canonicalVat("24"))
        assertNull(canonicalVat("VAT_24"))
        assertEquals("24", vatSelection("24"))
        assertTrue(vatChoices("24").any { it.first == "24" && it.second.startsWith("Недопустимое") })
    }

    @Test fun portalExampleMistakesAreReportedWithoutInventingDictionaryRules() {
        val p = validProject().let { p -> p.copy(
            settings = p.settings.copy(useVat = true),
            templates = p.templates.map { t -> t.copy(fields = t.fields + listOf("isVisibleToStateCustomers", "isAvailableToIndividuals", "available").map { key ->
                val d = fieldDefinitions.first { it.target == key }; Field(key, key, d.label, d.type)
            }) }
        ) }.value("categoryId", "").value("_portal_category", "793363662").value("oksm", "").value("delivery", "")
            .value("isVisibleToStateCustomers", "false").value("isAvailableToIndividuals", "false").value("available", "false").value("vat", "VAT_5")
        val report = validate(p)
        assertTrue(report.errors.any { "Страна происхождения" in it })
        assertFalse(report.errors.any { "Курьерская доставка" in it })
        assertTrue(report.warnings.any { "обеим группам" in it })
        assertTrue(report.warnings.any { "ppCategory" in it && "не проверены" in it })
        assertTrue(report.warnings.any { "доступность автоматически не проверены" in it })
        assertFalse(report.errors.any { "VAT_5" in it || "available" in it })
        assertFailsWith<IllegalArgumentException> { buildYml(p) }
        assertTrue(buildYml(p, allowInvalid = true).contains("<vat>VAT_5</vat>"))
        val fixed = p.value("oksm", "643").value("delivery", "false")
        assertTrue(validate(fixed).errors.isEmpty())
        assertTrue(buildYml(fixed).contains("<delivery>false</delivery>"))
        assertTrue(validate(fixed.value("isAvailableToIndividuals", "true")).warnings.none { "обеим группам" in it })
    }
    @Test fun shopUrlWhitespaceIsNormalizedForValidationAndExport() {
        val p = validProject().let { it.copy(settings = it.settings.copy(url = " https://example.ru\n", imageBase = " https://example.ru/images\n")) }
        assertTrue(validate(p).errors.isEmpty())
        assertTrue(buildYml(p).contains("<url>https://example.ru</url>"))
        assertTrue(buildYml(p.copy(settings = p.settings.copy(url = " \n"))).contains("<currencies>"))
        assertFalse(buildYml(p.copy(settings = p.settings.copy(url = " \n"))).contains("<url>"))
        assertTrue(validate(p.copy(settings = p.settings.copy(url = "https://exa\nmple.ru"))).errors.any { "адрес сайта" in it })
    }
    @Test fun publicAvailabilityOfLocalPhotosIsNotAssumed() {
        val p = validProject().let { it.copy(products = it.products.map { product -> product.copy(pictures = listOf(Picture(file = "a".repeat(32) + ".png", width = 800, height = 800))) }) }
        assertTrue(validate(p).warnings.any { "наличие изображения по публичной ссылке не проверено" in it })
    }

    @Test fun invalidCatalogCanExportOnlyWithExplicitOverride() {
        val p = defaultProject().let { it.copy(products = listOf(newProduct("draft", it.templates.single()))) }
        assertFailsWith<IllegalArgumentException> { buildYml(p) }
        assertTrue(buildYml(p, allowInvalid = true).contains("<offer id=\"\">"))
        val missingFields = p.copy(templates = p.templates.map { it.copy(fields = emptyList()) })
        assertTrue(buildYml(missingFields, allowInvalid = true).contains("<name></name>"))
        val badPrice = validProject().value("price", "wrong & <value>")
        assertTrue(buildYml(badPrice, allowInvalid = true).contains("<price>wrong &amp; &lt;value&gt;</price>"))
        assertTrue(buildYml(defaultProject(), allowInvalid = true).contains("<offers>"))
    }

    @Test fun minimalFormExportsWithOnlyRequiredFields() {
        val template = minimalTemplate()
        val fields = template.cardFields(Settings())
        assertEquals(12, fields.size)
        assertEquals(12, fields.count { it.target != "param" })
        assertEquals(0, fields.count { it.target == "param" })
        assertTrue(fields.filter { it.target != "param" }.all { it.required })
        assertFalse(fields.any { it.target in listOf("description", "vat") })
        val p = validProject().let { it.copy(templates = listOf(template), products = it.products.map { product -> product.copy(templateId = template.id) }) }
        assertTrue(validate(p).errors.isEmpty())
        assertTrue(validate(p.copy(templates = listOf(template.copy(fields = template.fields.map { if (it.target == "vat") it.copy(required = true) else it })))).errors.isEmpty())
        assertTrue(buildYml(p).contains("<currencyId>RUB</currencyId>"))
        val vatSettings = p.settings.copy(useVat = true)
        assertEquals(13, template.cardFields(vatSettings).size)
        assertTrue(template.cardFields(vatSettings).single { it.target == "vat" }.required)
        assertTrue(validate(p.copy(settings = vatSettings)).errors.any { "НДС" in it })
        assertTrue(buildYml(p.copy(settings = vatSettings).value("vat", "NO_VAT")).contains("<vat>NO_VAT</vat>"))
    }
    @Test fun universalMigrationConsolidatesFormsPreservesProductsAndAllowsLaterEdits() {
        val base = validProject()
        val oldBasic = base.templates.single()
        val oldMinimal = oldBasic.copy(id = "universal-minimal-v1", name = "Универсальная форма — минимум")
        val old = base.copy(templates = listOf(oldBasic, oldMinimal),
            products = base.products + base.products.single().copy(id = "other", templateId = oldMinimal.id), universalFormUnified = false)
        val installed = old.withMinimalPreset()
        assertEquals(1, installed.templates.size)
        assertEquals("Универсальная форма", installed.templates.single().name)
        assertTrue(installed.templates.single().fields.none { it.target == "param" })
        assertEquals(old.products.map { it.values }, installed.products.map { it.values })
        assertTrue(installed.products.all { it.templateId == "basic" })
        assertEquals(installed, installed.withMinimalPreset())
        val customized = installed.copy(templates = installed.templates.map { it.copy(fields = it.fields + Field("color", "param", "Цвет")) })
        assertEquals(customized, customized.withMinimalPreset())
        val deleted = installed.copy(templates = emptyList(), products = emptyList())
        assertEquals(deleted, deleted.withMinimalPreset())
        assertEquals(installed, projectJson.decodeFromString<Project>(projectJson.encodeToString(Project.serializer(), installed)))
    }
    @Test fun universalMigrationPreservesCustomMappingsPortalIdsAndOtherForms() {
        val source = defaultTemplate().let { it.copy(id = "universal-minimal-v1", fields = it.fields.map { f -> if (f.target == "name") f.copy(id = "customName") else f }) }
        val custom = source.copy(id = "custom", name = "Моя форма")
        val old = defaultProject().copy(settings = Settings(usePortalCategories = true), templates = listOf(source, custom),
            products = listOf(Product("p", source.id, mapOf("customName" to "Футболка", "_portal_category" to "793363662", "param2" to "50"))), universalFormUnified = false)
        val result = old.withMinimalPreset()
        assertEquals(custom, result.templates.last())
        val values = result.products.single().valuesFor(result.templates.first(), result.settings)
        assertEquals("Футболка", values["name"])
        assertEquals("793363662", values["ppCategory"])
        assertEquals("50", result.products.single().values["param2"])
    }

    @Test fun supplierCategoryUsesFreeIdAndRejectsBlankNames() {
        assertEquals(Category("2", "Мебель"), newSupplierCategory(listOf(Category("1", "A"), Category("3", "B")), " Мебель "))
        assertFailsWith<IllegalArgumentException> { newSupplierCategory(emptyList(), "  ") }
    }

    @Test fun formCanRequireOptionalStandardField() {
        val p = validProject()
        val template = p.templates.single().let { t -> t.copy(fields = t.fields + Field("customCode", "vendorCode", "Обязательный код", required = true)) }
        val project = p.copy(templates = listOf(template))
        assertTrue(validate(project).errors.any { "Обязательный код" in it })
        assertTrue(validate(project.copy(products = project.products.map { it.copy(values = it.values + ("customCode" to "SKU1")) })).errors.isEmpty())
    }
    @Test fun validCatalog() { assertEquals(emptyList(), validate(validProject()).errors); assertTrue(buildYml(validProject()).contains("<price>1250.50</price>")) }
    @Test fun emptyCannotExport() { assertFailsWith<IllegalArgumentException> { buildYml(defaultProject()) } }
    @Test fun duplicateOfferId() {
        val p = validProject(); assertTrue(validate(p.copy(products = p.products + p.products[0].copy(id = "other"))).errors.any { "артикул повторяется" in it })
    }
    @Test fun invalidPrices() { listOf("NaN", "Infinity", "-1", "0", "abc").forEach { assertTrue(validate(validProject().value("price", it)).errors.isNotEmpty(), it) } }
    @Test fun invalidDates() { listOf("2026-02-30T10:00", "2026-09-08T24:00", "2026-13-01T00:00").forEach { assertTrue(validate(validProject().value("endDate", it)).errors.isNotEmpty(), it) } }
    @Test fun equalDatesWithDifferentPrecisionRejected() { assertTrue(validate(validProject().value("endDate", "2026-09-07T12:00:00")).errors.any { "позже" in it }) }
    @Test fun requiredCharacteristics() { assertTrue(validate(validProject().value("param0", "")).warnings.any { "4 характеристики" in it }) }
    @Test fun unusedLegacyCategoriesCannotBlockPortalExport() {
        val p = validProject().copy(categories = listOf(Category("bad", "Unused", "missing")))
        assertTrue(validate(p).errors.isEmpty())
        assertFalse(buildYml(p).contains("Unused"))
        assertTrue(buildYml(p).contains("<category id=\"1\">Товары</category>"))
    }
    @Test fun imageUrls() { listOf("data:image/png;base64,a", "file:///a.png", "/a.png", "https://localhost/a.png", "http://127.0.0.1/a.png", "http://[::1]:8000/a.png", "https://a.ru/" + "a".repeat(510)).forEach { url ->
        val p = validProject(); assertTrue(validate(p.copy(products = p.products.map { it.copy(pictures = listOf(Picture(url))) })).errors.isNotEmpty(), url)
    } }
    @Test fun imageCount() { listOf(0, 11).forEach { count -> val p = validProject(); assertTrue(validate(p.copy(products = p.products.map { it.copy(pictures = List(count) { Picture("https://example.ru/a.png") }) })).errors.isNotEmpty()) } }
    @Test fun localImageMappingAndOverride() {
        val settings = validProject().settings
        val pic = Picture(file = "a".repeat(32) + ".png", width = 600, height = 600)
        assertEquals("https://example.ru/images/" + pic.file, imageUrl(pic, settings))
        assertEquals("https://other.ru/a.png", imageUrl(pic.copy(url = "https://other.ru/a.png"), settings))
    }
    @Test fun vatToggle() {
        val p = validProject().let { it.copy(settings = it.settings.copy(useVat = true)) }
        assertTrue(validate(p).errors.any { "НДС" in it }); assertTrue(buildYml(p.value("vat", "NO_VAT")).contains("<vat>NO_VAT</vat>"))
        assertFalse(buildYml(validProject().value("vat", "NO_VAT")).contains("<vat>"))
    }
    @Test fun xmlCharacters() { assertTrue(validate(validProject().value("name", "abc\u0001")).errors.isNotEmpty()); assertTrue(validate(validProject().value("name", "Товар 😀")).errors.isEmpty()) }
    @Test fun badMappingAndPathTraversal() {
        val p = validProject(); val t = p.templates[0]
        assertFailsWith<IllegalArgumentException> { checkShape(p.copy(templates = listOf(t.copy(fields = t.fields.mapIndexed { i, f -> if (i == 0) f.copy(target = "evil><x") else f })))) }
        assertFailsWith<IllegalArgumentException> { checkShape(p.copy(templates = listOf(t.copy(fields = t.fields + t.fields[0].copy(id = "other"))))) }
        assertFailsWith<IllegalArgumentException> { checkShape(p.copy(products = p.products.map { it.copy(pictures = listOf(Picture(file = "../../project.json"))) })) }
    }
    @Test fun defaultsApplyOnlyToNewProducts() {
        val t = defaultTemplate().let { it.copy(fields = it.fields.map { f -> if (f.target == "deliveryDays") f.copy(default = "1-30") else f }) }; assertEquals("1-30", newProduct("new", t).values["deliveryDays"])
        assertEquals("", Product("old", t.id).valuesFor(t)["deliveryDays"])
    }
    @Test fun optionalRulesAndPortalCategory() {
        assertTrue(validate(validProject().value("_portal_category", "123")).errors.isEmpty())
        val base = validProject().let { p -> p.copy(templates = p.templates.map { it.copy(fields = it.fields + listOf("max-quantity", "barcode", "dimensions").map { key -> Field(key, key, key) }) }) }
        assertTrue(validate(base.value("min-quantity", "10").value("max-quantity", "2")).errors.any { "меньше" in it })
        assertTrue(validate(base.value("barcode", "123")).errors.any { "штрихкод" in it })
        assertTrue(validate(base.value("dimensions", "10/0/20")).errors.any { "габариты" in it })
    }
}
