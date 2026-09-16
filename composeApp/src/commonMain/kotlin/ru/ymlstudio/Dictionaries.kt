package ru.ymlstudio

import kotlinx.serialization.Serializable

@Serializable data class FieldOption(val value: String, val label: String = "")
data class DictionaryEntry(val id: String, val code: String, val name: String, val parentId: String = "")
data class FieldChoice(val value: String, val label: String, val detail: String = "", val selectable: Boolean = true) {
    val searchText = "$value $label $detail".lowercase()
}
val dictionaryTitles = listOf("country" to "Страны (ОКСМ)", "okei" to "Единицы измерения (ОКЕИ)",
    "category" to "Категории портала", "region" to "Регионы", "vat" to "НДС", "package" to "Упаковка",
    "currency" to "Валюты", "unit" to "Единицы характеристик")
private fun readDictionary(raw: String) = raw.lineSequence().filter { it.isNotBlank() }.map {
    val p = it.split('\t'); DictionaryEntry(p[0], p[1], p[2], p.getOrElse(3) { "" })
}.toList()
object Dictionaries {
    val country by lazy { readDictionary(DictionaryData.country) }
    val okei by lazy { readDictionary(DictionaryData.okei) }
    val category by lazy { readDictionary(DictionaryData.category) }
    val region by lazy { readDictionary(DictionaryData.region) }
    val vat by lazy { readDictionary(DictionaryData.vat) }
    val packaging by lazy { readDictionary(DictionaryData.`package`) }
    val currency by lazy { readDictionary(DictionaryData.currency) }
    val unit by lazy { readDictionary(DictionaryData.unit) }
    fun entries(key: String): List<DictionaryEntry> = when(key) {
        "country" -> country; "okei" -> okei; "category" -> category; "region" -> region
        "vat" -> vat; "package" -> packaging; "currency" -> currency; "unit" -> unit; else -> emptyList()
    }
    private val choicesCache by lazy { dictionaryTitles.associate { it.first to buildChoices(it.first) } }
    fun choices(key: String): List<FieldChoice> = choicesCache[key].orEmpty()
    private fun buildChoices(key: String): List<FieldChoice> {
        val entries = entries(key)
        val parents = if (key == "category") entries.map { it.parentId }.toSet() else emptySet()
        val byId = if (key == "category") entries.associateBy { it.id } else emptyMap()
        return entries.map { e ->
            val value = when (key) {
                "category", "region", "package" -> e.id
                "okei" -> "${e.code} | ${e.name}"
                "unit" -> e.name
                "vat" -> canonicalVat(e.name.removeSuffix("%")) ?: e.code
                "currency" -> currencyCode(e)
                else -> e.code
            }
            val ancestors = mutableListOf<String>()
            var parent = e.parentId
            val visited = mutableSetOf<String>()
            while (parent.isNotEmpty() && visited.add(parent)) {
                val item = byId[parent] ?: break
                ancestors.add(item.name); parent = item.parentId
            }
            val detail = if (key == "category") ancestors.asReversed().joinToString(" → ")
                else ""
            val shownCode = if (key in listOf("vat", "currency")) value else if (key in listOf("category", "region", "package")) e.id else e.code
            FieldChoice(value, "$shownCode — ${e.name.ifBlank { "Название отсутствует в справочнике" }}", detail,
                e.name.isNotBlank() && !(key in listOf("okei", "unit") && e.name.all { it.isDigit() }) && !(key == "category" && e.id in parents))
        }
    }
}
private fun currencyCode(e: DictionaryEntry) = when(e.name.lowercase()) {
    "российский рубль" -> "RUB"; "доллар сша" -> "USD"; "евро" -> "EUR"; "фунт стерлингов" -> "GBP"; else -> e.code
}
fun dictionaryForTarget(target: String) = when(target) {
    "oksm" -> "country"; "okei" -> "okei"; "ppCategory" -> "category"; "regions", "region" -> "region"
    "vat" -> "vat"; "packageType" -> "package"; "currencyId" -> "currency"; else -> ""
}
fun Field.dictionaryKey() = if (dictionary == "custom") "" else dictionary.ifBlank { dictionaryForTarget(target) }
fun Field.customListOptions(): List<FieldOption> = options.flatMap { option ->
    listOf(FieldOption(option.value)) +
        if (option.label.isNotBlank() && option.label != option.value) listOf(FieldOption(option.label)) else emptyList()
}
fun Field.choices(): List<FieldChoice> {
    val key = dictionaryKey()
    if (key.isNotEmpty()) return Dictionaries.choices(key)
    if (options.isNotEmpty()) return customListOptions().map { it.value.trim() }.filter { it.isNotEmpty() }
        .distinct().map { FieldChoice(it, it) }
    return if (type == "boolean") listOf(FieldChoice("true", "Да"), FieldChoice("false", "Нет")) else emptyList()
}
fun Field.effectiveInputMode() = if (inputMode != "auto") inputMode else if (type == "boolean") "select"
    else if (dictionaryKey().isNotEmpty() || options.isNotEmpty()) "both" else "text"
fun searchChoices(choices: List<FieldChoice>, query: String): List<FieldChoice> {
    val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (tokens.isEmpty()) choices else choices.filter { choice -> tokens.all { it in choice.searchText } }
}
fun regionEntry(value: String) = Dictionaries.region.firstOrNull { it.id == value.trim() || it.code == value.trim() || it.name.equals(value.trim(), true) }
fun Field.choiceValue(choice: FieldChoice, current: String): String {
    if (target != "regions") return choice.value
    val name = regionEntry(choice.value)?.name ?: choice.value
    return (regionNames(current) + name).distinct().joinToString("; ")
}
fun Field.valueDescription(raw: String): String {
    if (raw.isBlank()) return ""
    if (target == "regions") return regionNames(raw).joinToString("; ") { value -> regionEntry(value)?.let { "${it.id} — ${it.name}" } ?: "$value — нет в справочнике" }
    return when(dictionaryKey()) {
        "country" -> countryValue(raw)?.takeIf { it.name.isNotBlank() }?.let { "${it.code} — ${it.name}" }.orEmpty()
        "okei" -> okeiValue(raw)?.let { "${it.id} — ${it.name}" }.orEmpty()
        "vat" -> canonicalVat(raw)?.let { code -> choices().firstOrNull { it.value == code }?.label }.orEmpty()
        else -> choices().firstOrNull { it.value == raw.trim() }?.label.orEmpty()
    }.ifBlank { if (dictionaryKey().isNotEmpty() || options.isNotEmpty()) "Значение отсутствует в списке" else "" }
}

fun Field.acceptsChoice(raw: String): Boolean {
    if (target == "regions") return regionNames(raw).all { regionEntry(it) != null }
    return choices().any { choice -> choice.selectable && when(dictionaryKey()) {
        "okei" -> okeiValue(raw) != null && okeiValue(raw) == okeiValue(choice.value)
        "country" -> countryValue(raw) != null && countryValue(raw) == countryValue(choice.value)
        "vat" -> canonicalVat(raw) != null && canonicalVat(raw) == choice.value
        else -> raw.trim() == choice.value
    } }
}
val supportedCurrencies = setOf("RUR", "RUB", "USD", "BYR", "BYN", "KZT", "EUR", "UAH")
fun currencyValue(raw: String): String = raw.trim().ifEmpty { "RUB" }.uppercase().let { value ->
    if (value in supportedCurrencies) value else Dictionaries.currency.firstOrNull { it.code == value || it.name.equals(value, true) }?.let(::currencyCode) ?: value
}
