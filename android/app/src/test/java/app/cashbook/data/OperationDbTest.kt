package app.cashbook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Запросы пакета без смены схемы: подсказки комментариев, лента всех операций, возврат удалённой. */
@RunWith(RobolectricTestRunner::class)
class OperationDbTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CashDao
    private var account = 0L
    private var food = 0L
    private var leisure = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback())
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        val uah = dao.currencies().first().first().id
        account = dao.insertAccount(Account(name = "Карта", currencyId = uah))
        food = dao.insertCategory(Category(name = "Еда", kind = CategoryKind.EXPENSE))
        leisure = dao.insertCategory(Category(name = "Досуг", kind = CategoryKind.EXPENSE))
    }

    @After fun tearDown() = db.close()

    private fun spend(date: Long, category: Long, comment: String) = runBlocking {
        dao.insertAtEndOfDay(
            Operation(date = date, expenseAccountId = account, expenseAmount = Money.parse("10"), categoryId = category, comment = comment),
        )
    }

    @Test fun recentCommentsDistinctNewestFirstMax5() = runBlocking {
        spend(1, food, "Хлеб")
        spend(2, food, "Молоко")
        spend(3, food, "")          // пустые не нужны
        spend(4, food, "  ")
        spend(5, leisure, "Кино")   // чужая статья
        spend(6, food, "Хлеб")      // повтор — один раз, и «свежее» Молока
        assertEquals(listOf("Хлеб", "Молоко"), dao.recentComments(food))
        assertEquals(listOf("Кино"), dao.recentComments(leisure))
        for (i in 10..20) spend(i.toLong(), food, "c$i")
        assertEquals(listOf("c20", "c19", "c18", "c17", "c16"), dao.recentComments(food))
        assertEquals(emptyList<String>(), dao.recentComments(999))
    }

    @Test fun allOperationsFlowNewestFirstAcrossMonths() = runBlocking {
        val a = spend(10, food, "a")
        val b = spend(400, food, "b")
        val c = spend(400, food, "c")
        assertEquals(listOf(c, b, a), dao.allOperationsFlow().first().map { it.id })
    }

    @Test fun deletedOperationReturnsWithSameIdAndPosition() = runBlocking {
        spend(7, food, "первая")
        val id = spend(7, food, "вторая")
        val op = dao.operation(id)!!
        dao.deleteOperation(op)
        assertNull(dao.operation(id))
        dao.insertOperation(op) // «Отменить»
        assertEquals(op, dao.operation(id))
        assertEquals(listOf("вторая", "первая"), dao.operations(7, 7).first().map { it.comment })
    }
}
