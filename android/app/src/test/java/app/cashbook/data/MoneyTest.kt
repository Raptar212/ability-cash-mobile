package app.cashbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test fun parsesCommaDotAndSpaces() {
        assertEquals(12_345_600L, Money.parse("1234,56"))
        assertEquals(12_345_600L, Money.parse("1234.56"))
        assertEquals(12_345_600L, Money.parse("1 234,56"))
        assertEquals(12_345_600L, Money.parse("1 234,56"))
        assertEquals(120_000L, Money.parse("12"))
        assertEquals(120_000L, Money.parse("+12"))
        assertEquals(5_000L, Money.parse(",5"))
    }

    @Test fun parsesNegativeAndZero() {
        assertEquals(-120_000L, Money.parse("-12"))
        assertEquals(-100L, Money.parse("-0,01"))
        assertEquals(0L, Money.parse("0"))
        assertEquals(1L, Money.parse("0,0001"))
    }

    @Test fun rejectsGarbageAndTooPrecise() {
        for (bad in listOf("", "-", ",", "abc", "1,2,3", "12a", "1.23456", "--1", "١٢")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Money.parse(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { Money.parse("1" + "0".repeat(14)) }
    }

    @Test fun formats() {
        assertEquals("1 234,56", Money.format(12_345_600L))
        assertEquals("0,00", Money.format(0L))
        assertEquals("-0,01", Money.format(-100L))
        assertEquals("-1 000 000,00", Money.format(-10_000_000_000L))
        assertEquals("12", Money.format(120_000L, precision = 0))
        assertEquals("0,0001", Money.format(1L, precision = 4))
    }

    @Test fun formatRoundsHalfAwayFromZero() {
        assertEquals("0,01", Money.format(50L))
        assertEquals("0,00", Money.format(49L))
        assertEquals("-0,01", Money.format(-50L))
        assertEquals("0,00", Money.format(-49L))
    }

    @Test fun formatRejectsOverflow() {
        assertThrows(IllegalArgumentException::class.java) { Money.format(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { Money.format(Long.MIN_VALUE) }
        assertEquals("1 000 000 000 000,00", Money.format(Money.parse("1000000000000")))
    }

    @Test fun roundTrip() {
        for (s in listOf("0,00", "1,05", "-99 999,99", "123 456 789,10")) {
            assertEquals(s, Money.format(Money.parse(s)))
        }
    }
}
