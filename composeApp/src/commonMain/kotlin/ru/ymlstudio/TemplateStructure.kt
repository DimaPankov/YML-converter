package ru.ymlstudio

/** Structural identity deliberately excludes IDs, order, defaults and editor settings. */
data class FieldStructure(val target: String, val type: String, val label: String, val unit: String)
data class FieldSlot(val structure: FieldStructure, val occurrence: Int)

private fun Field.structure() = FieldStructure(target, type, if (target == "param") label.trim() else "", unit.trim())
fun Template.structure(): Map<FieldStructure, Int> = fields.groupingBy { it.structure() }.eachCount()

internal fun Template.fieldSlots(): Map<FieldSlot, Field> {
    val counts = mutableMapOf<FieldStructure, Int>()
    return fields.associate { field ->
        val structure = field.structure()
        val occurrence = counts[structure] ?: 0
        counts[structure] = occurrence + 1
        FieldSlot(structure, occurrence) to field
    }
}

/** Move values by field meaning, not by IDs allocated by a particular import. */
fun Product.withTemplateFields(source: Template, destination: Template): Product {
    val sourceSlots = source.fieldSlots()
    val mapped = destination.fieldSlots().mapNotNull { (slot, field) ->
        val old = sourceSlots[slot] ?: return@mapNotNull null
        values[old.id]?.let { field.id to it }
    }.toMap()
    return copy(templateId = destination.id, values = mapped)
}

/** Match against the OLD structure so additions/removals also reach equivalent forms. */
fun Project.updateTemplate(updated: Template): Project {
    require(updated.copyPatternError() == null) { updated.copyPatternError().orEmpty() }
    val original = templates.firstOrNull { it.id == updated.id }
        ?: return copy(templates = templates + updated)
    val structure = original.structure()
    val equivalent = templates.filter { it.id == original.id || it.structure() == structure }.associateBy { it.id }
    val newFields = updated.fields.filter { field -> original.fields.none { it.id == field.id } }
    val nextProducts = products.map { product ->
        val source = equivalent[product.templateId] ?: return@map product
        // First align IDs using the previous definition; renamed/retyped fields retain values.
        val aligned = if (source.id == original.id) product else {
            val sourceIds = source.fields.map { it.id }.toSet()
            product.withTemplateFields(source, original).let {
                it.copy(values = product.values.filterKeys { key -> key !in sourceIds } + it.values)
            }
        }
        val values = aligned.values + newFields.associate { it.id to if (it.target == "ste") "" else it.default }
        product.copy(values = values)
    }
    return copy(templates = templates.map { template ->
        when {
            template.id == updated.id -> updated
            template.id in equivalent -> template.copy(fields = updated.fields, defaultPictures = updated.defaultPictures, copyPattern = updated.copyPattern)
            else -> template
        }
    }, products = nextProducts)
}
