package ru.ymlstudio

private sealed interface CopyPart {
    data class Text(val value: String) : CopyPart
    data class Argument(val variable: String, val content: List<CopyPart>) : CopyPart
}

/** Nested name{value} assignments; no executable expressions. */
private fun argumentNames(names: Set<String>): Regex {
    val alternatives = names.sortedByDescending { it.length }.map(Regex::escape) + "p[1-9][0-9]*"
    return Regex("(?<![\\p{L}\\p{N}_])(?:${alternatives.joinToString("|")})(?![\\p{L}\\p{N}_])")
}

private class CopyPatternParser(private val text: String, names: Set<String>, private val allowBare: Boolean = false) {
    private val arguments = argumentNames(names)
    private var position = 0
    fun parse(): List<CopyPart> {
        require(text.length <= 65536) { "Текст быстрого заполнения слишком длинный" }
        return parts(false, 0)
    }

    private fun parts(nested: Boolean, depth: Int): List<CopyPart> {
        require(depth < 32) { "Слишком много вложенных аргументов" }
        val result = mutableListOf<CopyPart>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) { result += CopyPart.Text(literal.toString()); literal.clear() }
        }
        while (position < text.length) {
            val char = text[position]
            if (char == '\\' && position + 1 < text.length && (text[position + 1] in "{}\\p" || arguments.find(text, position + 1)?.range?.first == position + 1)) {
                literal.append(text[position + 1]); position += 2; continue
            }
            if (char == '}') {
                require(nested) { "Лишняя закрывающая скобка" }
                position++; flush(); return result
            }
            require(char != '{') { "Перед скобкой укажите переменную, например p2{синий}" }
            val match = arguments.find(text, position)?.takeIf { it.range.first == position }
            if (match != null) {
                val hasBraces = text.getOrNull(position + match.value.length) == '{'
                require(allowBare || hasBraces) { "Укажите значение в скобках: ${match.value}{…}" }
                flush()
                position += match.value.length
                val content = if (hasBraces) { position++; parts(true, depth + 1) } else emptyList()
                result += CopyPart.Argument(match.value, content)
            } else { literal.append(char); position++ }
        }
        require(!nested) { "Не закрыта фигурная скобка" }
        flush()
        return result
    }


}

private fun List<CopyPart>.variables(): Set<String> = flatMap { part ->
    when (part) {
        is CopyPart.Text -> emptyList()
        is CopyPart.Argument -> listOf(part.variable) + part.content.variables()
    }
}.toSet()

private fun Template.copyBindings(): Map<String, Field> {
    val bound = fields.filter { it.copyVariable.isNotBlank() }
    require(bound.all { field -> field.copyVariable.none { it.isWhitespace() || it.isISOControl() || it in "{}\\" } }) {
        "Имя переменной: без пробелов, скобок { } и обратной косой черты"
    }
    require(bound.map { it.copyVariable }.distinct().size == bound.size) { "Переменная привязана к нескольким полям" }
    require(bound.none { it.target in listOf("id", "ste") }) { "Артикул и СТЕ нельзя привязать к переменной" }
    return bound.associateBy { it.copyVariable }
}

private fun Template.copyParts(): List<CopyPart> {
    val bindings = copyBindings()
    val parts = CopyPatternParser(copyPattern, bindings.keys, allowBare = true).parse()
    if (copyPattern.isNotBlank()) require(parts.variables().isNotEmpty()) { "Добавьте аргумент, например p1{Название товара}" }
    parts.variables().forEach { require(it in bindings) { "Привяжите $it к полю формы" } }
    return parts
}

fun Template.copyPatternError(): String? = try {
    copyParts()
    if (copyPattern.isNotBlank()) copyValues(copyText(Product("preview", id)))
    null
} catch (e: IllegalArgumentException) { e.message }

/** Both a nested argument and its containing argument receive the rendered text. */
fun Template.copyValues(text: String): Map<String, String> {
    val bindings = copyBindings()
    val required = copyParts().variables()
    val parts = CopyPatternParser(text, bindings.keys).parse()
    val variables = parts.variables()
    variables.forEach { require(it in bindings) { "Привяжите $it к полю формы" } }
    require(variables.containsAll(required)) { "Пропущены аргументы: ${(required - variables).joinToString()}" }
    val values = mutableMapOf<String, String>()
    fun render(parts: List<CopyPart>): String = parts.joinToString("") { part ->
        when (part) {
            is CopyPart.Text -> part.value
            is CopyPart.Argument -> {
                val value = render(part.content).trim()
                val field = bindings.getValue(part.variable)
                require(field.id !in values || values[field.id] == value) { "Разные значения ${part.variable}" }
                values[field.id] = value
                value
            }
        }
    }
    render(parts)
    return values
}

/** Use the form's sentence structure and the source product's leaf values. */
fun Template.copyText(product: Product): String {
    val bindings = copyBindings()
    fun escape(value: String) = value.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
        .replace(argumentNames(bindings.keys)) { "\\${it.value}" }
    fun render(parts: List<CopyPart>): String = parts.joinToString("") { part ->
        when (part) {
            is CopyPart.Text -> escape(part.value)
            is CopyPart.Argument -> {
                val content = if (part.content.variables().isEmpty()) {
                    product.values[bindings.getValue(part.variable).id]?.takeIf { it.isNotBlank() }?.let(::escape) ?: render(part.content)
                } else render(part.content)
                "${part.variable}{$content}"
            }
        }
    }
    return render(copyParts())
}

fun Project.finishQuickProductCopy(copy: Product, text: String): Product {
    val template = templates.first { it.id == copy.templateId }
    return finishProductCopy(copy, template.copyValues(text))
}

/** Keep unchanged leaf arguments specific to each card, then rebuild enclosing fields. */
fun Template.bulkCopyValues(text: String, baseline: String, product: Product, applyAll: Boolean = false): Map<String, String> {
    val entered = copyValues(text)
    if (applyAll) return entered
    if (text == baseline) return emptyMap()
    val original = copyValues(baseline)
    val bindings = copyBindings()
    val parts = CopyPatternParser(text, bindings.keys).parse()
    val result = mutableMapOf<String, String>()
    fun render(parts: List<CopyPart>): String = parts.joinToString("") { part ->
        when (part) {
            is CopyPart.Text -> part.value
            is CopyPart.Argument -> {
                val field = bindings.getValue(part.variable)
                val rendered = if (part.content.variables().isEmpty() && entered[field.id] == original[field.id]) {
                    product.values[field.id].orEmpty()
                } else render(part.content).trim()
                result[field.id] = rendered
                rendered
            }
        }
    }
    render(parts)
    return result.filter { (id, value) -> value != product.values[id].orEmpty() }
}
