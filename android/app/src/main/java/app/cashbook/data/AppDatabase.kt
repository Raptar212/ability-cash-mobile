package app.cashbook.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cashbook.widget.BalanceWidget
import org.json.JSONArray
import org.json.JSONObject

@Database(
    entities = [Currency::class, Account::class, Category::class, Operation::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // + operations.baseAmount
        AutoMigration(from = 2, to = 3), // + accounts.color
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CashDao

    companion object {
        /** Файл рабочей базы в личной папке приложения (`databases/`). */
        const val NAME = "cashbook.db"

        @Volatile private var instance: AppDatabase? = null

        /** Закрыть базу (WAL сбрасывается в основной файл); следующий [get] откроет заново. */
        fun reset() = synchronized(this) {
            instance?.close()
            instance = null
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .addCallback(SeedCallback(context.applicationContext))
                    .build()
                    .also {
                        watchForWidget(context.applicationContext, it)
                        instance = it
                    }
            }

        /**
         * Виджет «Баланс» перерисовывается после любой записи, влияющей на остатки, и при
         * открытии базы (в том числе после восстановления копии).
         */
        private fun watchForWidget(context: Context, db: AppDatabase) {
            db.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer("operations", "accounts", "currencies") {
                    override fun onInvalidated(tables: Set<String>) = BalanceWidget.refresh(context)
                },
            )
            BalanceWidget.refresh(context)
        }
    }
}

/** Валюты как в AbilityCash (`.cash` → `Currencies`). */
val SEED_CURRENCIES = listOf(
    Currency(code = "UAH", name = "Гривна"),
    Currency(code = "USD", name = "Доллар"),
    Currency(code = "EUR", name = "Евро"),
)

/**
 * Засев при каждом открытии базы, до любого запроса приложения. Валюты — `OR IGNORE` по unique
 * `code`: повтор ничего не меняет, база без валют лечится сама. Статьи — из ассета
 * `categories.json` (стартовое дерево; своё из AbilityCash — `tools/export_categories.py`
 * в этот ассет или импорт копии из `tools/cash_to_cashbook.py`), только в пустую таблицу.
 * [context] = null — без статей (тесты валют).
 */
class SeedCallback(private val context: Context? = null) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        for (c in SEED_CURRENCIES) {
            db.execSQL(
                "INSERT OR IGNORE INTO currencies (code, name, precision) VALUES (?, ?, ?)",
                arrayOf<Any>(c.code, c.name, c.precision),
            )
        }
        context?.let { ctx ->
            seedCategories(db) { ctx.assets.open("categories.json").bufferedReader().use { it.readText() } }
        }
    }
}

/** Дерево статей `{"income": [...], "expense": [...]}`, узел — `{"name", "children"}`. [json] читается, только если таблица пуста. */
fun seedCategories(db: SupportSQLiteDatabase, json: () -> String) {
    db.query("SELECT COUNT(*) FROM categories").use { c ->
        c.moveToFirst()
        if (c.getLong(0) > 0) return
    }
    val root = JSONObject(json())
    db.beginTransaction()
    try {
        insertTree(db, root.getJSONArray("income"), null, CategoryKind.INCOME)
        insertTree(db, root.getJSONArray("expense"), null, CategoryKind.EXPENSE)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun insertTree(db: SupportSQLiteDatabase, nodes: JSONArray, parentId: Long?, kind: CategoryKind) {
    for (i in 0 until nodes.length()) {
        val node = nodes.getJSONObject(i)
        val values = ContentValues().apply {
            put("name", node.getString("name"))
            put("parentId", parentId)
            put("kind", kind.name)
            put("comment", "")
            put("deleted", 0)
        }
        val id = db.insert("categories", SQLiteDatabase.CONFLICT_ABORT, values)
        check(id != -1L) { "Не вставилась статья ${node.getString("name")}" } // откатит транзакцию целиком
        insertTree(db, node.getJSONArray("children"), id, kind)
    }
}
