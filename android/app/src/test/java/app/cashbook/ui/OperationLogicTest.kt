package app.cashbook.ui

import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import app.cashbook.data.CategorySum
import app.cashbook.data.Exchange
import app.cashbook.data.Money
import app.cashbook.data.Operation
import app.cashbook.data.OperationType
import app.cashbook.data.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationLogicTest {
    private fun m(s: String) = Money.parse(s)
    private fun typed(keys: String) = keys.fold("") { e, k -> Calc.press(e, k) }

    private val income = Node(NodeKind.INCOME, 2)
    private val card = Node(NodeKind.ACCOUNT, 10)
    private val cash = Node(NodeKind.ACCOUNT, 11)
    private val food = Node(NodeKind.EXPENSE, 13)

    @Test fun pressBuildsValidExpression() {
        assertEquals("12,5+3", typed("12,5+3"))
        assertEquals("12+", typed("12×+"))          // оператор заменяется
        assertEquals("", typed("+×"))               // оператор в начале — нет
        assertEquals("0,5", typed(",5"))            // «,» в начале числа → «0,»
        assertEquals("1,25", typed("1,2,5"))        // вторая запятая в числе — нет
        assertEquals("1,2345", typed("1,23456"))    // не больше 4 знаков дроби
        assertEquals("7", typed("07"))              // ведущий ноль заменяется
        assertEquals("12", typed("123⌫"))
        assertEquals("9".repeat(13), typed("9".repeat(15))) // не больше 13 цифр
    }

    @Test fun evalArithmetic() {
        assertEquals(m("15,5"), Calc.eval("12,5+3"))
        assertEquals(m("18,5"), Calc.eval("12,5+3×2"))   // × раньше +
        assertEquals(m("4"), Calc.eval("10−3×2"))
        assertEquals(m("3,33"), Calc.eval("10÷3"))        // округление до копеек
        assertEquals(m("6,67"), Calc.eval("20÷3"))        // половина вверх
        assertEquals(m("3"), Calc.eval("10÷3", precision = 0))
        assertEquals(m("12"), Calc.eval("12+"))           // висящий оператор игнорируется
        assertEquals(m("-2"), Calc.eval("1−3"))           // отрицательное — отсеет amountError
        assertNull(Calc.eval(""))
        assertNull(Calc.eval("5÷0"))
        assertNull(Calc.eval("9999999999999×9999999999999")) // переполнение
    }

    @Test fun amountChecks() {
        assertEquals("Неверная сумма", amountError(null))
        assertEquals("Сумма должна быть больше нуля", amountError(0))
        assertEquals("Слишком большая сумма", amountError(MAX_OPERATION + 1))
        assertNull(amountError(m("0,01")))
    }

    @Test fun dropPairs() {
        assertEquals(OperationType.INCOME, dropType(income, card))
        assertEquals(OperationType.EXPENSE, dropType(card, food))
        assertEquals(OperationType.TRANSFER, dropType(card, cash))
        assertNull(dropType(card, card))
        assertNull(dropType(food, card))
        assertNull(dropType(income, food))
        assertNull(dropType(card, income))
        assertEquals(card to food, decodeDrop(encodeDrop(card, food)))
    }

    @Test fun buildsOperationsLikeCash() {
        val exp = buildOperation(card, food, m("100"), null, null, date = 5, position = 2, comment = "хлеб")
        assertEquals(OperationType.EXPENSE, exp.type)
        assertEquals(10L, exp.expenseAccountId)
        assertEquals(m("100"), exp.expenseAmount)
        assertEquals(13L, exp.categoryId)  // по умолчанию — статья кружка
        assertEquals(2, exp.position)

        val sub = buildOperation(card, food, m("1"), null, categoryId = 15, date = 5, position = 0, comment = "")
        assertEquals(15L, sub.categoryId)  // выбранная подстатья

        val inc = buildOperation(income, card, m("50"), null, null, 5, 0, "")
        assertEquals(OperationType.INCOME, inc.type)
        assertEquals(10L, inc.incomeAccountId)
        assertEquals(2L, inc.categoryId)

        val tr = buildOperation(card, cash, m("30"), null, null, 5, 0, "")
        assertEquals(OperationType.TRANSFER, tr.type)
        assertEquals(m("30"), tr.incomeAmount)
        assertNull(tr.categoryId)

        val ex = buildOperation(card, cash, m("830"), m("20"), null, 5, 0, "")
        assertEquals(m("830"), ex.expenseAmount)
        assertEquals(m("20"), ex.incomeAmount)

        assertThrows(IllegalArgumentException::class.java) { buildOperation(food, card, 1, null, null, 0, 0, "") }
    }

    private val tree = listOf(
        Category(id = 13, name = "Продукты", kind = CategoryKind.EXPENSE),
        Category(id = 14, name = "Хлебобулочные", parentId = 13, kind = CategoryKind.EXPENSE),
        Category(id = 15, name = "Хлеб", parentId = 14, kind = CategoryKind.EXPENSE),
        Category(id = 19, name = "Молочка", parentId = 13, kind = CategoryKind.EXPENSE),
        Category(id = 27, name = "Подарки", kind = CategoryKind.EXPENSE),
    )

    @Test fun rollUpSumsSubcategoriesIntoTop() {
        val r = rollUp(
            tree,
            listOf(
                CategorySum(13, "UAH", m("10")),
                CategorySum(15, "UAH", m("5")),   // внук → Продукты
                CategorySum(19, "USD", m("2")),
                CategorySum(27, "UAH", m("7")),
            ),
        )
        assertEquals(mapOf("UAH" to m("15"), "USD" to m("2")), r[13])
        assertEquals(mapOf("UAH" to m("7")), r[27])
    }

    @Test fun descendantsInTreeOrderWithDepthAlphabetical() {
        assertEquals(
            listOf("Молочка" to 0, "Хлебобулочные" to 0, "Хлеб" to 1),
            descendants(tree, 13).map { (c, d) -> c.name to d },
        )
        assertEquals(emptyList<Pair<Category, Int>>(), descendants(tree, 27))
    }

    @Test fun dropOfRestoresNodesWithTopCategory() {
        val tree = tree + Category(id = 2, name = "Работа", kind = CategoryKind.INCOME)
        val bread = Operation(date = 1, expenseAccountId = 10, expenseAmount = m("20"), categoryId = 15)
        assertEquals(card to food, dropOf(bread, tree))
        val salary = Operation(date = 1, incomeAccountId = 10, incomeAmount = m("500"), categoryId = 2)
        assertEquals(income to card, dropOf(salary, tree))
        val transfer = Operation(date = 1, expenseAccountId = 10, expenseAmount = m("5"), incomeAccountId = 11, incomeAmount = m("5"))
        assertEquals(card to cash, dropOf(transfer, tree))
        assertNull(dropOf(Operation(date = 1, expenseAccountId = 10, expenseAmount = m("1")), tree)) // без статьи
        // Пересборка из восстановленного перетаскивания даёт ту же операцию.
        val (from, to) = dropOf(bread, tree)!!
        assertEquals(bread.copy(createdAt = 0), buildOperation(from, to, m("20"), null, 15, 1, 0, "").copy(createdAt = 0))
    }

    @Test fun rateSidePicksValueAtLeastOne() {
        assertEquals(Rate(true, m("41,5")), rateOf(m("100"), m("4150")))     // 1 $ = 41,5 ₴
        assertEquals(Rate(false, m("41,5")), rateOf(m("4150"), m("100")))    // 1 $ = 41,5 ₴ при ₴ → $
        assertEquals(Rate(true, m("1")), rateOf(m("7"), m("7")))
        assertEquals(Rate(true, m("1,3333")), rateOf(m("3"), m("4")))        // 4 знака
        val e = Exchange("UAH", m("4150"), "USD", m("100"))
        assertEquals(Rate(true, m("41,5")), rateFrom(e, "USD"))              // прошлая операция в обратную сторону
        assertEquals(Rate(false, m("41,5")), rateFrom(e, "UAH"))
    }

    @Test fun convertByRate() {
        assertEquals(m("415"), convert(m("10"), Rate(true, m("41,5")), 2))
        assertEquals(m("2,41"), convert(m("100"), Rate(false, m("41,5")), 2)) // 100 ₴ → $
        assertEquals(m("12,35"), convert(m("1"), Rate(true, m("12,345")), 2)) // половина вверх
        assertEquals(m("12"), convert(m("1"), Rate(true, m("12,345")), 0))
        assertNull(convert(m("1"), Rate(true, 0), 2))
        assertNull(convert(Long.MAX_VALUE, Rate(true, m("1000")), 2))         // переполнение
    }

    @Test fun receivedIsBaseAmountForExpenseAndIncome() {
        assertEquals(m("415"), buildOperation(card, food, m("10"), m("415"), null, 5, 0, "").baseAmount)
        assertEquals(m("415"), buildOperation(income, card, m("10"), m("415"), null, 5, 0, "").baseAmount)
        assertNull(buildOperation(card, cash, m("10"), m("415"), null, 5, 0, "").baseAmount) // у перевода — зачисление
    }

    @Test fun toExprIsCalculatorInput() {
        assertEquals("12,5", toExpr(m("12,50")))
        assertEquals("100", toExpr(m("100")))
        assertEquals("1234567,0001", toExpr(m("1 234 567,0001")))
        assertEquals(m("1234567,0001"), Calc.eval(toExpr(m("1234567,0001")), 4))
    }

    @Test fun groupDigitsSplitsOnlyWholePart() {
        assertEquals("2 500", groupDigits("2500"))
        assertEquals("999", groupDigits("999"))
        assertEquals("12 500,12345", groupDigits("12500,12345")) // дробная часть не делится
        assertEquals("1 000+2 000 000", groupDigits("1000+2000000"))
        assertEquals("", groupDigits(""))
    }

    @Test fun circleInitialsSkipStopWordsAndStayUnique() {
        assertEquals(listOf("КР", "ОО", "ПР"), circleInitials(listOf("Кафе и рестораны", "Одежда и обувь", "Прочие расходы")))
        assertEquals(listOf("По", "Пд", "Пр"), circleInitials(listOf("Подработка", "Подарки", "Проценты")))
        assertEquals(listOf("По", "Пд"), circleInitials(listOf("Покупки", "Подарки"))) // дубль «По» → следующая буква
        assertEquals(listOf("Na", "Ka"), circleInitials(listOf("Nal", "Karta")))
        assertEquals(listOf("US", "ЗП"), circleInitials(listOf("USD", "ЗП"))) // заглавные остаются заглавными
        assertEquals(listOf("ЗП"), circleInitials(listOf("З/П (аванс)"))) // скобки и слэш — разделители
        assertEquals(listOf("", "Ап"), circleInitials(listOf("123", "Аптека"))) // без букв — пусто, не падает
    }

    @Test fun byDayKeepsOrderAndSumsExpensesPerCurrency() {
        val ops = listOf(
            Operation(id = 1, date = 5, expenseAccountId = 10, expenseAmount = m("10"), categoryId = 13),
            Operation(id = 2, date = 5, expenseAccountId = 12, expenseAmount = m("2"), categoryId = 13),
            Operation(id = 3, date = 5, incomeAccountId = 10, incomeAmount = m("500"), categoryId = 2),
            Operation(id = 4, date = 5, expenseAccountId = 10, expenseAmount = m("7"), categoryId = 13),
            Operation(id = 5, date = 4, expenseAccountId = 10, expenseAmount = m("3"), incomeAccountId = 11, incomeAmount = m("3")),
        )
        val days = byDay(ops, mapOf(10L to "UAH", 11L to "UAH", 12L to "USD"))
        assertEquals(listOf(5L, 4L), days.map { it.date })
        assertEquals(listOf(1L, 2L, 3L, 4L), days[0].operations.map { it.id })
        assertEquals(mapOf("UAH" to m("17"), "USD" to m("2")), days[0].expenses) // доход не в счёт
        assertEquals(emptyMap<String, Long>(), days[1].expenses) // перевод — не расход
    }
}
