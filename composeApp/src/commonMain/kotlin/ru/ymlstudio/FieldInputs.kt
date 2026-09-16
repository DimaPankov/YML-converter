package ru.ymlstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared Android/Desktop input. Large dictionaries are virtualized and searched locally. */
@Composable fun FieldValueInput(field: Field, value: String, label: String = field.label,
    modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    if (field.target == "vat") {
        Column(modifier) {
            SmallChoice(vatSelection(value), label, vatChoices(""), onChange)
        }
        return
    }
    if (field.target in listOf("beginDate", "endDate") || field.type == "datetime-local") {
        OfferDateTimeInput(value, label, modifier, onChange)
        return
    }
    val mode = field.effectiveInputMode()
    var choosing by remember(field.id) { mutableStateOf(false) }
    val choices = remember(field.target, field.dictionary, field.options, field.type) { field.choices() }
    val description = remember(field, value) { field.valueDescription(value) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (mode != "select") OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
            singleLine = field.type != "textarea", minLines = if (field.type == "textarea") 2 else 1)
        else Text(label, style = MaterialTheme.typography.labelLarge)
        if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall)
        if (mode != "text") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ choosing = true }) { Text(if (mode == "select" && value.isNotBlank()) value else "Выбрать из списка") }
            if (value.isNotEmpty()) TextButton({ onChange("") }) { Text("Очистить") }
        }
    }
    if (choosing) ChoiceDialog(label, choices, onDismiss = { choosing = false }) { choice ->
        onChange(field.choiceValue(choice, value)); choosing = false
    }
}

@Composable private fun ChoiceDialog(title: String, choices: List<FieldChoice>, onDismiss: () -> Unit, onSelect: (FieldChoice) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(choices, query) { searchChoices(choices, query) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Поиск по коду или названию") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Найдено: ${matches.size} из ${choices.size}", style = MaterialTheme.typography.bodySmall)
            if (matches.isEmpty()) Text("Совпадений нет")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                itemsIndexed(matches) { _, choice ->
                    TextButton(onClick = { onSelect(choice) }, enabled = choice.selectable, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.label)
                            if (choice.detail.isNotEmpty()) Text(choice.detail, style = MaterialTheme.typography.bodySmall)
                            if (!choice.selectable) Text("Недоступно для выбора", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
}

@Composable private fun SmallChoice(value: String, label: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton({ expanded = true }) { Text(options.firstOrNull { it.first == value }?.second ?: value) }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onChange(key); expanded = false }) }
            }
        }
    }
}

@Composable fun FieldChoiceConfiguration(field: Field, onChange: (Field) -> Unit) {
    if (field.target == "vat") {
        FieldValueInput(field, field.default, "Значение для новых карточек") { onChange(field.copy(default = it)) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallChoice(field.effectiveInputMode(), "Способ заполнения", listOf("text" to "Только ввод", "select" to "Только список", "both" to "Ввод и список")) { onChange(field.copy(inputMode = it)) }
        SmallChoice(field.dictionary, "Источник вариантов", listOf("" to "По назначению поля", "custom" to "Мой список") + dictionaryTitles) { onChange(field.copy(dictionary = it, inputMode = if (it == "custom" && field.effectiveInputMode() == "text") "both" else field.inputMode)) }
        if (field.dictionaryKey().isNotEmpty()) Text("Записей: ${field.choices().size}", style = MaterialTheme.typography.bodySmall)
        else if (field.dictionary == "custom" || field.effectiveInputMode() != "text" || field.options.isNotEmpty()) {
            val options = field.customListOptions()
            options.forEachIndexed { index, option ->
                Column {
                    OutlinedTextField(option.value, { value -> onChange(field.copy(options = options.mapIndexed { i, old -> if (i == index) old.copy(value = value) else old })) }, label = { Text("Вариант ${index + 1}") }, modifier = Modifier.fillMaxWidth())
                    TextButton({ onChange(field.copy(options = options.filterIndexed { i, _ -> i != index })) }) { Text("Удалить вариант ${index + 1}") }
                }
            }
            OutlinedButton({ onChange(field.copy(options = options + FieldOption(""))) }) { Text("+ Добавить вариант") }
        }
        FieldValueInput(field, field.default, "Значение для новых карточек") { onChange(field.copy(default = it)) }
    }
}

@Composable fun AddFieldButtons(fields: List<Field>, newId: () -> String, add: (Field) -> Unit) {
    var ready by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ ready = true }) { Text("+ Готовое поле") }
        OutlinedButton({ add(Field(newId(), "param", "Новое поле")) }) { Text("+ Собственное поле") }
        OutlinedButton({ add(Field(newId(), "param", "Поле со своим списком", inputMode = "both", dictionary = "custom")) }) { Text("+ Поле со своим списком") }
    }
    if (ready) {
        val definitions = fieldDefinitions.filter { d -> d.target != "categoryId" && fields.none { it.target == d.target } }
        val choices = definitions.map { d -> FieldChoice(d.target, d.label, dictionaryTitles.firstOrNull { it.first == dictionaryForTarget(d.target) }?.second ?: "Ввод значения") } +
            if (fields.none { it.isVatIncludedParameter() }) listOf(FieldChoice("vatIncludedParameter", VAT_INCLUDED_LABEL, "Характеристика товара · Да/Нет")) else emptyList()
        ChoiceDialog("Готовые поля", choices, { ready = false }) { choice ->
            if (choice.value == "vatIncludedParameter") add(vatIncludedParameter(newId()))
            else {
                val d = definitions.first { it.target == choice.value }
                add(Field(newId(), d.target, d.label, d.type, d.required, default = if (d.target == "currencyId") "RUB" else ""))
            }
            ready = false
        }
    }
}
