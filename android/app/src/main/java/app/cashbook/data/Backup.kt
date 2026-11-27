package app.cashbook.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Копия базы одним файлом: экспорт в выбранное место и восстановление из него.
 * Рабочая база всегда остаётся в `databases/` приложения.
 */
object Backup {
    /** Страховочная копия рабочей базы перед импортом — для отката. */
    const val BEFORE_IMPORT = "before-import.db"

    /**
     * Hash схем прошлых версий (`schemas/…/N.json`): такие копии тоже принимаются — Room при
     * открытии доводит их миграцией до текущей. Новая версия схемы → добавить сюда прежний hash.
     */
    val OLD_HASHES = setOf(
        "4b7a3e4e33d5dc75326ef59553f87f20", // v1
        "9eedd01f20d3d1abb1ce7f9a12214d13", // v2
        "563b7567e5c8e043aad00aad895ddec7", // v3
    )

    /** Hash схемы Room — копия подходит, если он совпадает с рабочей базой или есть в [OLD_HASHES]. */
    fun identityHash(db: AppDatabase): String =
        db.query("SELECT identity_hash FROM room_master_table LIMIT 1", null).use { c ->
            check(c.moveToFirst()) { "Нет room_master_table" }
            c.getString(0)
        }

    /**
     * Записать полную базу: сначала WAL сбрасывается в основной файл, и только потом
     * открывается [open] — выбранный файл не затирается, если база занята.
     */
    fun export(context: Context, db: AppDatabase, open: () -> OutputStream) {
        db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
            check(c.moveToFirst() && c.getInt(0) == 0) { "База занята, повторите экспорт" }
        }
        open().use { out -> context.getDatabasePath(AppDatabase.NAME).inputStream().use { it.copyTo(out) } }
    }

    /** Убрать файл импорта и служебные файлы SQLite, созданные проверкой [check]. */
    fun discard(file: File) {
        for (f in listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))) f.delete()
    }

    /** Скопировать выбранный файл во временный [dest] (проверка и замена идут по нему). */
    fun receive(input: InputStream, dest: File) {
        discard(dest) // без старых -wal/-shm от прошлой проверки
        dest.outputStream().use { input.copyTo(it) }
    }

    /** Ошибка для показа, если [file] — не копия базы этого приложения; null — годится. */
    fun check(file: File, expectedHash: String): String? {
        if (file.length() == 0L) return "Файл пустой"
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val ok = db.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                if (!ok) return "Файл повреждён"
                val hash = try {
                    db.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null)
                        .use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (e: SQLiteException) {
                    null
                }
                when (hash) {
                    null -> "Это не копия базы Cashbook"
                    expectedHash, in OLD_HASHES -> null
                    else -> "Копия от другой версии приложения"
                }
            }
        } catch (e: SQLiteException) {
            "Это не копия базы Cashbook"
        }
    }

    /**
     * Заменить рабочую базу проверенным [file]. Текущая база закрывается и сохраняется в
     * `files/before-import.db`. После вызова базу открывают заново через [AppDatabase.get].
     *
     * Замена атомарная: копия пишется во временный файл рядом с базой и встаёт на место одним
     * `move` последним шагом. Сбой на любом шаге до него — рабочая база не изменена.
     *
     * Только по одному (`@Synchronized`): пересоздание экрана во время восстановления не
     * запустит второе параллельно. Возвращает false, если файла импорта нет — нечего делать
     * (например, восстановление уже прошло).
     */
    @Synchronized
    fun restore(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        val staged = File(dbFile.path + ".import")
        val safety = File(context.filesDir, BEFORE_IMPORT)
        val safetyTmp = File(safety.path + ".tmp")
        try {
            file.inputStream().use { input ->
                staged.outputStream().use { out ->
                    input.copyTo(out)
                    out.fd.sync() // на диске до замены
                }
            }
            AppDatabase.reset() // закрытие сбрасывает WAL в основной файл
            if (dbFile.exists()) {
                // Прежняя страховочная копия заменяется только целой новой.
                dbFile.copyTo(safetyTmp, overwrite = true)
                Files.move(safetyTmp.toPath(), safety.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            // Пусто после закрытия; удаляем, чтобы чужой WAL не лёг поверх новой базы.
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            Files.move(staged.toPath(), dbFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.delete() // после move его уже нет; при сбое — убрать недописанный
            safetyTmp.delete()
        }
        discard(file)
        return true
    }
}
