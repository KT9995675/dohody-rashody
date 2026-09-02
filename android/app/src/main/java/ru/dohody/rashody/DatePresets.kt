package ru.dohody.rashody

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DatePresets {
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    fun range(preset: String): Pair<String, String> {
        val today = LocalDate.now()
        return when (preset) {
            "today" -> today.format(fmt) to today.format(fmt)
            "week" -> today.minusDays(6).format(fmt) to today.format(fmt)
            "month" -> today.withDayOfMonth(1).format(fmt) to today.format(fmt)
            else -> "" to ""
        }
    }

    fun displayDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return iso.take(10)
    }

    fun money(n: Double): String {
        return if (n % 1.0 == 0.0) n.toInt().toString() else String.format("%.2f", n)
    }

    fun today(): String = LocalDate.now().format(fmt)

    fun format(epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(fmt)

    fun parseOrNull(ymd: String?): LocalDate? {
        if (ymd.isNullOrBlank()) return null
        return try {
            LocalDate.parse(ymd.take(10), fmt)
        } catch (_: Exception) {
            null
        }
    }
}
