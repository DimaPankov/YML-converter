package ru.ymlstudio

import kotlinx.serialization.encodeToString
import kotlin.test.*

class CopyPatternsTest {
    @Test fun customNamesWithSymbolsExpandAndAssignByExactBinding() {
        val names = listOf("название", "цвет", "size", "color_1", "size-2", "$", "@", "42", "[x]", "★")
        val template = Template("t", "Форма", fields = names.mapIndexed { i, name ->
            Field("f$i", "param", "Поле $i", copyVariable = name)
        }, copyPattern = "название{Футболка, " + names.drop(1).joinToString(", ") + "}")
        assertNull(template.copyPatternError())
        val source = Product("source", "t", names.indices.associate { "f$it" to "значение$it" })
        val input = template.copyText(source)
        val values = template.copyValues(input)
        names.indices.drop(1).forEach { assertEquals("значение$it", values["f$it"]) }
        assertTrue(values.getValue("f0").startsWith("Футболка, значение1"))
        assertFailsWith<IllegalArgumentException> { template.copyValues(template.copyPattern) }
    }

    @Test fun overlappingCustomNamesDoNotMatchPartsOfWords() {
        val template = Template("t", "Форма", fields = listOf(
            Field("a", "param", "A", copyVariable = "color"),
            Field("b", "param", "B", copyVariable = "color-1")
        ), copyPattern = "watercolor: color / color-1")
        val input = template.copyText(Product("p", "t", mapOf("a" to "синий", "b" to "красный")))
        assertEquals("watercolor: color{синий} / color-1{красный}", input)
        assertEquals(mapOf("a" to "синий", "b" to "красный"), template.copyValues(input))
    }

    @Test fun spacesAndReservedSyntaxCannotBeUsedInArgumentNames() {
        for (name in listOf("collor collor", "x\ty", "x{y", "x}y", "x\\y")) {
            val template = form().copy(fields = form().fields.map { if (it.copyVariable == "p2") it.copy(copyVariable = name) else it })
            assertNotNull(template.copyPatternError(), name)
        }
    }

    @Test fun bareTemplateReferencesExpandIntoRequiredBracesForEditing() {
        val template = form().copy(
            fields = form().fields + Field("height", "param", "Рост", copyVariable = "p4"),
            copyPattern = "p1{Футболка RLS, p2, p3/p4}"
        )
        assertNull(template.copyPatternError())
        val text = template.copyText(Product("source", template.id, mapOf("color" to "синий", "size" to "54", "height" to "194")))
        assertEquals("p1{Футболка RLS, p2{синий}, p3{54}/p4{194}}", text)
        val values = template.copyValues(text.replace("синий", "красный"))
        assertEquals("Футболка RLS, красный, 54/194", values["title"])
        assertEquals("красный", values["color"])
        assertEquals("54", values["size"])
        assertEquals("194", values["height"])
        assertFailsWith<IllegalArgumentException> { template.copyValues(template.copyPattern) }
        assertFailsWith<IllegalArgumentException> { template.copyValues(text + " p2") }
        assertTrue("p4{}" in template.copyText(Product("empty", template.id)))
    }

    @Test fun bareTemplateSupportsTenArgumentsAndChecksBindings() {
        val template = Template("t", "Форма", fields = (1..10).map { Field("f$it", "param", "Поле $it", copyVariable = "p$it") },
            copyPattern = (1..10).joinToString(" / ") { "p$it" })
        assertNull(template.copyPatternError())
        val text = template.copyText(Product("p", "t", (1..10).associate { "f$it" to "значение $it" }))
        assertEquals(10, template.copyValues(text).size)
        assertEquals("Привяжите p11 к полю формы", template.copy(copyPattern = "p11").copyPatternError())
    }

    @Test fun variableLikeLiteralValuesRoundTripAndWordsAreNotReferences() {
        val template = form().copy(copyPattern = "p1{Футболка shop2 RLS, p2, p3}")
        val source = Product("p", "t", mapOf("color" to "модель p7", "size" to "p8{XL}"))
        assertNull(template.copyPatternError())
        val values = template.copyValues(template.copyText(source))
        assertEquals("модель p7", values["color"])
        assertEquals("p8{XL}", values["size"])
        assertTrue("shop2" in values.getValue("title"))
    }

    private fun form() = Template("t", "Одежда", fields = listOf(
        Field("sku", "id", "Артикул"),
        Field("title", "name", "Название", copyVariable = "p1"),
        Field("color", "param", "Цвет", copyVariable = "p2"),
        Field("size", "param", "Размер/рост", copyVariable = "p3"),
        Field("ste", "ste", "СТЕ"), Field("other", "param", "Материал")
    ), copyPattern = "p1{Футболка для баскетбола, стритбола, RLS, p2{синий}, p3{54/194}}")

    @Test fun oneInputAssignsNestedFieldsAndNameWithoutChangingSource() {
        val template = form()
        val source = Product("original", "t", mapOf("sku" to "A1", "title" to "Старая футболка", "color" to "синий", "size" to "54/194", "ste" to "38910216", "other" to "Хлопок"))
        val project = defaultProject().copy(templates = listOf(template), products = listOf(source))
        val draft = project.duplicateProduct(source, "copy")
        val input = template.copyText(draft).replace("p2{синий}", "p2{красный}").replace("p3{54/194}", "p3{50/182}")
        val result = project.finishQuickProductCopy(draft, input)
        assertEquals("Футболка для баскетбола, стритбола, RLS, красный, 50/182", result.values["title"])
        assertEquals("красный", result.values["color"])
        assertEquals("50/182", result.values["size"])
        assertEquals("Хлопок", result.values["other"])
        assertEquals("", result.values["ste"])
        assertTrue(isValidArticle(result.values.getValue("sku")))
        assertNotEquals(source.values["sku"], result.values["sku"])
        assertEquals(source, project.products.single())
    }

    @Test fun twoOrTenIndependentArgumentsUseArbitraryFieldIds() {
        for (count in listOf(2, 10)) {
            val template = Template("t", "Форма", fields = (1..count).map { Field("uuid-$it", "param", "Поле $it", copyVariable = "p$it") },
                copyPattern = (1..count).joinToString("\n") { "p$it{значение $it}" })
            assertNull(template.copyPatternError())
            assertEquals((1..count).associate { "uuid-$it" to "значение $it" }, template.copyValues(template.copyPattern))
        }
    }

    @Test fun multipleNestingLevelsPopulateEveryBoundField() {
        val template = form().copy(copyPattern = "p1{Футболка p3{p2{54}/194}}")
        assertEquals(mapOf("title" to "Футболка 54/194", "size" to "54/194", "color" to "54"), template.copyValues(template.copyPattern))
    }

    @Test fun brokenInputCannotSilentlyKeepStaleValues() {
        val template = form()
        for (text in listOf("p1{Название p2{Красный}", "p1{Название}}", "p1{Название p2{Красный}}", "p1{p2{Красный} p3{XL} p4{Нет}}", "p1{p2{Красный} p2{Синий} p3{XL}}")) {
            assertFailsWith<IllegalArgumentException>(text) { template.copyValues(text) }
        }
        assertNotNull(template.copy(fields = template.fields.map { it.copy(copyVariable = "p1") }).copyPatternError())
        assertNotNull(template.copy(copyPattern = "Обычный текст").copyPatternError())
    }

    @Test fun leafValuesArePrefilledAndBracesRemainLiteral() {
        val template = form()
        val source = Product("p", "t", mapOf("color" to "красный {R} \\ синий", "size" to "42/146"))
        val values = template.copyValues(template.copyText(source))
        assertEquals(source.values["color"], values["color"])
        assertEquals("42/146", values["size"])
        assertTrue("42/146" in values.getValue("title"))
    }

    @Test fun settingsSurviveSerializationAndEquivalentFormUpdates() {
        val template = form()
        assertEquals(template, projectJson.decodeFromString<Template>(projectJson.encodeToString(template)))
        val legacy = projectJson.decodeFromString<Template>("""{"id":"t","name":"Форма","fields":[]}""")
        assertEquals("", legacy.copyPattern)
        val original = template.copy(copyPattern = "", fields = template.fields.map { it.copy(copyVariable = "") })
        val equivalent = original.copy(id = "other", fields = original.fields.map { it.copy(id = "other-${it.id}") })
        val project = defaultProject().copy(templates = listOf(original, equivalent))
        val updated = project.updateTemplate(template)
        assertEquals(template.copyPattern, updated.templates.last().copyPattern)
        assertEquals(template.fields, updated.templates.last().fields)
    }

    @Test fun exportedValuesContainNoTemplateMarkers() {
        val initial = validProject()
        val template = initial.templates.single().let { t -> t.copy(fields = t.fields.map {
            when (it.id) { "name" -> it.copy(copyVariable = "p1"); "param0" -> it.copy(copyVariable = "p2"); else -> it }
        }, copyPattern = "p1{Стол p2{Белый}}") }
        val project = initial.copy(templates = listOf(template))
        val copied = project.finishQuickProductCopy(project.duplicateProduct(project.products.single(), "copy"), "p1{Стол p2{Красный}}")
        val xml = buildYml(project.copy(products = listOf(copied)))
        assertTrue("<name>Стол Красный</name>" in xml)
        assertTrue("<param name=\"Цвет\">Красный</param>" in xml)
        assertFalse("p1{" in xml)
    }
}
