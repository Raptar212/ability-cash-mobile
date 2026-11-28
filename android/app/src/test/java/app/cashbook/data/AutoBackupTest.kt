package app.cashbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class AutoBackupTest {
    private fun name(day: Int) = AutoBackup.fileName(LocalDateTime.of(2026, 11, day, 9, 0, 0))

    @Test fun fileNameHasDateAndTime() {
        assertEquals("cashbook-auto-2026-11-05-090000.db", name(5))
    }

    @Test fun oldDateOnlyNamesAreOursAndSortChronologically() {
        val old = "cashbook-auto-2026-11-28.db" // версия без времени — старше копии того же дня со временем
        val sameDay = "cashbook-auto-2026-11-28-090000.db"
        val newer = (29..30).map { "cashbook-auto-2026-11-$it-010000.db" } + (1..5).map { "cashbook-auto-2026-12-0$it-010000.db" }
        assertEquals(listOf(old), AutoBackup.staleNames(listOf(old, sameDay) + newer.drop(1))) // 6 новее + копия со временем = 7 остаются
        assertEquals(listOf(sameDay, old), AutoBackup.staleNames(listOf(old, sameDay) + newer))
    }

    @Test fun keepsSevenNewestAndOnlyOwnFiles() {
        val own = (1..10).map(::name)
        val foreign = listOf("cashbook-2026-11-01.db", "photo.jpg", "cashbook-auto-notes.txt")
        val stale = AutoBackup.staleNames((own + foreign).shuffled())
        assertEquals(listOf(name(3), name(2), name(1)), stale) // старые по имени; чужие не трогаем
        assertTrue(AutoBackup.staleNames(own.take(7)).isEmpty())
        assertTrue(AutoBackup.staleNames(emptyList()).isEmpty())
    }

    @Test fun lookalikeNamesAreNotOursAndExcludedNeverStale() {
        val odd = listOf("cashbook-auto-old.db", "cashbook-auto-2026-11-28 (1).db", "cashbook-auto-zzz.db", "cashbook-auto-2026-11-01.db.tmp")
        val own = (1..8).map(::name)
        assertEquals(listOf(name(1)), AutoBackup.staleNames(own + odd)) // чужие не удаляются и не занимают места
        assertTrue(AutoBackup.staleNames(own, exclude = name(1)).isEmpty()) // только что записанный не удаляется
    }

    @Test fun dueAfterDayOrWhenNeverDone() {
        val day = AutoBackup.EVERY_MS
        assertTrue(AutoBackup.due(5000, 4000)) // часы ушли назад
        assertTrue(AutoBackup.due(0, 5))
        assertFalse(AutoBackup.due(1000, 1000 + day - 1))
        assertTrue(AutoBackup.due(1000, 1000 + day))
    }

    @Test fun statusText() {
        val last = LocalDate.of(2026, 11, 28).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Выключена", AutoBackup.statusText(false, last, "x", ZoneOffset.UTC))
        assertEquals("Последняя: 28.11.2026", AutoBackup.statusText(true, last, null, ZoneOffset.UTC))
        assertEquals("Ошибка: База занята", AutoBackup.statusText(true, last, "База занята", ZoneOffset.UTC))
        assertEquals("Включена, копии ещё не было", AutoBackup.statusText(true, 0, null, ZoneOffset.UTC))
    }
}
