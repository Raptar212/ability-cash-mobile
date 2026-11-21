package app.cashbook.data

import android.content.Context
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

/** Загрузка стартового дерева статей из реального ассета `categories.json`. */
@RunWith(RobolectricTestRunner::class)
class CategoriesSeedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: CashDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(SeedCallback(context))
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After fun tearDown() = db.close()

    private fun all(kind: CategoryKind) = runBlocking { dao.categories(kind).first() }

    @Test fun wholeTreeIsLoadedInOrder() {
        val income = all(CategoryKind.INCOME)
        assertEquals(5, income.size)
        assertEquals(31, all(CategoryKind.EXPENSE).size)
        // Верхний уровень — в порядке файла.
        assertEquals(
            listOf("Зарплата", "Подработка", "Подарки", "Проценты", "Прочие доходы"),
            income.filter { it.parentId == null }.map { it.name },
        )
    }

    @Test fun nestingIsKept() {
        val byId = all(CategoryKind.EXPENSE).associateBy { it.id }
        val bread = byId.values.single { it.name == "Хлеб" }
        val bakery = byId.getValue(bread.parentId!!)
        assertEquals("Хлебобулочные изделия", bakery.name)
        val food = byId.getValue(bakery.parentId!!)
        assertEquals("Продукты", food.name)
        assertNull(food.parentId)
    }

    @Test fun reopenDoesNotDuplicateOrOverwrite() = runBlocking {
        val first = dao.categories(CategoryKind.EXPENSE).first().first()
        dao.updateCategory(first.copy(name = "Переименовано"))
        SeedCallback(context).onOpen(db.openHelper.writableDatabase)
        val expense = dao.categories(CategoryKind.EXPENSE).first()
        assertEquals(31, expense.size)
        assertEquals("Переименовано", expense.first().name)
    }
}
