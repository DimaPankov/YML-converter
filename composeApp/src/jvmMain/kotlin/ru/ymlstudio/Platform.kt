package ru.ymlstudio

import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

actual fun httpUrl(value: String, limit: Int, public: Boolean): Boolean = runCatching {
    val uri = URI(value)
    //skd
    val authority = uri.rawAuthority.orEmpty()
    val host = authority.substringBeforeLast(':', authority).lowercase()
    value.length <= limit && uri.scheme in listOf("http", "https") && authority.isNotBlank() &&
        !authority.contains('@') && value.none { it.isWhitespace() } &&
        (uri.host != null || Regex("[\\p{L}\\p{N}.-]+(:[0-9]+)?").matches(authority)) &&
        (!public || (host !in listOf("localhost", "127.0.0.1", "[::1]") && authority.lowercase() !in listOf("localhost", "127.0.0.1", "[::1]")))
}.getOrDefault(false)
actual fun validDate(value: String): Boolean = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2})?").matches(value) &&
    runCatching { LocalDateTime.parse(value); true }.getOrDefault(false)
actual fun decimalCompare(left: String, right: String): Int? = runCatching { left.replace(',', '.').toBigDecimal().compareTo(right.replace(',', '.').toBigDecimal()) }.getOrNull()
actual fun currentCatalogDate(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

actual fun parseOfferDateTime(value: String): OfferDateTime? = runCatching {
    if (!validDate(value)) return null
    val date = LocalDateTime.parse(value)
    OfferDateTime(date.toLocalDate().atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), date.hour, date.minute, date.second)
}.getOrNull()
actual fun formatOfferDateTime(value: OfferDateTime): String = java.time.Instant.ofEpochMilli(value.dateMillis)
    .atOffset(java.time.ZoneOffset.UTC).toLocalDate().atTime(value.hour, value.minute, value.second)
    .format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"))
actual fun displayOfferDateTime(value: String): String = runCatching {
    LocalDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm"))
}.getOrDefault(value)
