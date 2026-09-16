package ru.ymlstudio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.openProductMenu(id: String) {
    onNodeWithTag("product-row-$id").performMouseInput { click(button = MouseButton.Secondary) }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.dismissProductMenu(id: String) {
    onNodeWithTag("product-menu-$id").performMouseInput { click(Offset(-10f, -10f)) }
    waitUntil(5_000) { onAllNodesWithTag("product-menu-$id").fetchSemanticsNodes().isEmpty() }
}

internal fun ComposeContentTestRule.productAction(id: String, label: String) {
    openProductMenu(id)
    onNode(hasText(label) and hasAnyAncestor(hasTestTag("product-menu-$id"))).performClick()
}
