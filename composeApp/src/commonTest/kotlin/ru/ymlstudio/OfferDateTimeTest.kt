package ru.ymlstudio

import kotlin.test.*

class OfferDateTimeTest {
    @Test fun pickerPreservesCalendarDateAndSeconds() {
        val date = parseOfferDateTime("2028-02-29T23:59:59")!!
        assertEquals("2028-02-29T23:59:59", formatOfferDateTime(date))
        assertEquals("2028-02-29T00:05:00", formatOfferDateTime(date.copy(hour = 0, minute = 5, second = 0)))
        assertEquals("29.02.2028 23:59", displayOfferDateTime("2028-02-29T23:59:59"))
    }
    @Test fun legacyMinutesAndInvalidDates() {
        assertEquals("2026-09-09T15:30:00", formatOfferDateTime(parseOfferDateTime("2026-09-09T15:30")!!))
        assertNull(parseOfferDateTime("2026-02-29T15:30"))
        assertNull(parseOfferDateTime(""))
        assertEquals("ошибка", displayOfferDateTime("ошибка"))
    }
}
