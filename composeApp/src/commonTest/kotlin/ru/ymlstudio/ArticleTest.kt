package ru.ymlstudio

import kotlin.random.Random
import kotlin.test.*

class ArticleTest {
    @Test fun formatMatchesExistingRules() {
        for (value in listOf("1", "aBc123", "A".repeat(20))) assertTrue(isValidArticle(value))
        for (value in listOf("", "A".repeat(21), "Артикул", "A-1", "A 1", " A1", "A1\n", "A_1")) assertFalse(isValidArticle(value))
    }

    @Test fun batchIsUniqueAndContainsOnlyUnambiguousCharacters() {
        val generator = ArticleGenerator(setOf("OLD"), Random(42))
        val articles = List(10_000) { generator.next() }
        assertEquals(articles.size, articles.toSet().size)
        assertTrue(articles.all { it.length == 12 && isValidArticle(it) && it.none { char -> char in "01IO" } })
    }

    @Test fun collisionIsRetriedIncludingDifferentCaseAndSurroundingSpaces() {
        val seed = 71
        val sequence = ArticleGenerator(emptySet(), Random(seed))
        val first = sequence.next()
        val second = sequence.next()
        assertEquals(second, randomArticle(setOf(" ${first.lowercase()} "), Random(seed)))
    }

    @Test fun brokenRandomSourceFailsInsteadOfLoopingForeverOrReturningDuplicate() {
        val constant = object : Random() { override fun nextBits(bitCount: Int): Int = 0 }
        assertFailsWith<IllegalStateException> { randomArticle(setOf("2".repeat(12)), constant) }
    }
}
