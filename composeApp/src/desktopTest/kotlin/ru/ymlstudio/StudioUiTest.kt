package ru.ymlstudio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class StudioUiTest {
    @Test fun oneCopyInputUpdatesSeveralFieldsAndRejectsUnfinishedSyntax() {
        ProjectRepository(Files.createTempDirectory("yml-pattern-copy-ui")).use { repo ->
            val template = defaultTemplate().let { t -> t.copy(fields = t.fields.map {
                if (it.target == "name") it.copy(copyVariable = "p1") else it
            } + Field("color", "param", "Цвет", copyVariable = "p2") + Field("size", "param", "Размер", copyVariable = "p3"),
                copyPattern = "p1{Футболка p2, p3}") }
            val source = newProduct("source", template).copy(values = mapOf("id" to "12", "name" to "Футболка", "color" to "синий", "size" to "54/194"))
            val initial = defaultProject().copy(templates = listOf(template), products = listOf(source))
            repo.save(initial)
            compose.setContent { MaterialTheme { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } } }
            compose.productAction("source", "Копировать")
            compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("copy-fields"))).assertCountEquals(1)
            compose.onNodeWithText("Быстрое заполнение").performTextReplacement("p1{Футболка p2, p3}")
            compose.onNodeWithText("Укажите значение в скобках: p2{…}").assertExists()
            compose.onNodeWithText("Сохранить").assertIsNotEnabled()
            compose.onNodeWithText("Быстрое заполнение").performTextReplacement("p1{Футболка p2{красный}")
            compose.onNodeWithText("Сохранить").assertIsNotEnabled()
            compose.onNodeWithText("Быстрое заполнение").performTextReplacement("p1{Футболка p2{красный}, p3{50/182}}")
            compose.onNodeWithTag("copy-preview-name").assertTextContains("Название товара: Футболка красный, 50/182")
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(listOf(source), repo.load().products)
            compose.productAction("source", "Копировать")
            compose.onNodeWithText("Быстрое заполнение").assertTextContains("p1{Футболка p2{синий}, p3{54/194}}")
            compose.onNodeWithText("Быстрое заполнение").performTextReplacement("p1{Футболка p2{красный}, p3{50/182}}")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Копировать товар").fetchSemanticsNodes().isEmpty() }
            val restored = repo.load()
            assertEquals(source, restored.products.first())
            assertEquals("Футболка красный, 50/182", restored.products.last().values["name"])
            assertEquals("красный", restored.products.last().values["color"])
            assertEquals("50/182", restored.products.last().values["size"])
        }
    }

    @Test fun quickAccessFieldsCanBeEditedInCopyDialogAndCancelDiscardsThem() {
        ProjectRepository(Files.createTempDirectory("yml-quick-copy-ui")).use { repo ->
            val template = defaultTemplate().copy(fields = defaultTemplate().fields.map {
                it.copy(quickAccess = it.target == "price")
            } + Field("color", "param", "Цвет", quickAccess = true))
            val source = newProduct("source", template).copy(values = mapOf("id" to "12", "name" to "Майка", "price" to "100", "color" to "Красный"))
            val initial = defaultProject().copy(templates = listOf(template), products = listOf(source))
            repo.save(initial)
            compose.setContent { MaterialTheme { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } } }
            compose.onNodeWithText("Загрузить товары из YML").assertExists()
            compose.productAction("source", "Копировать")
            compose.onNodeWithText("Цена", substring = false).performTextReplacement("999")
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(1, repo.load().products.size)
            compose.productAction("source", "Копировать")
            compose.onNodeWithText("Цена", substring = false).assertTextContains("100")
            compose.onNodeWithText("Новое название товара").performTextReplacement("Майка XL")
            compose.onNodeWithText("Цена", substring = false).performTextReplacement("200")
            compose.onNodeWithText("Цвет", substring = false).performTextReplacement("Белый")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Копировать товар").fetchSemanticsNodes().isEmpty() }
            val restored = repo.load()
            assertEquals(source, restored.products.first())
            assertEquals("200", restored.products.last().values["price"])
            assertEquals("Белый", restored.products.last().values["color"])
            compose.onNodeWithText("Конструктор форм", substring = false).performClick()
            compose.onNodeWithText("Загрузить форму из YML").assertExists()
        }
    }

    @Test fun importFormDialogSelectsFilledOfferAndAcceptsNewName() {
        val preview = parseYmlImport("<offers><offer id=\"1\"><name>Первая</name></offer><offer id=\"2\"><name>Вторая</name><param name=\"Размер\">XL</param></offer></offers>".toByteArray(), "Файл")
        var selected: Pair<Int, String>? = null
        compose.setContent { MaterialTheme { YmlImportDialog(preview, true, false, {}) { index, name -> selected = index to name } } }
        compose.onNodeWithText("Название новой формы").performTextReplacement("Моя форма")
        compose.onNodeWithText("Форма из файла").performClick()
        compose.onNodeWithText("Форма 2 · 1 товаров · Вторая").performClick()
        compose.onNodeWithText("Добавить форму").performClick()
        compose.runOnIdle { assertEquals(1 to "Моя форма", selected) }
    }

    @Test fun importSingleStructureDoesNotShowDuplicateProducts() {
        val preview = parseYmlImport("<offers><offer id=\"1\"><name>Первая</name></offer><offer id=\"2\"><name>Вторая</name></offer></offers>".toByteArray(), "Файл")
        var selected: Int? = null
        compose.setContent { MaterialTheme { YmlImportDialog(preview, true, false, {}) { index, _ -> selected = index } } }
        compose.onNodeWithText("Форм: 1").assertExists()
        compose.onNodeWithText("Форма из файла").assertDoesNotExist()
        compose.onNodeWithText("Вторая", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Добавить форму").performClick()
        compose.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun vatAllowsOnlyOfficialSelectionEvenForLegacyTextConfiguration() {
        var selected by mutableStateOf("5")
        val field = Field("vat", "vat", "Ставка НДС", inputMode = "text", dictionary = "custom",
            options = listOf(FieldOption("INVALID")))
        compose.setContent { MaterialTheme { FieldValueInput(field, selected) { selected = it } } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("5 % — VAT_5").performClick()
        compose.onNodeWithText("20 % — VAT_20").performClick()
        compose.runOnIdle { assertEquals("VAT_20", selected) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun readyVatIncludedFieldIsBooleanAndNotOfferedTwice() {
        var fields by mutableStateOf(emptyList<Field>())
        compose.setContent { MaterialTheme { AddFieldButtons(fields, { "included" }) { fields = fields + it } } }
        compose.onNodeWithText("+ Готовое поле").performClick()
        compose.onNodeWithText("Поиск по коду или названию").performTextInput(VAT_INCLUDED_LABEL)
        compose.onNode(hasText(VAT_INCLUDED_LABEL) and !hasSetTextAction()).performClick()
        compose.runOnIdle {
            assertEquals(vatIncludedParameter("included"), fields.single())
            assertEquals(listOf("true", "false"), fields.single().choices().map { it.value })
        }
        compose.onNodeWithText("+ Готовое поле").performClick()
        compose.onNodeWithText("Поиск по коду или названию").performTextInput(VAT_INCLUDED_LABEL)
        compose.onNodeWithText("Совпадений нет").assertExists()
    }

    @Test fun copiedProductKeepsValuesAfterDialogSaveAndReopen() {
        val dir = Files.createTempDirectory("yml-copy-ui")
        ProjectRepository(dir).use { repo ->
            val template = defaultTemplate()
            val source = newProduct("source", template).copy(values = mapOf(
                "id" to "438", "name" to "Исходная футболка", "price" to "667.50",
                "vat" to "VAT_5", "oksm" to "643", "_portal_category" to "793363662"),
                pictures = listOf(Picture(url = "https://example.com/shirt.jpg")))
            val initial = defaultProject().copy(settings = Settings(useVat = true), products = listOf(source))
            repo.save(initial)
            compose.setContent { MaterialTheme { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } } }
            compose.productAction("source", "Копировать")
            compose.onNodeWithText("Новое название товара").assertTextContains("Исходная футболка")
            compose.onNodeWithText("Сохранить").assertIsEnabled()
            compose.onNodeWithText("Новое название товара").performTextReplacement("")
            compose.onNodeWithText("Сохранить").assertIsNotEnabled()
            compose.onNodeWithText("Новое название товара").performTextInput("Копия футболки")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Копировать товар").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Карточка товара").assertDoesNotExist()
            val copy = repo.load().products.last()
            assertEquals(source.values - setOf("id", "name"), copy.values - setOf("id", "name"))
            assertEquals(source.pictures, copy.pictures)
            compose.productAction(copy.id, "Изменить")
            compose.onNodeWithText("Название товара *").assertTextContains("Копия футболки")
            compose.onNodeWithText("Цена *").assertTextContains("667.50")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Фотографии (1/10)"))
            compose.onNodeWithText("Фотографии (1/10)").assertExists()
        }
    }

    @Test fun ownListShortcutAndSourceSelectionExposeOptionsImmediately() {
        var field by mutableStateOf<Field?>(null)
        compose.setContent { MaterialTheme {
            if (field == null) AddFieldButtons(emptyList(), { "own" }) { field = it }
            else FieldChoiceConfiguration(field!!) { field = it }
        } }
        compose.onNodeWithText("+ Поле со своим списком").performClick()
        compose.onNodeWithText("+ Добавить вариант").performClick()
        compose.onNodeWithText("Вариант 1").performTextInput("Синий")
        compose.runOnIdle {
            assertEquals("both", field!!.effectiveInputMode())
            assertEquals("Синий", field!!.options.single().value)
            field = Field("plain", "param", "Материал")
        }
        compose.onNodeWithText("По назначению поля").performClick()
        compose.onNodeWithText("Мой список").performClick()
        compose.onNodeWithText("+ Добавить вариант").assertExists()
        compose.runOnIdle { assertEquals("both", field!!.effectiveInputMode()) }
    }

    @Test fun offerDateCalendarAndClockConfirmCancelAndReopen() {
        var value by mutableStateOf("2028-02-29T23:59:59")
        compose.setContent { MaterialTheme {
            FieldValueInput(Field("start", "beginDate", "Начало предложения"), value) { value = it }
        } }
        compose.onNodeWithText("29.02.2028 23:59").performClick()
        compose.onNodeWithText("Далее: время").performClick()
        compose.onNodeWithText("Ввести цифрами").performClick()
        compose.onNodeWithText("Выбрать на часах").assertExists()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("08")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("05")
        compose.onNodeWithText("Выбрать на часах").performClick()
        val screenshots = Path.of("build/reports/ui").also { Files.createDirectories(it) }
        javax.imageio.ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", screenshots.resolve("offer-clock.png").toFile())
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle { assertEquals("2028-02-29T08:05:59", value) }
        compose.onNodeWithText("29.02.2028 08:05").performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals("2028-02-29T08:05:59", value) }
        compose.onNodeWithText("Очистить").performClick()
        compose.onNodeWithText("Выбрать дату и время").assertExists()
    }

    @Test fun readyFieldsCarryDictionaryAndCustomOptionsCanBeEdited() {
        var added by mutableStateOf<Field?>(null)
        compose.setContent { MaterialTheme {
            if (added == null) AddFieldButtons(emptyList(), { "created" }) { added = it }
            else FieldChoiceConfiguration(added!!) { added = it }
        } }
        compose.onNodeWithText("+ Готовое поле").performClick()
        compose.onNodeWithText("Поиск по коду или названию").performTextInput("Страна")
        compose.onNodeWithText("Страна происхождения (код ОКСМ)").performClick()
        compose.runOnIdle { assertEquals(251, added!!.choices().size); added = Field("custom", "param", "Мой размер", inputMode = "select", dictionary = "custom") }
        compose.onNodeWithText("+ Добавить вариант").performClick()
        compose.onNodeWithText("Вариант 1").performTextInput("Синий")
        compose.onNodeWithText("+ Добавить вариант").performClick()
        compose.onNodeWithText("Вариант 2").performTextInput("Красный")
        compose.runOnIdle { assertEquals(listOf(FieldOption("Синий"), FieldOption("Красный")), added!!.options) }
    }

    @Test fun categorySearchSelectsRealIdAndManualCountryCodeShowsName() {
        var value by mutableStateOf("")
        var field by mutableStateOf(Field("category", "ppCategory", "Категория"))
        compose.setContent { MaterialTheme { FieldValueInput(field, value) { value = it } } }
        compose.onNodeWithText("Выбрать из списка").performClick()
        compose.onNodeWithText("Поиск по коду или названию").performTextInput("баскетбола стритбола")
        compose.onNodeWithText("793363662 — Спортивная экипировка для баскетбола, стритбола").performClick()
        compose.runOnIdle { assertEquals("793363662", value); field = Field("country", "oksm", "Страна"); value = "" }
        compose.onNodeWithText("Страна").performTextInput("643")
        compose.onNodeWithText("643 — РОССИЯ").assertExists()
        val screenshots = Path.of("build/reports/ui").also { Files.createDirectories(it) }
        javax.imageio.ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", screenshots.resolve("dictionary-input.png").toFile())
    }
    @Test fun customSelectOnlyAndBothModesWorkInSharedInput() {
        var value by mutableStateOf("")
        var field by mutableStateOf(Field("size", "param", "Размер", inputMode = "select", dictionary = "custom", options = listOf(FieldOption("S", "Маленький"), FieldOption("L", "Большой"))))
        compose.setContent { MaterialTheme { FieldValueInput(field, value) { value = it } } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Выбрать из списка").performClick()
        compose.onNodeWithText("Поиск по коду или названию").performTextInput("Большой")
        compose.onNodeWithText("L — Большой").assertDoesNotExist()
        compose.onNode(hasText("Большой") and !hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals("Большой", value); field = field.copy(inputMode = "both") }
        compose.onNodeWithText("Размер").performTextReplacement("XL")
        compose.runOnIdle { assertEquals("XL", value) }
    }

    @Test fun vatSelectorShowsInvalidStoredValueAndAllowsExplicitReplacement() {
        var selected = "24"
        compose.setContent { MaterialTheme {
            VatSelector("24", "НДС") { selected = it }
        } }
        compose.onNodeWithText("Недопустимое значение: 24").performClick()
        compose.onNodeWithText("5 % — VAT_5").performClick()
        compose.runOnIdle { assertEquals("VAT_5", selected) }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun invalidExportNeedsExplicitConfirmation() {
        var exported: Pair<Boolean, Boolean>? = null
        compose.setContent { MaterialTheme { Box(Modifier.size(1280.dp, 860.dp)) {
            ExportScreen(defaultProject(), false) { bundle, allowInvalid -> exported = bundle to allowInvalid }
        } } }
        compose.onNodeWithText("Сохранить YML").performClick()
        compose.runOnIdle { assertEquals(null, exported) }
        compose.onNodeWithText("Сохранить всё равно").performClick()
        compose.runOnIdle { assertEquals(false to true, exported) }
        compose.onNodeWithText("YML + фотографии ZIP").performClick()
        compose.onNodeWithText("Сохранить всё равно").performClick()
        compose.runOnIdle { assertEquals(true to true, exported) }
    }

    @Test fun createDraftSaveAndProtectUnsavedChanges() {
        val dir = Files.createTempDirectory("yml-ui-test")
        ProjectRepository(dir).use { repo ->
            try {
                val initial = defaultProject().withMinimalPreset(); repo.save(initial)
                compose.setContent { MaterialTheme { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } } }
                val screenshots = Path.of("build/reports/ui").also { Files.createDirectories(it) }
                javax.imageio.ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", screenshots.resolve("products.png").toFile())
                compose.onNodeWithText("+ Добавить товар").performClick()
                compose.onNodeWithText("Универсальная форма").performClick()
                compose.onNodeWithText("Название товара *").performTextInput("Пробный товар")
                compose.onNodeWithText("+ Создать категорию").assertDoesNotExist()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("ID категории портала *"))
                compose.onNodeWithText("ID категории портала *").performTextInput("793363662")
                for ((label, value) in listOf("Регионы поставки *" to "Москва; Московская область", "Стоимость доставки *" to "0", "Срок доставки, рабочие дни *" to "1-30")) {
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
                    compose.onNodeWithText(label).performTextInput(value)
                }
                javax.imageio.ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", screenshots.resolve("product-editor.png").toFile())
                compose.onNodeWithText("Конструктор форм", useUnmergedTree = true).performClick()
                compose.onNodeWithText("Отменить несохранённые изменения и перейти в другой раздел?").assertExists()
                compose.onAllNodesWithText("Отмена").onLast().performClick()
                compose.onNodeWithText("Сохранить", substring = false).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Изменения сохранены").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("ОК").performClick()
                compose.onNodeWithText("Пробный товар").assertExists()
                assertEquals("Пробный товар", repo.load().products.single().values["name"])
                assertEquals(emptyList<Category>(), repo.load().categories)
                assertEquals("793363662", repo.load().products.single().values["_portal_category"])
                assertEquals("Москва; Московская область", repo.load().products.single().values["regions"])
                assertEquals("0", repo.load().products.single().values["deliveryCost"])
                assertEquals("1-30", repo.load().products.single().values["deliveryDays"])
                compose.onNodeWithText("Экспорт YML", useUnmergedTree = true).performClick()
                compose.onNodeWithText("Сохранить YML").assertIsEnabled().performClick()
                compose.onNodeWithText("Сохранить с ошибками?").assertExists()
                compose.onNodeWithText("Отмена").performClick()
                compose.onNodeWithText("Сохранить с ошибками?").assertDoesNotExist()
            } finally { /* Composition is disposed by the JUnit rule. */ }
        }
        dir.toFile().deleteRecursively()
    }
}
