package app.cashbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cashbook.data.CashDao
import app.cashbook.data.Operation
import app.cashbook.data.OperationType
import app.cashbook.data.type
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")

/** Чьи операции в ленте: все, одного счёта или одной статьи (с подстатьями). */
sealed interface OpsFilter {
    data object All : OpsFilter
    data class Account(val id: Long) : OpsFilter
    data class Category(val id: Long) : OpsFilter
    /** Все операции одного дня [date] (epochDay); месяц не переключается. */
    data class Day(val date: Long) : OpsFilter
}

/**
 * Лента операций месяца [month] по дням; нажатие на строку — [onOpen] с id операции.
 * [filter] не All — в заголовке имя счёта/статьи, месяц строкой ниже; [onAdd]/[onEdit] — «+» и «✎» справа.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(
    dao: CashDao,
    month: YearMonth,
    listState: LazyListState,
    onMonth: (YearMonth) -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    filter: OpsFilter = OpsFilter.All,
    onAdd: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onTransfer: (() -> Unit)? = null,
) {
    // Список помнит свой месяц: до загрузки нового операции прошлого не показываются ни кадра.
    val loaded by produceState<Pair<YearMonth, List<Operation>>?>(null, month) {
        dao.operations(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay()).collect { value = month to it }
    }
    val monthOps = loaded?.takeIf { it.first == month }?.second
    val accounts by remember { dao.allAccounts() }.collectAsState(initial = emptyList())
    val currencies by remember { dao.currencies() }.collectAsState(initial = emptyList())
    val categories by remember { dao.allCategories() }.collectAsState(initial = emptyList())

    val currencyById = currencies.associateBy { it.id }
    val accountById = accounts.associateBy { it.id }
    val currencyOf = accounts.associate { a -> a.id to (currencyById[a.currencyId]?.code ?: "?") }
    val precisions = currencies.associate { it.code to it.precision }
    val catById = categories.associateBy { it.id }
    // Пока статьи не загрузились, фильтр по статье не применить — ждём, как и операции.
    val ops = if (filter is OpsFilter.Category && categories.isEmpty()) null else monthOps?.filter { op ->
        when (filter) {
            OpsFilter.All -> true
            is OpsFilter.Account -> op.expenseAccountId == filter.id || op.incomeAccountId == filter.id
            is OpsFilter.Category -> op.categoryId?.let { topCategory(catById, it) == filter.id } == true
            is OpsFilter.Day -> op.date == filter.date
        }
    }
    val title = when (filter) {
        OpsFilter.All -> "Операции"
        is OpsFilter.Account -> accountById[filter.id]?.name ?: ""
        is OpsFilter.Category -> catById[filter.id]?.name ?: ""
        is OpsFilter.Day -> "Операции за день"
    }
    val days = remember(ops, currencyOf) { byDay(ops.orEmpty(), currencyOf) }

    fun go(m: YearMonth) {
        onMonth(m)
        listState.requestScrollToItem(0)
    }

    fun money(accountId: Long?, amount: Long?): String {
        val code = currencyOf[accountId] ?: "?"
        return "${fmt(amount ?: 0, precisions[code] ?: 2)} ${symbol(code)}"
    }

    fun categoryLabel(id: Long?): String {
        val c = id?.let { catById[it] } ?: return "Без статьи"
        val top = topCategory(catById, c.id)
        return if (top == c.id) c.name else "${catById[top]?.name} › ${c.name}"
    }

    fun accountName(id: Long?) = id?.let { accountById[it]?.name } ?: "?"

    // Инициалы кружков — те же, что на главном: считаются по ряду верхних статей своего вида.
    val initialsByTop = remember(categories) {
        categories.filter { it.parentId == null }.groupBy { it.kind }.values
            .flatMap { tops -> tops.map { it.id }.zip(circleInitials(tops.map { it.name })) }.toMap()
    }

    fun initials(id: Long?) = id?.let { initialsByTop[topCategory(catById, it)] } ?: "—"

    fun icon(id: Long?) = id?.let { catById[topCategory(catById, it)]?.icon }?.let(::categoryIcon)

    fun accountColor(id: Long?) = id?.let { accountById[it] }?.let { Color(accountArgb(it.id, it.color)) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        onEdit?.let { IconButton(onClick = it) { Icon(Icons.Filled.Edit, "Изменить") } }
                        onTransfer?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Перевод на другой счёт") } }
                        onAdd?.let { IconButton(onClick = it) { Icon(Icons.Filled.Add, "Добавить операцию") } }
                    },
                )
                if (filter !is OpsFilter.Day) MonthSwitcher(month, onChange = ::go)
            }
        },
    ) { padding ->
        // До загрузки список не рисуем: пустой LazyColumn сбросил бы прокрутку при возврате из правки.
        if (ops == null) return@Scaffold
        if (days.isEmpty()) {
            val hint = when (filter) {
                OpsFilter.All -> "Перетащите счёт на статью на главном экране — трата появится здесь."
                is OpsFilter.Account -> "Операций по счёту в этом месяце нет. «+» вверху — новый расход."
                is OpsFilter.Category -> "Трат по статье в этом месяце нет. «+» вверху — новый расход."
                is OpsFilter.Day -> "В этот день операций нет. Перетащите счёт на статью на главном экране."
            }
            EmptyState("Операций нет", hint, Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState) {
            for (day in days) {
                item(key = "d${day.date}") {
                    // Строка дня темнее строк операций — границы дней видны в длинном списке.
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(
                                LocalDate.ofEpochDay(day.date).format(DateTimeFormatter.ofPattern("d MMMM, EEEE", RU)),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (day.expenses.isNotEmpty()) {
                                // Минус у каждой валюты; отступ — дата и сумма не слипаются при крупном шрифте.
                                Text(
                                    multi(day.expenses.mapValues { -it.value }, precisions, compact = false),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
                items(day.operations, key = { it.id }) { op ->
                    OperationRow(op, ::categoryLabel, ::initials, ::icon, ::accountName, ::accountColor, ::money) { onOpen(op.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Пустой экран: значок, что пусто, и подсказка, что сделать. */
@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun OperationRow(
    op: Operation,
    categoryLabel: (Long?) -> String,
    initials: (Long?) -> String,
    icon: (Long?) -> ImageVector?,
    accountName: (Long?) -> String,
    accountColor: (Long?) -> Color?,
    money: (Long?, Long?) -> String,
    onClick: () -> Unit,
) {
    val type = op.type
    // Имя счёта — его цветом: в статье сразу видно, с какого счёта трата.
    fun AnnotatedString.Builder.account(id: Long?) {
        val text = accountName(id)
        accountColor(id)?.let { withStyle(SpanStyle(color = it)) { append(text) } } ?: append(text)
    }
    val title = if (type == OperationType.TRANSFER) {
        buildAnnotatedString {
            account(op.expenseAccountId)
            append(" › ")
            account(op.incomeAccountId)
        }
    } else {
        AnnotatedString(categoryLabel(op.categoryId))
    }
    val accountId = when (type) {
        OperationType.EXPENSE -> op.expenseAccountId
        OperationType.INCOME -> op.incomeAccountId
        OperationType.TRANSFER -> null
    }
    val amount = when (type) {
        OperationType.EXPENSE -> "−" + money(op.expenseAccountId, op.expenseAmount)
        OperationType.INCOME -> "+" + money(op.incomeAccountId, op.incomeAmount)
        OperationType.TRANSFER -> {
            val out = money(op.expenseAccountId, op.expenseAmount)
            val got = money(op.incomeAccountId, op.incomeAmount)
            if (out == got) out else "$out → $got"
        }
    }
    val color = when (type) {
        OperationType.EXPENSE -> MaterialTheme.colorScheme.error
        OperationType.INCOME -> incomeColor()
        OperationType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Кружок статьи — как на главном (цвет вида, инициалы); перевод — стрелка на цвете счетов.
        // Картинка: TalkBack читает название из строки.
        val circle = when (type) {
            OperationType.EXPENSE -> ExpenseColor
            OperationType.INCOME -> IncomeColor
            OperationType.TRANSFER -> AccountColor
        }
        Box(Modifier.size(36.dp).background(circle, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            val catIcon = if (type == OperationType.TRANSFER) null else icon(op.categoryId)
            if (type == OperationType.TRANSFER) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = onCircle(circle), modifier = Modifier.size(20.dp))
            } else if (catIcon != null) {
                Icon(catIcon, null, tint = onCircle(circle), modifier = Modifier.size(20.dp))
            } else {
                Text(initials(op.categoryId), color = onCircle(circle), style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildAnnotatedString {
                if (type != OperationType.TRANSFER) account(accountId)
                if (op.comment.isNotBlank()) {
                    if (length > 0) append(" · ")
                    append(op.comment)
                }
            }
            if (sub.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    // Точка — рисунок, не символ: TalkBack не читает «чёрный круг» в каждой строке.
                    accountColor(accountId)?.let { c ->
                        Box(Modifier.padding(top = 4.dp, end = 6.dp).size(8.dp).background(c, CircleShape))
                    }
                    Text(
                        sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Не шире половины экрана: длинный обмен переносится, а не вытесняет название. Без веса —
        // название (weight) берёт всё, что сумма не заняла.
        Text(
            amount, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 2, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = (LocalConfiguration.current.screenWidthDp / 2).dp),
        )
    }
}

/** Окно правки операции [id]: грузит операцию и открывает окно ввода с её полями. */
@Composable
fun EditOperationScreen(dao: CashDao, id: Long, onDone: () -> Unit) {
    val loaded by produceState<Pair<Operation, Pair<Node, Node>>?>(null, id) {
        val op = dao.operation(id)
        val drop = op?.let { dropOf(it, dao.allCategories().first()) }
        if (op == null || drop == null) onDone() else value = op to drop
    }
    val (op, drop) = loaded ?: return
    OperationScreen(dao, drop.first, drop.second, onDone, editing = op)
}

/** Переключатель месяца — одинаковый в лентах и графиках: полоса под верхней панелью, месяц по центру. */
@Composable
fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущий месяц")
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.titlecase(RU) },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий месяц")
            }
        }
    }
}
