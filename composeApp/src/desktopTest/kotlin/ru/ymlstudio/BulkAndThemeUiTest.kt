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
import kotlin.test.*

class BulkAndThemeUiTest {
    @Test fun listOpensAtLastProductAndKeepsItVisible() {
        ProjectRepository(Files.createTempDirectory("yml-scroll-end")).use { repo ->
            val initial = project().copy(products = List(30) { index ->
                Product("item-$index", form.id, mapOf("id" to "SKU$index", "name" to "Товар $index"))
            })
            compose.setContent { Box(Modifier.size(1280.dp, 720.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.onNodeWithTag("product-row-item-29").assertIsDisplayed()
            val listBounds = compose.onNodeWithTag("products-list").fetchSemanticsNode().boundsInRoot
            val rowBounds = compose.onNodeWithTag("product-row-item-29").fetchSemanticsNode().boundsInRoot
            assertTrue(rowBounds.top >= listBounds.top && rowBounds.bottom <= listBounds.bottom)
        }
    }
    @Test fun productPhotoDeletionIsSavedOnlyWithCard() {
        ProjectRepository(Files.createTempDirectory("yml-delete-photo")).use { repo ->
            val photo = Picture(url = "https://example.com/photo.jpg")
            val initial = project().let { it.copy(products = it.products.map { p -> p.copy(pictures = listOf(photo)) }) }
            repo.save(initial)
            compose.setContent { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.onNodeWithTag("product-row-a").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("delete-photo-0"))
            compose.onNodeWithTag("delete-photo-0").performClick()
            compose.onNodeWithTag("delete-photo-0").assertDoesNotExist()
            assertEquals(initial, repo.load())
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Изменения сохранены").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(repo.load().products.first().pictures.isEmpty())
            assertEquals(listOf(photo), repo.load().products[1].pictures)
        }
    }
    @Test fun bulkPhotosAcceptLinksAndApplyOnlyToSelection() {
        val initial = project()
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "yml-photo-ui")
        val picture = Picture(url = "https://example.com/new.png")
        var result: Project? = null
        compose.setContent {
            CompositionLocalProvider(LocalImageDirectory provides directory) {
                StudioTheme(false) {
                    BulkEditDialog(initial, setOf("a", "b"), false, {}) { changes, articles, photos, _ ->
                        result = initial.updateProducts(setOf("a", "b"), changes, articles, photos)
                    }
                }
            }
        }
        compose.onNodeWithTag("bulk-fields").performScrollToNode(hasTestTag("bulk-photos-ADD"))
        compose.onNodeWithTag("bulk-photos-ADD").performClick()
        compose.onNodeWithTag("bulk-apply").assertIsNotEnabled()
        compose.onNodeWithText("Загрузить файл").assertDoesNotExist()
        compose.onNodeWithText("Добавить ссылку").performScrollTo().performClick()
        compose.onNodeWithText("Ссылка 1").performScrollTo().performTextInput(picture.url)
        compose.onNodeWithText(picture.url).assertExists()
        compose.onNodeWithTag("bulk-apply").performClick()
        assertEquals(listOf(picture), result!!.products[0].pictures)
        assertEquals(listOf(picture), result!!.products[1].pictures)
        assertEquals(initial.products[2], result!!.products[2])
        compose.onNodeWithText("Удалить", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("bulk-apply").assertIsNotEnabled()
        compose.onNodeWithTag("bulk-photos-CLEAR").performScrollTo().performClick()
        compose.onNodeWithTag("bulk-apply").assertIsEnabled()
    }
    @get:Rule val compose = createComposeRule()
    private val form = Template("form", "Одежда", fields = listOf(
        Field("id", "id", "Артикул"), Field("name", "name", "Название"),
        Field("price", "price", "Цена", type = "number"), Field("color", "param", "Цвет")))
    private fun project() = defaultProject().copy(templates = listOf(form, form.copy(id = "other", name = "Другая")), products = listOf(
        Product("a", form.id, mapOf("id" to "A1", "name" to "Майка", "price" to "100", "color" to "Белый")),
        Product("b", form.id, mapOf("id" to "A2", "name" to "Футболка", "price" to "200", "color" to "Чёрный")),
        Product("c", "other", mapOf("id" to "A3", "name" to "Куртка", "price" to "300"))))

    @OptIn(ExperimentalTestApi::class)
    @Test fun ordinaryClicksEditOrToggleSelectionDependingOnMode() {
        ProjectRepository(Files.createTempDirectory("yml-product-clicks")).use { repo ->
            val initial = project()
            repo.save(initial)
            compose.setContent { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.onNodeWithTag("product-row-a").performMouseInput { click() }
            compose.onNodeWithText("Карточка товара").assertExists()
            compose.onNode(hasSetTextAction() and hasText("Майка")).performTextReplacement("Новое название")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Изменения сохранены").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Новое название", repo.load().products.first().values["name"])
            compose.onNodeWithText("ОК").performClick()
            compose.openProductMenu("a")
            compose.onNodeWithText("Карточка товара").assertDoesNotExist()
            compose.onNodeWithTag("select-a").performClick()
            compose.onNodeWithTag("product-row-b").performMouseInput { click() }
            compose.onNodeWithText("Выбрано: 2").assertExists()
            compose.onNodeWithTag("product-row-c").performMouseInput { click() }
            compose.onNodeWithText("Выбрано: 2").assertExists()
            compose.onNodeWithText("Карточка товара").assertDoesNotExist()
            compose.onNodeWithTag("product-row-a").performMouseInput { click() }
            compose.onNodeWithText("Выбрано: 1").assertExists()
            compose.onNodeWithTag("product-row-b").performMouseInput { click() }
            compose.onNodeWithTag("bulk-edit").assertDoesNotExist()
            compose.onNodeWithTag("product-row-c").performMouseInput { click() }
            compose.onNodeWithText("Карточка товара").assertExists()
            compose.onNode(hasSetTextAction() and hasText("Куртка")).assertExists()
        }
    }

    @Test fun selectionRestrictsFormsAndBulkEditingPersistsOnlyCheckedFields() {
        ProjectRepository(Files.createTempDirectory("yml-bulk-ui")).use { repo ->
            val initial = project().copy(settings = Settings(darkTheme = true))
            repo.save(initial)
            compose.setContent { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.onNodeWithTag("bulk-edit").assertDoesNotExist()
            compose.onNodeWithText("Изменить", substring = false).assertDoesNotExist()
            compose.onNodeWithText("Удалить", substring = false).assertDoesNotExist()
            compose.onNodeWithTag("select-a").assertDoesNotExist()
            compose.openProductMenu("a")
            val menuShots = Path.of("build/reports/ui").also { Files.createDirectories(it) }
            javax.imageio.ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", menuShots.resolve("product-context-menu.png").toFile())
            compose.onNodeWithTag("select-a").performClick()
            compose.openProductMenu("c")
            compose.onNodeWithTag("select-c").assertIsNotEnabled()
            compose.dismissProductMenu("c")
            compose.openProductMenu("b")
            compose.onNodeWithTag("select-b").assertIsEnabled()
            compose.dismissProductMenu("b")
            compose.onNodeWithText("Выбрать все этой формы").performClick()
            compose.onNodeWithText("Выбрано: 2").assertExists()
            compose.onNodeWithTag("bulk-edit").performClick()
            compose.onNodeWithTag("bulk-field-id").assertDoesNotExist()
            compose.onNodeWithTag("bulk-apply").assertIsNotEnabled()
            compose.onNodeWithTag("bulk-field-price").performClick()
            compose.onNode(hasSetTextAction() and hasText("Цена")).performTextReplacement("750")
            val screenshots = Path.of("build/reports/ui").also { Files.createDirectories(it) }
            javax.imageio.ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", screenshots.resolve("bulk-edit-dark.png").toFile())
            compose.onNodeWithTag("bulk-apply").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("bulk-dialog").fetchSemanticsNodes().isEmpty() }
            val loaded = repo.load()
            assertEquals(listOf("750", "750", "300"), loaded.products.map { it.values["price"] })
            assertEquals(initial.products.map { it.values["color"] }, loaded.products.map { it.values["color"] })
            assertEquals(initial.products.map { it.values["id"] }, loaded.products.map { it.values["id"] })
            compose.onNodeWithTag("bulk-edit").assertDoesNotExist()
            compose.openProductMenu("c")
            compose.onNodeWithTag("select-c").assertIsEnabled()
            compose.dismissProductMenu("c")
        }
    }

    @Test fun cancelDoesNotSaveAndBulkDeleteRemovesOnlySelectionWithoutSuccessDialog() {
        ProjectRepository(Files.createTempDirectory("yml-bulk-delete")).use { repo ->
            val initial = project()
            repo.save(initial)
            compose.setContent { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.openProductMenu("a")
            compose.onNodeWithTag("select-a").performClick()
            compose.onNodeWithTag("bulk-edit").performClick()
            compose.onNodeWithTag("bulk-field-price").performClick()
            compose.onNode(hasSetTextAction() and hasText("Цена")).performTextReplacement("999")
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(initial, repo.load())
            compose.openProductMenu("b")
            compose.onNodeWithTag("select-b").performClick()
            compose.onNodeWithTag("bulk-delete").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("product-row-a").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf(initial.products.last()), repo.load().products)
            compose.onNodeWithText("Изменения сохранены").assertDoesNotExist()
            compose.openProductMenu("c")
            compose.onNodeWithTag("select-c").assertIsEnabled()
            compose.dismissProductMenu("c")
        }
    }

    @Test fun themeSwitchSavesPreferenceAndShowsBothModes() {
        ProjectRepository(Files.createTempDirectory("yml-theme-ui")).use { repo ->
            val initial = project()
            repo.save(initial)
            compose.setContent { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.onNodeWithText("Настройки каталога", substring = false).performClick()
            compose.onNodeWithText("Светлая").assertIsSelected()
            compose.onNodeWithText("Тёмная").performClick()
            compose.onNodeWithText("Тёмная").assertIsSelected()
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Изменения сохранены").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(repo.load().settings.darkTheme)
            compose.onNodeWithText("ОК").performClick()
            compose.onNodeWithText("Светлая").performClick()
            compose.onNodeWithText("Светлая").assertIsSelected()
        }
    }

    @Test fun bulkArticleGenerationOnlySavesOnApply() {
        ProjectRepository(Files.createTempDirectory("yml-bulk-articles")).use { repo ->
            val initial = project()
            repo.save(initial)
            compose.setContent { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.productAction("a", "Выбрать товар")
            compose.onNodeWithText("Выбрать все этой формы").performClick()
            compose.onNodeWithTag("bulk-edit").performClick()
            compose.onNodeWithTag("bulk-generate-articles").performClick()
            compose.onNodeWithTag("bulk-apply").assertIsEnabled()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(initial, repo.load())
            compose.onNodeWithTag("bulk-edit").performClick()
            compose.onNodeWithTag("bulk-generate-articles").assertIsEnabled().performClick()
            compose.onNodeWithTag("bulk-generate-articles").assertIsNotEnabled()
            compose.onNodeWithTag("bulk-cancel-articles").performClick()
            compose.onNodeWithTag("bulk-apply").assertIsNotEnabled()
            compose.onNodeWithTag("bulk-generate-articles").performClick()
            compose.onNodeWithTag("bulk-apply").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("bulk-dialog").fetchSemanticsNodes().isEmpty() }
            val result = repo.load()
            val articles = result.products.take(2).map { it.values.getValue("id") }
            assertEquals(2, articles.toSet().size)
            assertTrue(articles.all { isValidArticle(it) && it.length == 12 && it !in initial.usedArticles() })
            assertEquals(initial.products.last(), result.products.last())
        }
    }
}
