package ru.ymlstudio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.*
import java.nio.file.Files

class BulkAutomationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedProductsOpenOneLineEditingInsideEditDialogAndSaveTogether() {
        ProjectRepository(Files.createTempDirectory("yml-bulk-line")).use { repo ->
            val initial = bulkAutomationProject()
            repo.save(initial)
            compose.setContent { Box(Modifier.size(1280.dp, 860.dp)) { Studio(repo, initial, false, {}, {}) } }
            compose.onNodeWithTag("products-list").performScrollToNode(hasTestTag("product-row-item1"))
            compose.productAction("item1", "Выбрать товар")
            compose.onNodeWithTag("product-row-item2").performClick()
            compose.onNodeWithText("Заполнить строкой").assertDoesNotExist()
            compose.onNodeWithTag("bulk-edit").performClick()
            compose.onNodeWithText("Заполнить строкой").assertExists()
            compose.onNodeWithTag("bulk-use-template").performClick()
            compose.onNodeWithText("Быстрое заполнение").assertExists()
            compose.onNodeWithText("Быстрое заполнение").performTextReplacement("title{Футболка color{белый}, size{10}}")
            compose.onNodeWithTag("bulk-apply").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("bulk-dialog").fetchSemanticsNodes().isEmpty() }
            val saved = repo.load()
            assertEquals("Футболка белый, 10", saved.products[0].values["name"])
            assertEquals("Футболка белый, 20", saved.products[1].values["name"])
            assertEquals(initial.products[2], saved.products[2])
        }
    }

    @Test fun editsTemplateAndPhotoTogetherWithoutOverwritingDifferentSizes() {
        val initial = bulkAutomationProject()
        val ids = setOf("item1", "item2")
        var result: Project? = null
        compose.setContent { StudioTheme(false) {
            BulkEditDialog(initial, ids, false, {}) { values, articles, photos, template ->
                result = initial.updateProducts(ids, values, articles, photos, template)
            }
        } }
        compose.onNodeWithTag("bulk-use-template").performClick()
        compose.onNodeWithText("Быстрое заполнение").performTextReplacement("title{color, size}")
        compose.onNodeWithTag("bulk-apply").assertIsNotEnabled()
        compose.onNodeWithText("Быстрое заполнение").performTextReplacement("title{Футболка color{красный}, size{10}}")
        compose.onNodeWithTag("bulk-fields").performScrollToNode(hasTestTag("bulk-photos-EDIT_URL"))
        compose.onNodeWithTag("bulk-photos-EDIT_URL").performClick()
        compose.onNodeWithTag("bulk-apply").assertIsNotEnabled()
        compose.onNodeWithText("Новая ссылка фотографии").performScrollTo().performTextReplacement("https://example.com/replaced.jpg")
        compose.onNodeWithTag("bulk-apply").performClick()
        val changed = assertNotNull(result)
        assertEquals("Футболка красный, 20", changed.products[1].values["name"])
        assertEquals("20", changed.products[1].values["size"])
        assertEquals("https://example.com/replaced.jpg", changed.products[1].pictures.first().url)
        assertEquals(initial.products[1].pictures[1], changed.products[1].pictures[1])
        assertEquals(initial.products[2], changed.products[2])
    }
}
