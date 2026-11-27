package app.cashbook.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Миграции 1 → 2 → 3 → 4 на файле, собранном ровно по `schemas/…/1.json` (схема, hash, user_version):
 * обновление приложения поверх старой базы и импорт старой копии.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val v1 = JSONObject(File("schemas/app.cashbook.data.AppDatabase/1.json").readText()).getJSONObject("database")

    @After fun cleanup() {
        AppDatabase.reset()
        ctx.deleteDatabase(AppDatabase.NAME)
        File(ctx.filesDir, Backup.BEFORE_IMPORT).delete()
    }

    /** База v1: счёт UAH с остатком 100 и расход 30 по статье. */
    private fun createV1(file: File) {
        file.parentFile?.mkdirs()
        Backup.discard(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = v1.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val idx = e.optJSONArray("indices") ?: continue
                for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = v1.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO currencies (id, code, name, precision) VALUES (1, 'UAH', 'Гривна', 2)")
            db.execSQL("INSERT INTO accounts (id, name, currencyId, startingBalance, comment, archived) VALUES (1, 'Нал', 1, 1000000, '', 0)")
            db.execSQL("INSERT INTO categories (id, name, parentId, kind, comment, deleted) VALUES (1, 'Продукты', NULL, 'EXPENSE', '', 0)")
            db.execSQL(
                "INSERT INTO operations (id, date, position, expenseAccountId, expenseAmount, categoryId, comment, createdAt) " +
                    "VALUES (1, 20000, 0, 1, 300000, 1, 'хлеб', 0)",
            )
            db.version = v1.getInt("version")
        }
    }

    private fun dao() = AppDatabase.get(ctx).dao()

    private fun assertV1DataMigrated() = runBlocking {
        val ops = dao().allOperations()
        assertEquals(1, ops.size)
        assertEquals(300000L, ops[0].expenseAmount)
        assertEquals("хлеб", ops[0].comment)
        assertNull(ops[0].baseAmount) // новое поле у старых операций пустое
        assertNull(dao().account(1)!!.color) // v3: цвет не выбран — палитра по id
        assertEquals(listOf(700000L), dao().balances().first().map { it.balance })
        val category = dao().allCategories().first().single()
        assertEquals("Продукты", category.name) // засев не дублирует дерево
        assertNull(category.icon) // v4: иконка не выбрана — инициалы
    }

    /** Каждая прошлая схема (все `N.json`, кроме текущей) — в [Backup.OLD_HASHES]: новая версия не забудет прежнюю. */
    @Test fun oldHashesMatchSchemaFiles() {
        val current = AppDatabase.get(ctx).openHelper.readableDatabase.version
        val old = File("schemas/app.cashbook.data.AppDatabase").listFiles()!!
            .filter { it.nameWithoutExtension.toInt() != current }
            .map { JSONObject(it.readText()).getJSONObject("database").getString("identityHash") }
        assertTrue(old.isNotEmpty())
        assertEquals(old.toSet(), Backup.OLD_HASHES)
    }

    @Test fun appUpdateMigratesV1Database() {
        createV1(ctx.getDatabasePath(AppDatabase.NAME))
        assertV1DataMigrated()
        assertEquals(4, AppDatabase.get(ctx).openHelper.readableDatabase.version)
    }

    @Test fun v1CopyIsAcceptedAndMigratedOnRestore() {
        val current = Backup.identityHash(AppDatabase.get(ctx))
        val copy = File(ctx.cacheDir, "v1.db")
        createV1(copy)
        assertNull(Backup.check(copy, current))

        Backup.restore(ctx, copy)

        assertV1DataMigrated()
        assertEquals(current, Backup.identityHash(AppDatabase.get(ctx)))
    }
}
