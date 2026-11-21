package app.cashbook.ui

import app.cashbook.data.Category
import app.cashbook.data.CategorySum
import app.cashbook.data.Exchange
import app.cashbook.data.Money
import app.cashbook.data.Operation
import app.cashbook.data.OperationType
import app.cashbook.data.type
import java.math.BigDecimal
import java.math.RoundingMode

/** Кружок главного экрана: статья дохода, счёт или статья расхода. */
enum class NodeKind { INCOME, ACCOUNT, EXPENSE }

data class Node(val kind: NodeKind, val id: Long)

/** Какую операцию даёт перетаскивание [from] → [to]; null — пара ничего не создаёт. */
fun dropType(from: Node, to: Node): OperationType? = when {
    from.kind == NodeKind.INCOME && to.kind == NodeKind.ACCOUNT -> OperationType.INCOME
    from.kind == NodeKind.ACCOUNT && to.kind == NodeKind.EXPENSE -> OperationType.EXPENSE
    from.kind == NodeKind.ACCOUNT && to.kind == NodeKind.ACCOUNT && from.id != to.id -> OperationType.TRANSFER
    else -> null
}

/** «INCOME:3>ACCOUNT:5» — для rememberSaveable (переживает поворот). */
fun encodeDrop(from: Node, to: Node) = "${from.kind}:${from.id}>${to.kind}:${to.id}"

fun decodeDrop(s: String): Pair<Node, Node> {
    val (a, b) = s.split('>').map { part ->
        val (kind, id) = part.split(':')
        Node(NodeKind.valueOf(kind), id.toLong())
    }
    return a to b
}

/**
 * Операция из перетаскивания. [amount] — списано (или получено для дохода), [received] —
 * зачислено при обмене (null — та же сумма), у расхода/дохода — сумма в базовой валюте
 * (`baseAmount`, null — нет). [categoryId] null — статья самого кружка.
 */
fun buildOperation(
    from: Node,
    to: Node,
    amount: Long,
    received: Long?,
    categoryId: Long?,
    date: Long,
    position: Int,
    comment: String,
): Operation = when (dropType(from, to)) {
    OperationType.INCOME -> Operation(
        date = date, position = position, incomeAccountId = to.id, incomeAmount = amount,
        categoryId = categoryId ?: from.id, comment = comment, baseAmount = received,
    )
    OperationType.EXPENSE -> Operation(
        date = date, position = position, expenseAccountId = from.id, expenseAmount = amount,
        categoryId = categoryId ?: to.id, comment = comment, baseAmount = received,
    )
    OperationType.TRANSFER -> Operation(
        date = date, position = position, expenseAccountId = from.id, expenseAmount = amount,
        incomeAccountId = to.id, incomeAmount = received ?: amount, comment = comment,
    )
    null -> throw IllegalArgumentException("Перетаскивание $from → $to не создаёт операцию")
}

/**
 * Курс между суммой «из» и суммой «в»: `perFrom` — «1 из = value в», иначе «1 в = value из».
 * Сторона выбирается так, чтобы value ≥ 1 (41,5 ₴ за $, а не 0,0241 $ за ₴). value ×10000.
 */
data class Rate(val perFrom: Boolean, val value: Long)

/** Курс из двух сумм (обе > 0), 4 знака после запятой. */
fun rateOf(from: Long, to: Long): Rate {
    val perFrom = to >= from
    val (num, den) = if (perFrom) to to from else from to to
    return Rate(perFrom, BigDecimal(num).divide(BigDecimal(den), 4, RoundingMode.HALF_UP).movePointRight(4).toLong())
}

/** Курс для пары [fromCode] → [toCode] из прошлой операции [e] (в любом направлении). */
fun rateFrom(e: Exchange, fromCode: String): Rate =
    if (e.fromCode == fromCode) rateOf(e.fromAmount, e.toAmount) else rateOf(e.toAmount, e.fromAmount)

/** Сумма «в» по сумме «из» [amount] и курсу, с округлением до [precision]; null — курс 0 или переполнение. */
fun convert(amount: Long, rate: Rate, precision: Int): Long? {
    if (rate.value <= 0) return null
    val a = BigDecimal.valueOf(amount, 4)
    val r = BigDecimal.valueOf(rate.value, 4)
    val out = if (rate.perFrom) a.multiply(r) else a.divide(r, precision, RoundingMode.HALF_UP)
    return try {
        out.setScale(precision, RoundingMode.HALF_UP).movePointRight(4).longValueExact()
    } catch (e: ArithmeticException) {
        null
    }
}

/** Потолок суммы операции: остатки и суммы за месяц остаются в диапазоне `Money.format`. */
val MAX_OPERATION = Money.parse("1000000000000")

/** Ошибка суммы для показа; null — сумма годится. */
fun amountError(amount: Long?): String? = when {
    amount == null -> "Неверная сумма"
    amount <= 0 -> "Сумма должна быть больше нуля"
    amount > MAX_OPERATION -> "Слишком большая сумма"
    else -> null
}

/**
 * Свёртка сумм по статьям до верхнего уровня: id верхней статьи → (код валюты → сумма).
 * Подстатьи любой глубины складываются в своего верхнего предка.
 */
fun rollUp(categories: List<Category>, sums: List<CategorySum>): Map<Long, Map<String, Long>> {
    val byId = categories.associateBy { it.id }
    val out = mutableMapOf<Long, MutableMap<String, Long>>()
    for (s in sums) {
        val m = out.getOrPut(topCategory(byId, s.categoryId)) { mutableMapOf() }
        m[s.currencyCode] = (m[s.currencyCode] ?: 0L) + s.amount
    }
    return out
}

/** Верхний предок статьи [id] (сама, если верхняя или неизвестна). */
fun topCategory(byId: Map<Long, Category>, id: Long): Long {
    var c = byId[id] ?: return id
    repeat(byId.size) { c = byId[c.parentId ?: return c.id] ?: return c.id }
    return c.id // цикл в parentId — не зависаем
}

/** Перетаскивание, которым создаётся [op], — для окна правки; null — доход/расход без статьи. */
fun dropOf(op: Operation, categories: List<Category>): Pair<Node, Node>? {
    val top = op.categoryId?.let { topCategory(categories.associateBy { c -> c.id }, it) }
    return when (op.type) {
        OperationType.INCOME -> top?.let { Node(NodeKind.INCOME, it) to Node(NodeKind.ACCOUNT, op.incomeAccountId!!) }
        OperationType.EXPENSE -> top?.let { Node(NodeKind.ACCOUNT, op.expenseAccountId!!) to Node(NodeKind.EXPENSE, it) }
        OperationType.TRANSFER -> Node(NodeKind.ACCOUNT, op.expenseAccountId!!) to Node(NodeKind.ACCOUNT, op.incomeAccountId!!)
    }
}

/** Сумма как ввод калькулятора: 125000 → «12,5», без пробелов и лишних нулей. */
fun toExpr(amount: Long): String = Money.format(amount, 4).replace(" ", "").trimEnd('0').trimEnd(',')

/** День ленты: операции в порядке запроса и расходы дня по коду валюты. */
data class Day(val date: Long, val operations: List<Operation>, val expenses: Map<String, Long>)

/** Группировка по дням с сохранением порядка [ops]; [currencyOf] — код валюты по id счёта. */
fun byDay(ops: List<Operation>, currencyOf: Map<Long, String>): List<Day> =
    ops.groupBy { it.date }.map { (date, list) ->
        val expenses = list.filter { it.type == OperationType.EXPENSE }
            .groupBy({ currencyOf[it.expenseAccountId] ?: "?" }, { it.expenseAmount!! })
            .mapValues { it.value.sum() }
        Day(date, list, expenses)
    }

/** Потомки статьи в порядке дерева (родитель, затем его дети), с глубиной для отступа. */
fun descendants(categories: List<Category>, rootId: Long): List<Pair<Category, Int>> {
    val children = categories.groupBy { it.parentId }
    val out = mutableListOf<Pair<Category, Int>>()
    fun walk(id: Long, depth: Int) {
        for (c in children[id].orEmpty().sortedBy { it.id }) {
            out += c to depth
            walk(c.id, depth + 1)
        }
    }
    walk(rootId, 0)
    return out
}

/** Калькулятор суммы: выражение из цифр, «,» и + − × ÷; арифметика в ×10000 без Float. */
object Calc {
    const val OPS = "+−×÷"
    private const val MAX_DIGITS = 13

    /** Строка выражения после нажатия [key] (цифра, ',', оператор или '⌫'). */
    fun press(expr: String, key: Char): String {
        val cur = expr.takeLastWhile { it !in OPS }
        return when {
            key == '⌫' -> expr.dropLast(1)
            key in OPS -> when {
                expr.isEmpty() -> expr
                expr.last() in OPS -> expr.dropLast(1) + key
                else -> expr + key
            }
            key == ',' -> when {
                ',' in cur -> expr
                cur.isEmpty() -> "${expr}0,"
                else -> "$expr,"
            }
            key in '0'..'9' -> when {
                ',' in cur && cur.substringAfter(',').length >= 4 -> expr
                cur.count { it != ',' } >= MAX_DIGITS -> expr
                cur == "0" -> expr.dropLast(1) + key
                else -> expr + key
            }
            else -> expr
        }
    }

    /** Значение выражения, округлённое до [precision] знаков; null — пусто, ÷0 или переполнение. */
    fun eval(expr: String, precision: Int = 2): Long? = try {
        val e = expr.trimEnd { it in OPS }
        if (e.isEmpty()) {
            null
        } else {
            val nums = mutableListOf<Long>()
            val ops = mutableListOf<Char>()
            var start = 0
            for ((i, c) in e.withIndex()) {
                if (c in OPS) {
                    nums += Money.parse(e.substring(start, i))
                    ops += c
                    start = i + 1
                }
            }
            nums += Money.parse(e.substring(start))
            // Сначала × и ÷, потом + и −.
            val terms = mutableListOf(nums[0])
            val addOps = mutableListOf<Char>()
            for (i in ops.indices) {
                val b = nums[i + 1]
                when (ops[i]) {
                    '×' -> terms[terms.lastIndex] = roundDiv(Math.multiplyExact(terms.last(), b), Money.SCALE)
                    '÷' -> {
                        if (b == 0L) throw ArithmeticException("÷0")
                        terms[terms.lastIndex] = roundDiv(Math.multiplyExact(terms.last(), Money.SCALE), b)
                    }
                    else -> {
                        addOps += ops[i]
                        terms += b
                    }
                }
            }
            var acc = terms[0]
            for (i in addOps.indices) {
                acc = if (addOps[i] == '+') Math.addExact(acc, terms[i + 1]) else Math.subtractExact(acc, terms[i + 1])
            }
            roundDiv(acc, Money.pow10(4 - precision)) * Money.pow10(4 - precision)
        }
    } catch (e: ArithmeticException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** n / d с округлением половины от нуля. */
    private fun roundDiv(n: Long, d: Long): Long {
        val q = n / d
        val r = n % d
        if (r == 0L) return q
        val twice = Math.multiplyExact(Math.abs(r), 2L)
        val sign = if ((n < 0) != (d < 0)) -1 else 1
        return if (twice >= Math.abs(d)) q + sign else q
    }
}
