package app.cashbook.ui

import app.cashbook.data.Account
import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import app.cashbook.data.Currency
import app.cashbook.data.Money
import app.cashbook.data.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoinKeeperCsvTest {
    private fun m(s: String) = Money.parse(s)
    private fun day(s: String) = LocalDate.parse(s).toEpochDay()

    private val currencies = listOf(Currency(1, "UAH", "Гривна"), Currency(2, "USD", "Доллар"))
    private val accounts = listOf(
        Account(10, "Кошелек", 1),
        Account(11, "Коробка \"доллар\"", 2, archived = true),
    )
    private val categories = listOf(
        Category(1, "Доход", kind = CategoryKind.INCOME),
        Category(2, "Подработка", 1, CategoryKind.INCOME),
        Category(13, "Продукты", kind = CategoryKind.EXPENSE),
        Category(14, "Хлебобулочные", 13, CategoryKind.EXPENSE),
        Category(15, "Хлеб", 14, CategoryKind.EXPENSE),
        Category(16, "Газировка,вод,сок", 13, CategoryKind.EXPENSE),
    )

    private fun rows(ops: List<Operation>) =
        CoinKeeperCsv.write(ops, accounts, currencies, categories).split("\n")

    @Test fun headerBomAndSep() {
        val lines = rows(emptyList())
        assertEquals("\uFEFFsep=,", lines[0])
        assertEquals(
            "\"Данные\",\"Тип\",\"Из\",\"В\",\"Метки\",\"Сумма\",\"Валюта\",\"Сумма в др.валюте\",\"Др.валюта\",\"Повторение\",\"Заметка\"",
            lines[1],
        )
        assertEquals(listOf(""), lines.drop(2)) // файл заканчивается переводом строки
    }

    @Test fun expenseTopCategoryAndSubcategoryAsLabel() {
        val lines = rows(
            listOf(
                Operation(date = day("2023-06-14"), expenseAccountId = 10, expenseAmount = m("55"), categoryId = 13),
                Operation(date = day("2023-06-15"), expenseAccountId = 10, expenseAmount = m("39,80"), categoryId = 15, comment = "к чаю"),
                Operation(date = day("2023-06-16"), expenseAccountId = 10, expenseAmount = m("12,5"), categoryId = 16),
            ),
        )
        assertEquals("\"14.06.2023\",\"Расход\",\"Кошелек\",\"Продукты\",\"\",\"55\",\"UAH\",\"55\",\"UAH\",\"Нет\",\"\"", lines[2])
        assertEquals("\"15.06.2023\",\"Расход\",\"Кошелек\",\"Продукты\",\"Хлеб\",\"39,8\",\"UAH\",\"39,8\",\"UAH\",\"Нет\",\"к чаю\"", lines[3])
        assertEquals("\"16.06.2023\",\"Расход\",\"Кошелек\",\"Продукты\",\"Газировка,вод,сок\",\"12,5\",\"UAH\",\"12,5\",\"UAH\",\"Нет\",\"\"", lines[4])
    }

    @Test fun incomeIsTransferFromSource() {
        val lines = rows(listOf(Operation(date = day("2024-01-05"), incomeAccountId = 10, incomeAmount = m("1500"), categoryId = 2)))
        assertEquals("\"05.01.2024\",\"Перевод\",\"Доход\",\"Кошелек\",\"Подработка\",\"1500\",\"UAH\",\"1500\",\"UAH\",\"Нет\",\"\"", lines[2])
    }

    @Test fun exchangeUsesBothCurrenciesAndEscapesQuotes() {
        val lines = rows(
            listOf(
                Operation(
                    date = day("2024-11-09"), expenseAccountId = 11, expenseAmount = m("100"),
                    incomeAccountId = 10, incomeAmount = m("4000"), comment = "курс \"40\"",
                ),
            ),
        )
        assertEquals(
            "\"09.11.2024\",\"Перевод\",\"Коробка \"\"доллар\"\"\",\"Кошелек\",\"\",\"100\",\"USD\",\"4000\",\"UAH\",\"Нет\",\"курс \"\"40\"\"\"",
            lines[2],
        )
    }

    @Test fun baseAmountIsSecondCurrencyInCategory() {
        val lines = rows(
            listOf(
                Operation(date = day("2024-11-10"), expenseAccountId = 11, expenseAmount = m("100"), categoryId = 13, baseAmount = m("4150")),
                Operation(date = day("2024-11-11"), incomeAccountId = 11, incomeAmount = m("50"), categoryId = 2, baseAmount = m("2075,5")),
            ),
        )
        assertEquals("\"10.11.2024\",\"Расход\",\"Коробка \"\"доллар\"\"\",\"Продукты\",\"\",\"100\",\"USD\",\"4150\",\"UAH\",\"Нет\",\"\"", lines[2])
        assertEquals("\"11.11.2024\",\"Перевод\",\"Доход\",\"Коробка \"\"доллар\"\"\",\"Подработка\",\"2075,5\",\"UAH\",\"50\",\"USD\",\"Нет\",\"\"", lines[3])
    }

    @Test fun oldestFirstThenPositionInDay() {
        val ops = listOf(
            Operation(id = 1, date = day("2024-02-02"), position = 0, expenseAccountId = 10, expenseAmount = m("3"), categoryId = 13),
            Operation(id = 2, date = day("2024-02-01"), position = 1, expenseAccountId = 10, expenseAmount = m("2"), categoryId = 13),
            Operation(id = 3, date = day("2024-02-01"), position = 0, expenseAccountId = 10, expenseAmount = m("1"), categoryId = 13),
        )
        val amounts = rows(ops).drop(2).filter { it.isNotEmpty() }.map { it.split("\",\"")[5] }
        assertEquals(listOf("1", "2", "3"), amounts)
        assertTrue(rows(ops).all { !it.contains('\r') })
    }
}
