package ru.ymlstudio

/** Older forms sometimes stored the delivery term as a same-named param instead of option/@days. */
fun Field.isDeliveryDaysParameter(): Boolean = target == "param" &&
    label.trim().replace(Regex("\\s+"), " ").equals("Срок доставки, рабочие дни", ignoreCase = true)

fun Project.withDeliveryDaysMapping(): Project {
    var mappedProducts = products
    val mappedTemplates = templates.map { template ->
        val aliases = template.fields.filter { it.isDeliveryDaysParameter() }
        if (aliases.isEmpty()) return@map template
        val existing = template.fields.firstOrNull { it.target == "deliveryDays" }
        var id = "deliveryDays"
        while (template.fields.any { it.id == id }) id += "_"
        val defaults = aliases.map { it.default.trim() }.filter { it.isNotEmpty() }.distinct()
        val fallback = defaults.singleOrNull()?.takeIf(::validDeliveryDays).orEmpty()
        val field = (existing ?: Field(id, "deliveryDays", "Срок доставки, рабочие дни", required = true)).let {
            it.copy(default = it.default.ifBlank { fallback }, quickAccess = it.quickAccess || aliases.any { alias -> alias.quickAccess })
        }
        mappedProducts = mappedProducts.map { product ->
            if (product.templateId != template.id || !product.values[field.id].isNullOrBlank()) return@map product
            val values = aliases.map { product.values[it.id].orEmpty().trim() }.filter { it.isNotEmpty() }.distinct()
            val value = values.singleOrNull()?.takeIf(::validDeliveryDays) ?: return@map product
            product.copy(values = product.values + (field.id to value))
        }
        val related = mappedProducts.filter { it.templateId == template.id }
        // Drop duplicate controls only when all values were preserved. Conflicts remain visible.
        val redundant = aliases.filter { alias ->
            (alias.default.isBlank() || (validDeliveryDays(alias.default.trim()) && alias.default.trim() == field.default.trim())) &&
                related.all { product ->
                    val value = product.values[alias.id].orEmpty().trim()
                    value.isEmpty() || (validDeliveryDays(value) && value == product.values[field.id].orEmpty().trim())
                }
        }.map { it.id }.toSet()
        template.copy(fields = template.fields.filter { it.id !in redundant }.map {
            if (it.id == field.id) field else it
        }.let { if (existing == null) it + field else it })
    }
    return copy(templates = mappedTemplates, products = mappedProducts)
}
