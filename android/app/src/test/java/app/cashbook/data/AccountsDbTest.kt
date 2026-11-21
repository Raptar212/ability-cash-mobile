package app.cashbook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountsDbTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CashDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback())
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After fun tearDown() = db.close()

    @Test fun seedsCurrenciesLikeAbilityCash() = runBlocking {
        val codes = dao.currencies().first().map { it.code to it.precision }
        assertEquals(listOf("UAH" to 2, "USD" to 2, "EUR" to 2), codes)
    }

    @Test fun seedIsIdempotentAndHealsEmptyBase() = runBlocking {
        val raw = db.openHelper.writableDatabase
        SeedCallback().onOpen(raw) // повторное открытие — без дублей
        assertEquals(3, dao.currencies().first().size)
        raw.execSQL("DELETE FROM currencies") // база этапа 1: валют нет
        SeedCallback().onOpen(raw)
        assertEquals(listOf("UAH", "USD", "EUR"), dao.currencies().first().map { it.code })
    }

    @Test fun editAndArchiveAccount() = runBlocking {
        val uah = dao.currencies().first().single { it.code == "UAH" }.id
        val id = dao.insertAccount(Account(name = "Кошелёк", currencyId = uah, startingBalance = Money.parse("100")))
        dao.updateAccount(dao.account(id)!!.copy(name = "Кошелёк 2", startingBalance = Money.parse("150")))
        val row = dao.balances().first().single()
        assertEquals("Кошелёк 2", row.name)
        assertEquals(Money.parse("150"), row.balance)

        dao.updateAccount(dao.account(id)!!.copy(archived = true))
        assertTrue(dao.balances().first().isEmpty())
        assertTrue(dao.accounts().first().isEmpty())
        assertTrue(dao.account(id)!!.archived)
    }
}
