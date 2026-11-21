package app.cashbook.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Копия базы на настоящем файле `databases/cashbook.db` (через [AppDatabase.get]). */
@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @After fun cleanup() {
        AppDatabase.reset()
        ctx.deleteDatabase(AppDatabase.NAME)
        File(ctx.filesDir, Backup.BEFORE_IMPORT).delete()
    }

    private fun dao() = AppDatabase.get(ctx).dao()
    private fun hash() = Backup.identityHash(AppDatabase.get(ctx))
    private fun names() = runBlocking { dao().accounts().first().map { it.name } }

    private fun addAccount(name: String) = runBlocking {
        val uah = dao().currencies().first().first().id
        dao().insertAccount(Account(name = name, currencyId = uah))
    }

    private fun exportTo(name: String): File {
        val f = File(ctx.cacheDir, name)
        Backup.export(ctx, AppDatabase.get(ctx)) { f.outputStream() }
        return f
    }

    @Test fun exportedCopyPassesCheck() {
        addAccount("A")
        assertNull(Backup.check(exportTo("exp.db"), hash()))
    }

    @Test fun rejectsEmptyGarbageForeignAndOtherVersion() {
        val h = hash()
        assertEquals("Файл пустой", Backup.check(File(ctx.cacheDir, "empty.db").apply { writeBytes(ByteArray(0)) }, h))
        assertEquals("Это не копия базы Cashbook", Backup.check(File(ctx.cacheDir, "junk.db").apply { writeText("hello") }, h))

        val foreign = File(ctx.cacheDir, "foreign.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use { it.execSQL("CREATE TABLE t(x)") }
        assertEquals("Это не копия базы Cashbook", Backup.check(foreign, h))

        val other = exportTo("other.db")
        SQLiteDatabase.openDatabase(other.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE room_master_table SET identity_hash = 'другая схема'")
        }
        assertEquals("Копия от другой версии приложения", Backup.check(other, h))
    }

    @Test fun restoreReplacesDataAndKeepsSafetyCopy() {
        addAccount("A")
        val copy = exportTo("copy.db")
        addAccount("B")
        assertEquals(setOf("A", "B"), names().toSet())

        Backup.restore(ctx, copy)

        assertEquals(listOf("A"), names()) // база открыта заново — данные из копии
        val safety = File(ctx.filesDir, Backup.BEFORE_IMPORT)
        val saved = SQLiteDatabase.openDatabase(safety.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM accounts ORDER BY name", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
        }
        assertEquals(listOf("A", "B"), saved) // прежние данные можно вернуть
        assertFalse(File(ctx.getDatabasePath(AppDatabase.NAME).path + ".import").exists()) // временный убран
        assertFalse(copy.exists()) // файл импорта удалён после замены
    }

    @Test fun repeatedRestoreIsNoOpAndKeepsSafetyCopy() {
        addAccount("A")
        val copy = exportTo("copy2.db")
        addAccount("B")
        assertTrue(Backup.restore(ctx, copy))
        // Второй вызов (пересоздание экрана): файла уже нет — ничего не трогает.
        assertFalse(Backup.restore(ctx, copy))
        assertEquals(listOf("A"), names())
        val safety = File(ctx.filesDir, Backup.BEFORE_IMPORT)
        val saved = SQLiteDatabase.openDatabase(safety.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM accounts", null).use { it.moveToFirst(); it.getInt(0) }
        }
        assertEquals(2, saved) // страховка не перезаписана импортированными данными
    }

    @Test fun failedRestoreLeavesWorkingDatabaseIntact() {
        addAccount("A")
        val dir = File(ctx.cacheDir, "notafile.db").apply { deleteRecursively(); mkdirs() } // чтение упадёт
        assertThrows(java.io.IOException::class.java) { Backup.restore(ctx, dir) }
        assertEquals(listOf("A"), names()) // база не тронута и открывается
        assertFalse(File(ctx.getDatabasePath(AppDatabase.NAME).path + ".import").exists())
        dir.delete()
    }

    @Test fun exportOpensTargetOnceAfterCheckpoint() {
        // Цель открывается лениво, после чекпойнта (ветку «база занята» в Robolectric не вызвать).
        var opened = 0
        Backup.export(ctx, AppDatabase.get(ctx)) { opened++; java.io.ByteArrayOutputStream() }
        assertEquals(1, opened)
    }
}
