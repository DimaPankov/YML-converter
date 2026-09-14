package ru.ymlstudio

import kotlin.test.*

class DictionaryTest {
    @Test fun fullSnapshotsAreAvailableAndCodesAreNotConfusedWithInternalIds() {
        assertEquals(251, Dictionaries.country.size)
        assertEquals(811, Dictionaries.okei.size)
        assertEquals(773, Dictionaries.unit.size)
        assertEquals(93, Dictionaries.region.size)
        assertEquals(14977, Dictionaries.category.size)
        assertEquals(23, Dictionaries.packaging.size)
        assertEquals(8, Dictionaries.vat.size)
        assertEquals(4, Dictionaries.currency.size)
        assertEquals("РОССИЯ", countryValue("643")?.name)
        assertEquals("КИТАЙ", countryValue("156")?.name)
        assertEquals("Штука", okeiValue("796")?.name)
        assertEquals("294", Dictionaries.okei.single { it.code == "796" && it.name == "Штука" }.id)
        assertTrue(Dictionaries.choices("okei").any { it.value == "796 | Штука" })
        assertTrue(Dictionaries.choices("unit").any { it.value == "шт" })
    }
    @Test fun searchUsesNamesCodesAndCategoryAncestry() {
        val choices = Dictionaries.choices("category")
        assertEquals("793363662", searchChoices(choices, "793363662").single().value)
        assertTrue(searchChoices(choices, "баскетбола стритбола").any { it.value == "793363662" })
        assertFalse(choices.single { it.value == "1" }.selectable)
        assertTrue(choices.single { it.value == "793363662" }.selectable)
        val region = Dictionaries.region.first { it.name == "Москва" }
        assertEquals(listOf("Москва"), regionNames("${region.id}; Москва"))
    }
    @Test fun ratesAndCurrenciesUseYmlValuesInsteadOfDictionaryIds() {
        val vat = Dictionaries.choices("vat")
        assertTrue(vat.any { it.value == "VAT_5" && "5%" in it.label })
        assertTrue(vat.all { it.value in vatAliases })
        assertTrue(Dictionaries.choices("currency").any { it.value == "RUB" })
        assertEquals("RUB", currencyValue("1"))
        assertEquals("USD", currencyValue("2"))
        assertFalse("GBP" in supportedCurrencies)
    }
    @Test fun customListModesSurviveSerializationAndEnforceOnlySelectMode() {
        val field = Field("size", "param", "Размер", inputMode = "select", dictionary = "custom",
            options = listOf(FieldOption("S", "Маленький"), FieldOption("L", "Большой")), default = "S")
        val base = validProject()
        val p = base.copy(templates = base.templates.map { it.copy(fields = it.fields + field) },
            products = base.products.map { it.copy(values = it.values + ("size" to "X")) })
        assertTrue(validate(p).errors.any { "выберите значение из списка" in it })
        val both = p.copy(templates = p.templates.map { it.copy(fields = it.fields.map { f -> if (f.id == "size") f.copy(inputMode = "both") else f }) })
        assertTrue(validate(both).errors.isEmpty())
        assertTrue(buildYml(both).contains("<param name=\"Размер\">X</param>"))
        assertEquals(both, projectJson.decodeFromString<Project>(projectJson.encodeToString(Project.serializer(), both)))
        assertEquals("S", newProduct("new", p.templates.single()).values["size"])
        assertEquals("L — Большой", field.valueDescription("L"))
        assertTrue(field.copy(dictionary = "country").choices().size == 251)
    }
    @Test fun knownManualCodesAreAcceptedInSelectOnlyFieldsAndRegionChoicesAppend() {
        val unit = Field("u", "okei", "Единица", inputMode = "select")
        assertTrue(unit.acceptsChoice("796"))
        assertTrue(unit.acceptsChoice("796 | Штука"))
        assertFalse(unit.acceptsChoice("99999"))
        val regions = Field("r", "regions", "Регионы")
        val selected = regions.choices().first { it.label.endsWith("— Москва") }
        assertEquals("Москва", regions.choiceValue(selected, "Москва"))
        assertEquals("Московская область; Москва", regions.choiceValue(selected, "Московская область"))
    }
    @Test fun currencyFieldControlsOfferAndCurrencyBlockWithoutInventedRates() {
        val p = validProject().let { it.copy(templates = it.templates.map { t -> t.copy(fields = t.fields + Field("currency", "currencyId", "Валюта")) },
            products = it.products.map { o -> o.copy(values = o.values + ("currency" to "USD")) }) }
        val xml = buildYml(p)
        assertTrue(xml.contains("<currency id=\"USD\"/>"))
        assertTrue(xml.contains("<currencyId>USD</currencyId>"))
        assertFalse(xml.contains("rate="))
        assertTrue(validate(p.copy(products = p.products.map { it.copy(values = it.values + ("currency" to "GBP")) })).errors.any { "Валюта" in it })
    }
}
