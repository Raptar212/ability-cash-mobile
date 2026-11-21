package app.cashbook.ui

import app.cashbook.data.Account
import app.cashbook.data.Category
import app.cashbook.data.Currency
import app.cashbook.data.Operation
import app.cashbook.data.OperationType
import app.cashbook.data.type
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Операции в формате экспорта CoinKeeper (секция операций): BOM, `sep=,`, каждое поле в
 * кавычках, старые сверху. Доход в CoinKeeper — перевод из источника дохода на счёт.
 */
object CoinKeeperCsv {
    private val HEADER = listOf(
        "Данные", "Тип", "Из", "В", "Метки", "Сумма", "Валюта", "Сумма в др.валюте", "Др.валюта", "Повторение", "Заметка",
    )
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun write(ops: List<Operation>, accounts: List<Account>, currencies: List<Currency>, categories: List<Category>): String {
        val accById = accounts.associateBy { it.id }
        val codeById = currencies.associate { it.id to it.code }
        val catById = categories.associateBy { it.id }
        val baseCode = currencies.minByOrNull { it.id }?.code.orEmpty()
        fun account(id: Long?) = accById[id]?.name.orEmpty()
        fun code(id: Long?) = codeById[accById[id]?.currencyId].orEmpty()
        fun top(id: Long?) = id?.let { catById[topCategory(catById, it)]?.name }.orEmpty()
        fun sub(id: Long?) = catById[id]?.takeIf { it.parentId != null }?.name.orEmpty()

        val out = StringBuilder("\uFEFFsep=,\n")
        line(out, HEADER)
        for (op in ops.sortedWith(compareBy({ it.date }, { it.position }, { it.id }))) {
            val date = LocalDate.ofEpochDay(op.date).format(DATE)
            // [тип, из, в, метки, сумма, валюта, сумма2, валюта2]
            val f = when (op.type) {
                // Пересчитанная сумма — в статье (базовая валюта), иначе статья в валюте счёта.
                OperationType.EXPENSE -> {
                    val a = toExpr(op.expenseAmount!!)
                    val c = code(op.expenseAccountId)
                    val (b, bc) = op.baseAmount?.let { toExpr(it) to baseCode } ?: (a to c)
                    listOf("Расход", account(op.expenseAccountId), top(op.categoryId), sub(op.categoryId), a, c, b, bc)
                }
                OperationType.INCOME -> {
                    val a = toExpr(op.incomeAmount!!)
                    val c = code(op.incomeAccountId)
                    val (b, bc) = op.baseAmount?.let { toExpr(it) to baseCode } ?: (a to c)
                    listOf("Перевод", top(op.categoryId), account(op.incomeAccountId), sub(op.categoryId), b, bc, a, c)
                }
                OperationType.TRANSFER -> listOf(
                    "Перевод", account(op.expenseAccountId), account(op.incomeAccountId), "",
                    toExpr(op.expenseAmount!!), code(op.expenseAccountId),
                    toExpr(op.incomeAmount!!), code(op.incomeAccountId),
                )
            }
            line(out, listOf(date) + f + listOf("Нет", op.comment))
        }
        return out.toString()
    }

    private fun line(out: StringBuilder, fields: List<String>) {
        fields.joinTo(out, ",", postfix = "\n") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }
}
