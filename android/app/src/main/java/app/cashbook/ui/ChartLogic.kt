package app.cashbook.ui

import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import app.cashbook.data.CategorySum
import app.cashbook.data.DaySum
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** Доля круга: статья ([id] null — «Другое»), сумма, есть ли у статьи подстатьи для раскрытия. */
data class Slice(val id: Long?, val name: String, val amount: Long, val hasChildren: Boolean)

/** Столбики месяца: расходы и доходы в одной валюте. */
data class MonthBar(val month: YearMonth, val expense: Long, val income: Long)

const val NO_SUBCATEGORY = "Без подстатьи"
const val OTHER = "Другое"

/**
 * Суммы вида [kind] в валюте [code] по статьям, по убыванию: [parent] null — свёрнуты до
 * верхних статей, иначе до прямых подстатей [parent] (операции на ней самой — «Без подстатьи»).
 */
fun slices(categories: List<Category>, sums: List<CategorySum>, kind: CategoryKind, code: String, parent: Long?): List<Slice> {
    val byId = categories.associateBy { it.id }
    val withChildren = categories.mapNotNull { it.parentId }.toSet()
    val acc = LinkedHashMap<Long, Long>()
    for (s in sums) {
        if (s.currencyCode != code || byId[s.categoryId]?.kind != kind) continue
        val key = if (parent == null) topCategory(byId, s.categoryId) else childUnder(byId, s.categoryId, parent) ?: continue
        acc[key] = (acc[key] ?: 0L) + s.amount
    }
    return acc.filter { it.value > 0 }
        .map { (id, v) ->
            if (id == parent) Slice(id, NO_SUBCATEGORY, v, false) else Slice(id, byId[id]?.name ?: "?", v, id in withChildren)
        }
        .sortedByDescending { it.amount }
}

/** Предок статьи [id] — прямой потомок [parent]; сама [parent] — [parent]; не под ней — null. */
fun childUnder(byId: Map<Long, Category>, id: Long, parent: Long): Long? {
    var c = byId[id] ?: return null
    repeat(byId.size + 1) {
        if (c.id == parent) return parent
        if (c.parentId == parent) return c.id
        c = byId[c.parentId ?: return null] ?: return null
    }
    return null // цикл в parentId
}

/** Не больше [max] долей: крупнейшие [max]−1 и «Другое» из остальных. */
fun fold(slices: List<Slice>, max: Int = 6): List<Slice> =
    if (slices.size <= max) slices else slices.take(max - 1) + Slice(null, OTHER, slices.drop(max - 1).sumOf { it.amount }, false)

/** Процент [amount] от [total], округление половины вверх, без Float. */
fun percent(amount: Long, total: Long): Int =
    if (total <= 0) 0 else BigDecimal(amount).multiply(BigDecimal(100)).divide(BigDecimal(total), 0, RoundingMode.HALF_UP).toInt()

/** 12 месяцев до [end] включительно: расходы и доходы в валюте [code]; пустой месяц — нули. */
fun monthly(rows: List<DaySum>, end: YearMonth, code: String): List<MonthBar> {
    val expense = HashMap<YearMonth, Long>()
    val income = HashMap<YearMonth, Long>()
    for (r in rows) {
        if (r.currencyCode != code) continue
        val m = YearMonth.from(LocalDate.ofEpochDay(r.date))
        val target = if (r.expense) expense else income
        target[m] = (target[m] ?: 0L) + r.amount
    }
    return (11 downTo 0).map { end.minusMonths(it.toLong()) }.map { MonthBar(it, expense[it] ?: 0L, income[it] ?: 0L) }
}
