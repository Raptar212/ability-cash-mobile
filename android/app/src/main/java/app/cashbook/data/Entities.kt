package app.cashbook.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CategoryKind { EXPENSE, INCOME }

enum class OperationType { EXPENSE, INCOME, TRANSFER }

@Entity(tableName = "currencies", indices = [Index("code", unique = true)])
data class Currency(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val precision: Int = 2,
) {
    init {
        require(precision in 0..4) { "Точность валюты вне 0..4: $precision" }
    }
}

@Entity(
    tableName = "accounts",
    foreignKeys = [ForeignKey(entity = Currency::class, parentColumns = ["id"], childColumns = ["currencyId"])],
    indices = [Index("currencyId")],
)
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val currencyId: Long,
    val startingBalance: Long = 0,
    val comment: String = "",
    val archived: Boolean = false,
    /** ARGB цвета в ленте; null — не выбран, цвет из палитры по id (`accountArgb`). */
    val color: Int? = null,
)

/** Статья — дерево как в AbilityCash: верхний уровень `parentId = null`, вид — расход или доход. */
@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["parentId"])],
    indices = [Index("parentId")],
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val kind: CategoryKind,
    val comment: String = "",
    val deleted: Boolean = false,
    /** Слаг иконки (`CATEGORY_ICON_PATHS`) у верхней статьи; null — не выбрана, на кружке инициалы. */
    val icon: String? = null,
)

/**
 * Операция в форме `Transactions` из `.cash`: сторона расхода (списание со счёта) и сторона
 * дохода (поступление на счёт). Суммы положительные. Только расход — расход, только доход —
 * доход, обе — перевод (обмен, если валюты счетов разные и суммы в своих валютах).
 * `date` — `LocalDate.toEpochDay()`, `createdAt` — unix-секунды. `baseAmount` — расход или
 * доход счёта в другой валюте, пересчитанный в базовую (первую по id) валюту, как в CoinKeeper;
 * null — не пересчитан (счёт в базовой валюте или старая операция).
 */
@Entity(
    tableName = "operations",
    foreignKeys = [
        ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["expenseAccountId"]),
        ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["incomeAccountId"]),
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"]),
    ],
    indices = [Index("expenseAccountId"), Index("incomeAccountId"), Index("categoryId"), Index("date", "position")],
)
data class Operation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val position: Int = 0,
    val expenseAccountId: Long? = null,
    val expenseAmount: Long? = null,
    val incomeAccountId: Long? = null,
    val incomeAmount: Long? = null,
    val categoryId: Long? = null,
    val comment: String = "",
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val baseAmount: Long? = null,
) {
    init {
        require((expenseAccountId == null) == (expenseAmount == null)) { "Сторона расхода: нужны и счёт, и сумма" }
        require((incomeAccountId == null) == (incomeAmount == null)) { "Сторона дохода: нужны и счёт, и сумма" }
        require(expenseAccountId != null || incomeAccountId != null) { "Операция без счёта" }
        require(expenseAmount == null || expenseAmount > 0) { "Сумма расхода должна быть > 0" }
        require(incomeAmount == null || incomeAmount > 0) { "Сумма дохода должна быть > 0" }
        require(expenseAccountId == null || expenseAccountId != incomeAccountId) { "Перевод на тот же счёт" }
        require(baseAmount == null || baseAmount > 0) { "Сумма в базовой валюте должна быть > 0" }
        require(baseAmount == null || expenseAccountId == null || incomeAccountId == null) { "У перевода нет суммы в базовой валюте" }
    }
}

val Operation.type: OperationType
    get() = when {
        expenseAccountId != null && incomeAccountId != null -> OperationType.TRANSFER
        expenseAccountId != null -> OperationType.EXPENSE
        else -> OperationType.INCOME
    }

/** Две суммы одной операции в разных валютах — источник курса. */
data class Exchange(val fromCode: String, val fromAmount: Long, val toCode: String, val toAmount: Long)

data class CategorySum(val categoryId: Long, val currencyCode: String, val amount: Long)

/** Сумма доходов или расходов за день [date] (epochDay) в валюте счёта. */
data class DaySum(val date: Long, val expense: Boolean, val currencyCode: String, val amount: Long)

data class AccountBalance(
    val accountId: Long,
    val name: String,
    val currencyCode: String,
    val precision: Int,
    val balance: Long,
)
