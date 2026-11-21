package app.cashbook.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CashDao
    private var uah = 0L
    private var usd = 0L
    private var card = 0L
    private var cash = 0L
    private var dollars = 0L
    private var food = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        uah = dao.insertCurrency(Currency(code = "UAH", name = "Гривна"))
        usd = dao.insertCurrency(Currency(code = "USD", name = "Доллар"))
        card = dao.insertAccount(Account(name = "Карта", currencyId = uah, startingBalance = Money.parse("1000")))
        cash = dao.insertAccount(Account(name = "Наличные", currencyId = uah, startingBalance = Money.parse("200,50")))
        dollars = dao.insertAccount(Account(name = "Доллары", currencyId = usd))
        food = dao.insertCategory(Category(name = "Продукты", kind = CategoryKind.EXPENSE))
    }

    @After fun tearDown() = db.close()

    private fun balance(accountId: Long): Long = runBlocking {
        dao.balances().first().single { it.accountId == accountId }.balance
    }

    private fun add(op: Operation) = runBlocking { dao.insertOperation(op) }

    @Test fun noOperationsGivesStartingBalance() {
        assertEquals(Money.parse("1000"), balance(card))
        assertEquals(Money.parse("200,50"), balance(cash))
        assertEquals(0L, balance(dollars))
    }

    @Test fun expenseAndIncome() {
        add(Operation(date = 1, expenseAccountId = card, expenseAmount = Money.parse("150,25"), categoryId = food))
        add(Operation(date = 2, incomeAccountId = card, incomeAmount = Money.parse("50")))
        assertEquals(Money.parse("899,75"), balance(card))
        assertEquals(Money.parse("200,50"), balance(cash))
    }

    @Test fun transferMovesMoneyBetweenAccounts() {
        add(Operation(date = 1, expenseAccountId = card, expenseAmount = Money.parse("300"), incomeAccountId = cash, incomeAmount = Money.parse("300")))
        assertEquals(Money.parse("700"), balance(card))
        assertEquals(Money.parse("500,50"), balance(cash))
    }

    @Test fun exchangeUsesAmountsInOwnCurrencies() {
        add(Operation(date = 1, expenseAccountId = card, expenseAmount = Money.parse("830"), incomeAccountId = dollars, incomeAmount = Money.parse("20")))
        assertEquals(Money.parse("170"), balance(card))
        assertEquals(Money.parse("20"), balance(dollars))
    }

    @Test fun archivedAccountHiddenButTransfersStillCount() = runBlocking {
        val old = dao.insertAccount(Account(name = "Старый", currencyId = uah, archived = true))
        add(Operation(date = 1, expenseAccountId = old, expenseAmount = Money.parse("10"), incomeAccountId = card, incomeAmount = Money.parse("10")))
        val rows = dao.balances().first()
        assertEquals(listOf(dollars, card, cash).sorted(), rows.map { it.accountId }.sorted())
        assertEquals(Money.parse("1010"), balance(card))
    }

    @Test fun currencyCodeIsUniqueAndPrecisionChecked() {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insertCurrency(Currency(code = "UAH", name = "Дубль")) }
        }
        assertThrows(IllegalArgumentException::class.java) { Currency(code = "XXX", name = "x", precision = 5) }
    }

    @Test fun categorySumsPerCurrencyWithoutTransfersAndOutsidePeriod() = runBlocking {
        val salary = dao.insertCategory(Category(name = "Работа", kind = CategoryKind.INCOME))
        add(Operation(date = 10, expenseAccountId = card, expenseAmount = Money.parse("100"), categoryId = food))
        add(Operation(date = 11, expenseAccountId = cash, expenseAmount = Money.parse("50"), categoryId = food))
        add(Operation(date = 12, expenseAccountId = dollars, expenseAmount = Money.parse("2"), categoryId = food))
        add(Operation(date = 12, incomeAccountId = card, incomeAmount = Money.parse("1000"), categoryId = salary))
        add(Operation(date = 40, expenseAccountId = card, expenseAmount = Money.parse("999"), categoryId = food)) // вне периода
        add(Operation(date = 12, expenseAccountId = card, expenseAmount = Money.parse("5"), incomeAccountId = cash, incomeAmount = Money.parse("5")))
        val sums = dao.categorySums(1, 31).first().map { Triple(it.categoryId, it.currencyCode, it.amount) }.toSet()
        assertEquals(
            setOf(
                Triple(food, "UAH", Money.parse("150")),
                Triple(food, "USD", Money.parse("2")),
                Triple(salary, "UAH", Money.parse("1000")),
            ),
            sums,
        )
    }

    @Test fun nextPositionPerDay() = runBlocking {
        assertEquals(0, dao.nextPosition(7))
        add(Operation(date = 7, position = 0, incomeAccountId = card, incomeAmount = 1))
        add(Operation(date = 7, position = 3, incomeAccountId = card, incomeAmount = 1))
        assertEquals(4, dao.nextPosition(7))
        assertEquals(0, dao.nextPosition(8))
        // insertAtEndOfDay игнорирует переданный position и ставит в конец дня.
        val id = dao.insertAtEndOfDay(Operation(date = 7, position = 0, incomeAccountId = card, incomeAmount = 1))
        assertEquals(4, dao.operations(7, 7).first().single { it.id == id }.position)
    }

    @Test fun balanceCarriesCurrencyForFormatting() = runBlocking {
        val row = dao.balances().first().single { it.accountId == dollars }
        assertEquals("USD", row.currencyCode)
        assertEquals(2, row.precision)
    }

    @Test fun saveEditKeepsIdAndPositionOrMovesToEndOfNewDay() = runBlocking {
        add(Operation(date = 7, position = 0, expenseAccountId = card, expenseAmount = Money.parse("1"), categoryId = food))
        val id = dao.insertAtEndOfDay(Operation(date = 7, expenseAccountId = card, expenseAmount = Money.parse("10"), categoryId = food, createdAt = 111))
        add(Operation(date = 8, position = 4, expenseAccountId = card, expenseAmount = Money.parse("1"), categoryId = food))
        val old = dao.operation(id)!!
        assertEquals(1, old.position)

        dao.saveEdit(old, Operation(date = 7, expenseAccountId = card, expenseAmount = Money.parse("25"), categoryId = food, comment = "x"))
        val same = dao.operation(id)!!
        assertEquals(listOf(1, 111L, "x"), listOf(same.position, same.createdAt, same.comment))
        assertEquals(Money.parse("1000") - Money.parse("27"), balance(card))

        dao.saveEdit(same, same.copy(date = 8, position = 0))
        val moved = dao.operation(id)!!
        assertEquals(8L to 5, moved.date to moved.position) // в конец нового дня
        assertEquals(3, dao.operations(1, 31).first().size)
    }

    @Test fun deleteOperationRestoresBalance() = runBlocking {
        val id = dao.insertAtEndOfDay(Operation(date = 1, expenseAccountId = card, expenseAmount = Money.parse("50"), categoryId = food))
        assertEquals(Money.parse("950"), balance(card))
        dao.deleteOperation(dao.operation(id)!!)
        assertEquals(Money.parse("1000"), balance(card))
        assertNull(dao.operation(id))
    }

    @Test fun accountsWithArchivedIncludeArchivedWithCurrency() = runBlocking {
        val old = dao.insertAccount(Account(name = "Старый", currencyId = usd, archived = true))
        val row = dao.accountsWithArchived().first().single { it.accountId == old }
        assertEquals(listOf("Старый", "USD", 2), listOf(row.name, row.currencyCode, row.precision))
        assertEquals(4, dao.accountsWithArchived().first().size)
    }

    @Test fun daySumsSplitExpenseIncomeByCurrencyWithoutTransfers() = runBlocking {
        val salary = dao.insertCategory(Category(name = "Работа", kind = CategoryKind.INCOME))
        add(Operation(date = 10, expenseAccountId = card, expenseAmount = Money.parse("100"), categoryId = food))
        add(Operation(date = 10, expenseAccountId = cash, expenseAmount = Money.parse("50"), categoryId = food))
        add(Operation(date = 10, expenseAccountId = dollars, expenseAmount = Money.parse("2"), categoryId = food))
        add(Operation(date = 10, incomeAccountId = card, incomeAmount = Money.parse("1000"), categoryId = salary))
        add(Operation(date = 10, expenseAccountId = card, expenseAmount = Money.parse("5"), incomeAccountId = cash, incomeAmount = Money.parse("5")))
        add(Operation(date = 40, expenseAccountId = card, expenseAmount = Money.parse("999"), categoryId = food)) // вне периода
        val rows = dao.daySums(1, 31).first().map { listOf(it.date, it.expense, it.currencyCode, it.amount) }.toSet()
        assertEquals(
            setOf(
                listOf(10L, true, "UAH", Money.parse("150")),
                listOf(10L, true, "USD", Money.parse("2")),
                listOf(10L, false, "UAH", Money.parse("1000")),
            ),
            rows,
        )
    }

    @Test fun baseAmountGoesToReportsInBaseCurrencyButNotToBalance() = runBlocking {
        add(Operation(date = 10, expenseAccountId = dollars, expenseAmount = Money.parse("10"), categoryId = food, baseAmount = Money.parse("415")))
        add(Operation(date = 10, expenseAccountId = dollars, expenseAmount = Money.parse("2"), categoryId = food)) // без пересчёта
        add(Operation(date = 10, expenseAccountId = card, expenseAmount = Money.parse("100"), categoryId = food))
        assertEquals(
            setOf(Triple(food, "UAH", Money.parse("515")), Triple(food, "USD", Money.parse("2"))),
            dao.categorySums(1, 31).first().map { Triple(it.categoryId, it.currencyCode, it.amount) }.toSet(),
        )
        assertEquals(
            setOf(listOf(10L, true, "UAH", Money.parse("515")), listOf(10L, true, "USD", Money.parse("2"))),
            dao.daySums(1, 31).first().map { listOf(it.date, it.expense, it.currencyCode, it.amount) }.toSet(),
        )
        assertEquals(Money.parse("-12"), balance(dollars)) // остаток — в валюте счёта
    }

    @Test fun lastAccountOfExpenseOrIncomeSkipsTransfersAndArchived() = runBlocking {
        assertNull(dao.lastAccount(true))
        add(Operation(date = 5, expenseAccountId = card, expenseAmount = 1, categoryId = food))
        add(Operation(date = 6, incomeAccountId = cash, incomeAmount = 1))
        add(Operation(date = 7, expenseAccountId = cash, expenseAmount = 1, incomeAccountId = card, incomeAmount = 1)) // перевод
        assertEquals(card, dao.lastAccount(true))
        assertEquals(cash, dao.lastAccount(false))
        add(Operation(date = 8, expenseAccountId = dollars, expenseAmount = 1, categoryId = food))
        assertEquals(dollars, dao.lastAccount(true))
        dao.updateAccount(dao.account(dollars)!!.copy(archived = true))
        assertEquals(card, dao.lastAccount(true)) // архивный — пропускается
    }

    @Test fun lastExchangeIsNewestOfPairInEitherDirection() = runBlocking {
        assertNull(dao.lastExchange("USD", "UAH"))
        add(Operation(date = 5, expenseAccountId = card, expenseAmount = Money.parse("4000"), incomeAccountId = dollars, incomeAmount = Money.parse("100")))
        add(Operation(date = 5, expenseAccountId = card, expenseAmount = Money.parse("5"), incomeAccountId = cash, incomeAmount = Money.parse("5"))) // одна валюта
        assertEquals(Exchange("UAH", Money.parse("4000"), "USD", Money.parse("100")), dao.lastExchange("USD", "UAH"))
        add(Operation(date = 6, expenseAccountId = dollars, expenseAmount = Money.parse("10"), categoryId = food, baseAmount = Money.parse("415")))
        assertEquals(Exchange("USD", Money.parse("10"), "UAH", Money.parse("415")), dao.lastExchange("UAH", "USD"))
        add(Operation(date = 4, incomeAccountId = dollars, incomeAmount = Money.parse("1"), baseAmount = Money.parse("39"))) // старее
        assertEquals(Money.parse("415"), dao.lastExchange("UAH", "USD")!!.toAmount)
    }
}
