package app.cashbook.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Базовая валюта — первая по id (UAH засевается первой). */
private const val BASE_CODE = "(SELECT code FROM currencies ORDER BY id LIMIT 1)"
/** Валюта и сумма дохода/расхода для отчётов: пересчитанная в базовую, если есть. */
private const val CODE = "CASE WHEN o.baseAmount IS NULL THEN c.code ELSE $BASE_CODE END"
private const val AMOUNT = "COALESCE(o.baseAmount, o.expenseAmount, o.incomeAmount)"

@Dao
interface CashDao {
    @Insert suspend fun insertCurrency(currency: Currency): Long
    /** В порядке засева (как в `.cash`): первая — валюта по умолчанию для нового счёта. */
    @Query("SELECT * FROM currencies ORDER BY id") fun currencies(): Flow<List<Currency>>

    @Insert suspend fun insertAccount(account: Account): Long
    /** Счёт не удаляется (на него ссылаются операции) — только `archived = true`. */
    @Update suspend fun updateAccount(account: Account)
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: Long): Account?
    @Query("SELECT * FROM accounts WHERE archived = 1 ORDER BY name") fun archivedAccounts(): Flow<List<Account>>

    /** Сколько операций ссылается на статьи [ids] — статью с операциями не удаляем. */
    @Query("SELECT COUNT(*) FROM operations WHERE categoryId IN (:ids)") suspend fun countOperations(ids: List<Long>): Int

    /** Пометить статьи [ids] удалёнными (статья и все её подстатьи) одной транзакцией. */
    @Query("UPDATE categories SET deleted = 1 WHERE id IN (:ids)") suspend fun deleteCategories(ids: List<Long>)

    @Insert suspend fun insertCategory(category: Category): Long
    @Update suspend fun updateCategory(category: Category)
    /** Все уровни дерева одного вида, в порядке `.cash` (по id). */
    @Query("SELECT * FROM categories WHERE deleted = 0 AND kind = :kind ORDER BY id")
    fun categories(kind: CategoryKind): Flow<List<Category>>

    /** Все статьи обоих видов, в порядке `.cash`; дерево строится по `parentId`. */
    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY id")
    fun allCategories(): Flow<List<Category>>

    /**
     * Суммы доходов и расходов по статьям за [from]..[to] (epochDay), по валюте счёта, а
     * пересчитанные (`baseAmount`) — в базовой валюте. Переводы (обе стороны) не считаются.
     */
    @Query(
        """
        SELECT o.categoryId AS categoryId, $CODE AS currencyCode, SUM($AMOUNT) AS amount
        FROM operations o
        JOIN accounts a ON a.id = COALESCE(o.expenseAccountId, o.incomeAccountId)
        JOIN currencies c ON c.id = a.currencyId
        WHERE o.categoryId IS NOT NULL
            AND (o.expenseAccountId IS NULL OR o.incomeAccountId IS NULL)
            AND o.date BETWEEN :from AND :to
        GROUP BY o.categoryId, currencyCode
        """,
    )
    fun categorySums(from: Long, to: Long): Flow<List<CategorySum>>

    /** Суммы доходов и расходов по дням за [from]..[to], валюты — как в [categorySums]; переводы не считаются. */
    @Query(
        """
        SELECT o.date AS date, (o.expenseAccountId IS NOT NULL) AS expense, $CODE AS currencyCode, SUM($AMOUNT) AS amount
        FROM operations o
        JOIN accounts a ON a.id = COALESCE(o.expenseAccountId, o.incomeAccountId)
        JOIN currencies c ON c.id = a.currencyId
        WHERE o.categoryId IS NOT NULL
            AND (o.expenseAccountId IS NULL OR o.incomeAccountId IS NULL)
            AND o.date BETWEEN :from AND :to
        GROUP BY o.date, expense, currencyCode
        """,
    )
    fun daySums(from: Long, to: Long): Flow<List<DaySum>>

    /**
     * Последняя операция с суммами в валютах [a] и [b] (в любом порядке) — обмен между счетами
     * или расход/доход с `baseAmount`. Источник курса для окна ввода.
     */
    @Query(
        """
        SELECT fromCode, fromAmount, toCode, toAmount FROM (
            SELECT o.date, o.position, o.id, fc.code AS fromCode, o.expenseAmount AS fromAmount,
                tc.code AS toCode, o.incomeAmount AS toAmount
            FROM operations o
            JOIN accounts fa ON fa.id = o.expenseAccountId JOIN currencies fc ON fc.id = fa.currencyId
            JOIN accounts ta ON ta.id = o.incomeAccountId JOIN currencies tc ON tc.id = ta.currencyId
            WHERE fc.id != tc.id
            UNION ALL
            SELECT o.date, o.position, o.id, c.code, COALESCE(o.expenseAmount, o.incomeAmount), $BASE_CODE, o.baseAmount
            FROM operations o
            JOIN accounts a ON a.id = COALESCE(o.expenseAccountId, o.incomeAccountId)
            JOIN currencies c ON c.id = a.currencyId
            WHERE o.baseAmount IS NOT NULL
        )
        WHERE (fromCode = :a AND toCode = :b) OR (fromCode = :b AND toCode = :a)
        ORDER BY date DESC, position DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun lastExchange(a: String, b: String): Exchange?

    /**
     * Активный счёт последнего расхода ([expense]) или дохода (не перевода) — для ввода по тапу
     * на статью; null — таких операций ещё нет.
     */
    @Query(
        """
        SELECT a.id FROM operations o
        JOIN accounts a ON a.id = CASE WHEN :expense THEN o.expenseAccountId ELSE o.incomeAccountId END
        WHERE a.archived = 0 AND (o.expenseAccountId IS NULL OR o.incomeAccountId IS NULL)
        ORDER BY o.date DESC, o.position DESC, o.id DESC
        LIMIT 1
        """,
    )
    suspend fun lastAccount(expense: Boolean): Long?

    /** `position` новой операции дня [date]: max + 1 (как AbilityCash). */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM operations WHERE date = :date")
    suspend fun nextPosition(date: Long): Int

    @Insert suspend fun insertOperation(operation: Operation): Long

    /** Вставка в конец дня одной транзакцией — `position` без гонки двух сохранений. */
    @Transaction
    suspend fun insertAtEndOfDay(operation: Operation): Long =
        insertOperation(operation.copy(position = nextPosition(operation.date)))
    @Update suspend fun updateOperation(operation: Operation)
    @Delete suspend fun deleteOperation(operation: Operation)
    @Query("SELECT * FROM operations WHERE id = :id") suspend fun operation(id: Long): Operation?

    /** Все операции, старые сверху — для экспорта CSV. */
    @Query("SELECT * FROM operations ORDER BY date, position, id") suspend fun allOperations(): List<Operation>

    /** Правка [old] на [new]: id и `createdAt` прежние; тот же день — та же позиция, другой — в конец дня. */
    @Transaction
    suspend fun saveEdit(old: Operation, new: Operation) = updateOperation(
        new.copy(
            id = old.id,
            createdAt = old.createdAt,
            position = if (new.date == old.date) old.position else nextPosition(new.date),
        ),
    )

    /** Счета с валютой, включая архивные, — для правки операции (остаток не считается: 0). */
    @Query(
        """
        SELECT a.id AS accountId, a.name AS name, c.code AS currencyCode, c.precision AS precision, 0 AS balance
        FROM accounts a JOIN currencies c ON c.id = a.currencyId
        """,
    )
    fun accountsWithArchived(): Flow<List<AccountBalance>>

    /** Все счета, включая архивные, — для ленты операций. */
    @Query("SELECT * FROM accounts") fun allAccounts(): Flow<List<Account>>

    /** Операции за [from]..[to] (epochDay включительно), новые сверху. */
    @Query("SELECT * FROM operations WHERE date BETWEEN :from AND :to ORDER BY date DESC, position DESC, id DESC")
    fun operations(from: Long, to: Long): Flow<List<Operation>>

    /** Остаток = начальный + поступления − списания; считается, не хранится. */
    @Query(
        """
        SELECT a.id AS accountId, a.name AS name, c.code AS currencyCode, c.precision AS precision,
            a.startingBalance
            + COALESCE((SELECT SUM(o.incomeAmount) FROM operations o WHERE o.incomeAccountId = a.id), 0)
            - COALESCE((SELECT SUM(o.expenseAmount) FROM operations o WHERE o.expenseAccountId = a.id), 0)
            AS balance
        FROM accounts a JOIN currencies c ON c.id = a.currencyId
        WHERE a.archived = 0
        ORDER BY a.name
        """,
    )
    fun balances(): Flow<List<AccountBalance>>
}
