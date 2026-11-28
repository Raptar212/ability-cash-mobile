package app.cashbook.ui

import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import app.cashbook.data.Money
import app.cashbook.data.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTotalsTest {
    private fun m(s: String) = Money.parse(s)

    private val cats = mapOf(
        1L to Category(id = 1, name = "Продукты", kind = CategoryKind.EXPENSE),
        2L to Category(id = 2, name = "Супермаркет", parentId = 1, kind = CategoryKind.EXPENSE),
        3L to Category(id = 3, name = "Зарплата", kind = CategoryKind.INCOME),
    )
    private val accounts = mapOf(10L to "Карта Моно", 11L to "Наличные", 12L to "Dollar")
    private fun hit(op: Operation, q: String) = matchesQuery(op, q, cats, accounts)

    private val shop = Operation(
        id = 1, date = 0, expenseAccountId = 10, expenseAmount = m("120,50"), categoryId = 2, comment = "Сильпо на Оболони",
    )

    @Test fun cyrillicCaseInsensitiveInComment() {
        assertTrue(hit(shop, "сильпо"))
        assertTrue(hit(shop, "СИЛЬПО"))
        assertTrue(hit(shop.copy(comment = "сильпо"), "Сильпо"))
        assertFalse(hit(shop, "атб"))
    }

    @Test fun matchesCategoryWithParentAndAccount() {
        assertTrue(hit(shop, "супермаркет")) // сама статья
        assertTrue(hit(shop, "продукт"))     // родитель подстатьи
        assertTrue(hit(shop, "моно"))        // счёт
        assertFalse(hit(shop, "зарплата"))
    }

    @Test fun matchesAmountWithCommaOrDot() {
        assertTrue(hit(shop, "120,5"))
        assertTrue(hit(shop, "120.50"))
        assertFalse(hit(shop, "121"))
        val salary = Operation(id = 2, date = 0, incomeAccountId = 11, incomeAmount = m("5000"), categoryId = 3)
        assertTrue(hit(salary, "5000"))
        assertTrue(hit(salary, "зарп"))
    }

    @Test fun transferMatchesByEitherAccountName() {
        val t = Operation(id = 3, date = 0, expenseAccountId = 10, expenseAmount = m("100"), incomeAccountId = 11, incomeAmount = m("100"))
        assertTrue(hit(t, "налич"))
        assertTrue(hit(t, "моно"))
        assertFalse(hit(t, "dollar"))
    }

    @Test fun blankQueryMatchesAllAndCategoryCycleDoesNotHang() {
        assertTrue(hit(shop, "  "))
        val loop = mapOf(
            1L to Category(id = 1, name = "A", parentId = 2, kind = CategoryKind.EXPENSE),
            2L to Category(id = 2, name = "B", parentId = 1, kind = CategoryKind.EXPENSE),
        )
        assertFalse(matchesQuery(shop.copy(categoryId = 1, comment = ""), "zzz", loop, accounts))
    }

    @Test fun totalsPerCurrencyWithoutTransfers() {
        val currencyOf = mapOf(10L to "UAH", 11L to "UAH", 12L to "USD")
        val ops = listOf(
            Operation(date = 0, incomeAccountId = 10, incomeAmount = m("1000"), categoryId = 3),
            Operation(date = 0, expenseAccountId = 10, expenseAmount = m("300"), categoryId = 1),
            Operation(date = 0, expenseAccountId = 11, expenseAmount = m("50"), categoryId = 1),
            Operation(date = 0, expenseAccountId = 12, expenseAmount = m("20"), categoryId = 1, baseAmount = m("800")),
            Operation(date = 0, expenseAccountId = 10, expenseAmount = m("999"), incomeAccountId = 11, incomeAmount = m("999")),
        )
        val totals = periodTotals(ops, currencyOf)
        assertEquals(
            listOf(PeriodTotal("UAH", m("1000"), m("350")), PeriodTotal("USD", 0, m("20"))),
            totals,
        )
        assertEquals(m("650"), totals[0].net)
        assertEquals("Доходы +1 000,00 ₴ · Расходы −350,00 ₴ · Итог 650,00 ₴", totalLine(totals[0], 2))
        assertEquals("Расходы −20,00 $", totalLine(totals[1], 2)) // только расходы: ноль и «Итог» скрыты
        assertEquals("Доходы +5,00 $", totalLine(PeriodTotal("USD", m("5"), 0), 2))
        assertTrue(periodTotals(emptyList(), currencyOf).isEmpty())
        assertTrue(periodTotals(ops.takeLast(1), currencyOf).isEmpty()) // один перевод — итогов нет
    }

    @Test fun commentHintsFilterByTypedText() {
        val recent = listOf("Сильпо", "Молоко", "Сильпо на Оболони")
        assertEquals(recent, commentHints(recent, ""))
        assertEquals(listOf("Сильпо на Оболони"), commentHints(recent, "Сильпо")) // равное — не подсказка
        assertEquals(listOf("Сильпо", "Сильпо на Оболони"), commentHints(recent, "сил"))
        assertTrue(commentHints(recent, "хлеб").isEmpty())
    }

    @Test fun usesAccountDetectsEitherSide() {
        val t = Operation(date = 0, expenseAccountId = 10, expenseAmount = 1, incomeAccountId = 11, incomeAmount = 1)
        assertTrue(usesAccount(t, setOf(11)))
        assertTrue(usesAccount(t, setOf(10, 99)))
        assertFalse(usesAccount(t, setOf(12)))
        assertFalse(usesAccount(t, emptySet()))
    }
}
