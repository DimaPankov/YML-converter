package ru.ymlstudio

const val VAT_INCLUDED_LABEL = "НДС включен в цену продукта"
fun Field.isVatIncludedParameter() = target == "param" && label.trim() == VAT_INCLUDED_LABEL
fun vatIncludedParameter(id: String) = Field(id, "param", VAT_INCLUDED_LABEL, type = "boolean", inputMode = "select")

// ndsType in the official YML schema, downloaded 2026-09-08.
// Source: https://old.zakupki.mos.ru/Content/Pub/YmlExchange/v1_0/Xsd/schema.xsd
val vatOptions: List<Pair<String, String>> = listOf("NO_VAT" to "Без НДС") +
    listOf("0", "5", "7", "10", "18", "20", "22").map { "VAT_$it" to "$it % — VAT_$it" }
val vatAliases: Map<String, String> = buildMap {
    vatOptions.forEach { (code, _) -> put(code, code) }
    put("Без НДС", "NO_VAT")
    listOf("0", "5", "7", "10", "18", "20", "22").forEach { put(it, "VAT_$it") }
}
fun canonicalVat(value: String): String? = vatAliases[value.trim()]
fun vatSelection(value: String): String = canonicalVat(value) ?: value
fun vatChoices(value: String): List<Pair<String, String>> = listOf("" to "Выберите НДС") +
    (if (value.isNotBlank() && canonicalVat(value) == null) listOf(value to "Недопустимое значение: $value") else emptyList()) + vatOptions
