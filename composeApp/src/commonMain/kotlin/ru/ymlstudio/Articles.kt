package ru.ymlstudio

import kotlin.random.Random

private val articlePattern = Regex("[A-Za-z0-9]{1,20}")
private const val ARTICLE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
const val GENERATED_ARTICLE_LENGTH = 12

fun isValidArticle(value: String): Boolean = articlePattern.matches(value)

/** Random product identifier, not a credential. Avoid ambiguous 0/O and 1/I characters. */
class ArticleGenerator(used: Set<String>, private val random: Random = Random.Default) {
    private val reserved = used.map { it.trim().uppercase() }.toHashSet()

    fun next(): String {
        repeat(128) {
            val candidate = buildString {
                repeat(GENERATED_ARTICLE_LENGTH) { append(ARTICLE_ALPHABET[random.nextInt(ARTICLE_ALPHABET.length)]) }
            }
            if (reserved.add(candidate)) return candidate
        }
        error("Не удалось подобрать уникальный артикул. Повторите операцию.")
    }
}

fun randomArticle(used: Set<String>, random: Random = Random.Default): String = ArticleGenerator(used, random).next()

fun Project.usedArticles(): Set<String> {
    val forms = templates.associateBy { it.id }
    return products.map { product -> product.valuesFor(forms.getValue(product.templateId))["id"].orEmpty() }.toSet()
}
