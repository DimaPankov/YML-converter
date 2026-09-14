package ru.ymlstudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// DatePicker represents calendar dates at UTC midnight; offer times remain local wall times.
data class OfferDateTime(val dateMillis: Long, val hour: Int, val minute: Int, val second: Int = 0)
expect fun parseOfferDateTime(value: String): OfferDateTime?
expect fun formatOfferDateTime(value: OfferDateTime): String
expect fun displayOfferDateTime(value: String): String

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OfferDateTimeInput(value: String, label: String, modifier: Modifier = Modifier,
    onChange: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton({ choosing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (value.isBlank()) "Выбрать дату и время" else displayOfferDateTime(value))
        }
        if (value.isNotBlank()) TextButton({ onChange("") }) { Text("Очистить") }
    }
    if (choosing) {
        val initial = remember { parseOfferDateTime(value) ?: parseOfferDateTime(currentCatalogDate().replace(' ', 'T'))!! }
        val date = rememberDatePickerState(initialSelectedDateMillis = initial.dateMillis)
        val time = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        var timeStep by remember { mutableStateOf(false) }
        var keyboard by remember { mutableStateOf(false) }
        if (!timeStep) DatePickerDialog(onDismissRequest = { choosing = false },
            confirmButton = { TextButton({ timeStep = true }, enabled = date.selectedDateMillis != null) { Text("Далее: время") } },
            dismissButton = { TextButton({ choosing = false }) { Text("Отмена") } }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DatePicker(date, title = { Text(label, Modifier.padding(16.dp)) })
            }
        } else AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Время — $label") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayOfferDateTime(formatOfferDateTime(OfferDateTime(date.selectedDateMillis!!, time.hour, time.minute))))
                    if (keyboard) TimeInput(time) else TimePicker(time)
                    TextButton({ keyboard = !keyboard }) { Text(if (keyboard) "Выбрать на часах" else "Ввести цифрами") }
                    TextButton({ timeStep = false }) { Text("Назад к календарю") }
                }
            }, confirmButton = { TextButton({
                onChange(formatOfferDateTime(OfferDateTime(date.selectedDateMillis!!, time.hour, time.minute, initial.second)))
                choosing = false
            }) { Text("Готово") } },
            dismissButton = { TextButton({ choosing = false }) { Text("Отмена") } })
    }
}
