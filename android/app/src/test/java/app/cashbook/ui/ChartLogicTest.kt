package app.cashbook.ui

import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import app.cashbook.data.CategorySum
import app.cashbook.data.DaySum
import app.cashbook.data.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ChartLogicTest {
    private fun m(s: String) = Money.parse(s)

    private val tree = listOf(
        Category(id = 1, name = "Работа", kind = CategoryKind.INCOME),
        Category(id = 13, name = "Продукты", kind = CategoryKind.EXPENSE),
        Category(id = 14, name = "Хлебобулочные", parentId = 13, kind = CategoryKind.EXPENSE),
        Category(id = 15, name = "Хлеб", parentId = 14, kind = CategoryKind.EXPENSE),
        Category(id = 19, name = "Молочка", parentId = 13, kind = CategoryKind.EXPENSE),
        Category(id = 27, name = "Подарки", kind = CategoryKind.EXPENSE),
    )
    private val sums = listOf(
        CategorySum(13, "UAH", m("10")),  // на самой статье
        CategorySum(15, "UAH", m("5")),   // внук → Хлебобулочные
        CategorySum(14, "UAH", m("1")),
        CategorySum(19, "UAH", m("20")),
        CategorySum(19, "USD", m("2")),   // другая валюта
        CategorySum(27, "UAH", m("7")),
        CategorySum(1, "UAH", m("500")),  // доход
    )

    @Test fun topLevelSlicesByKindAndCurrencySortedDesc() {
        val s = slices(tree, sums, CategoryKind.EXPENSE, "UAH", null)
        assertEquals(listOf(Slice(13, "Продукты", m("36"), true), Slice(27, "Подарки", m("7"), false)), s)
        assertEquals(listOf(Slice(13, "Продукты", m("2"), true)), slices(tree, sums, CategoryKind.EXPENSE, "USD", null))
        assertEquals(listOf(Slice(1, "Работа", m("500"), false)), slices(tree, sums, CategoryKind.INCOME, "UAH", null))
    }

    @Test fun drillDownRollsIntoDirectChildrenAndOwnAmount() {
        val s = slices(tree, sums, CategoryKind.EXPENSE, "UAH", 13)
        assertEquals(
            listOf(
                Slice(19, "Молочка", m("20"), false),
                Slice(13, NO_SUBCATEGORY, m("10"), false),
                Slice(14, "Хлебобулочные", m("6"), true),
            ),
            s,
        )
        // Подарки — не под Продуктами.
        assertEquals(emptyList<Slice>(), slices(tree, listOf(CategorySum(27, "UAH", m("7"))), CategoryKind.EXPENSE, "UAH", 13))
    }

    @Test fun foldKeepsFiveAndOther() {
        val many = (1..8).map { Slice(it.toLong(), "c$it", (10 - it).toLong(), false) } // 9, 8 … 2
        val f = fold(many)
        assertEquals(6, f.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), f.take(5).map { it.id })
        assertEquals(Slice(null, OTHER, 4L + 3L + 2L, false), f.last())
        assertEquals(many.take(6), fold(many.take(6))) // 6 долей — без «Другого»
    }

    @Test fun percentRoundsHalfUpWithoutOverflow() {
        assertEquals(33, percent(1, 3))
        assertEquals(67, percent(2, 3))
        assertEquals(50, percent(1, 2))
        assertEquals(0, percent(5, 0))
        assertEquals(100, percent(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2))
    }

    @Test fun monthlyTwelveMonthsPerCurrencyWithEmptyMonths() {
        fun d(s: String) = LocalDate.parse(s).toEpochDay()
        val rows = listOf(
            DaySum(d("2026-11-03"), true, "UAH", m("10")),
            DaySum(d("2026-11-20"), true, "UAH", m("5")),
            DaySum(d("2026-11-20"), false, "UAH", m("100")),
            DaySum(d("2026-11-21"), true, "USD", m("9")),
            DaySum(d("2025-12-31"), true, "UAH", m("1")),
            DaySum(d("2025-11-30"), true, "UAH", m("999")), // за окном 12 месяцев
        )
        val bars = monthly(rows, YearMonth.of(2026, 11), "UAH")
        assertEquals(12, bars.size)
        assertEquals(MonthBar(YearMonth.of(2025, 12), m("1"), 0), bars.first())
        assertEquals(MonthBar(YearMonth.of(2026, 11), m("15"), m("100")), bars.last())
        assertEquals(MonthBar(YearMonth.of(2026, 5), 0, 0), bars[5])
    }
}
