package ru.ymlstudio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class FolderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createsFolderMovesSelectionCopiesInsideAndReturnsCardToDesktop() {
        ProjectRepository(Files.createTempDirectory("yml-folders-ui")).use { repo ->
            val initial = validProject().let { p -> p.copy(products = listOf(
                p.products.single().copy(id = "one", values = p.products.single().values + ("name" to "Первый")),
                p.products.single().copy(id = "two", values = p.products.single().values + mapOf("id" to "A002", "name" to "Второй"))
            )) }
            repo.save(initial)
            compose.setContent { MaterialTheme { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } } }
            compose.onNodeWithText("+ Папка").performClick()
            compose.onNodeWithText("Название папки").performTextReplacement("Отправленные")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Новая папка").fetchSemanticsNodes().isEmpty() }
            val folderId = repo.load().folders.single().id
            compose.productAction("one", "Выбрать товар")
            compose.onNodeWithTag("product-row-two").performClick()
            compose.onNodeWithTag("bulk-move").performClick()
            compose.onNodeWithText("Поиск папки").performTextReplacement("ОТПРАВ")
            compose.onNodeWithTag("move-to-$folderId").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("product-row-one").fetchSemanticsNodes().isEmpty() }
            assertTrue(repo.load().products.all { it.folderId == folderId })
            compose.onNodeWithTag("open-folders").performClick()
            compose.onNodeWithText("Папки товаров").assertExists()
            compose.onNodeWithText("Поиск папки").performTextReplacement("не существует")
            compose.onNodeWithText("Папки не найдены").assertExists()
            compose.onNodeWithTag("folder-$folderId").assertDoesNotExist()
            compose.onNodeWithText("Поиск папки").performTextReplacement("правл")
            compose.onNodeWithTag("folder-$folderId").performClick()
            compose.onNodeWithTag("product-row-one").assertExists()
            compose.productAction("one", "Копировать")
            compose.onNodeWithText("Новое название товара").performTextReplacement("Копия в папке")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Копировать товар").fetchSemanticsNodes().isEmpty() }
            assertEquals(folderId, repo.load().products.last().folderId)
            compose.onNodeWithText("Переименовать").performClick()
            compose.onNodeWithText("Название папки").performTextReplacement("Архив")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Переименовать папку").fetchSemanticsNodes().isEmpty() }
            assertEquals("Архив", repo.load().folders.single().name)
            compose.onNodeWithText("Удалить папку").assertIsNotEnabled()
            compose.onNodeWithTag("products-list").performScrollToNode(hasTestTag("product-row-one"))
            compose.productAction("one", "Переместить")
            compose.onNodeWithTag("move-to-").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("product-row-one").fetchSemanticsNodes().isEmpty() }
            assertEquals("", repo.load().products.first().folderId)
            compose.onNodeWithTag("open-folders").performClick()
            compose.onNodeWithTag("folder-").performClick()
            compose.onNodeWithTag("product-row-one").assertExists()
            compose.onNodeWithTag("product-row-two").assertDoesNotExist()
            compose.onNodeWithText("Выгрузить YML").performClick()
            compose.onNodeWithText("1 товаров · UTF-8 · RUB").assertExists()
            compose.onNodeWithText("Отдельная папка").performClick()
            compose.onNodeWithText("Папка для экспорта").assertExists()
            compose.onNodeWithText("Поиск папки").performTextReplacement("арх")
            compose.onNodeWithTag("export-folder-$folderId").performClick()
            compose.onNodeWithText("Папка: Архив").assertExists()
            compose.onNodeWithText("2 товаров · UTF-8 · RUB").assertExists()
            compose.onNodeWithText("Все товары").performClick()
            compose.onNodeWithText("3 товаров · UTF-8 · RUB").assertExists()
        }
    }
}
