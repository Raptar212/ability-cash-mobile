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
        }
    }
    val title = when (filter) {
        OpsFilter.All -> null
        is OpsFilter.Account -> accountById[filter.id]?.name ?: ""
        is OpsFilter.Category -> catById[filter.id]?.name ?: ""
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

    fun accountColor(id: Long?) = id?.let { accountById[it] }?.let { Color(accountArgb(it.id, it.color)) }

    @Composable
    fun monthSwitcher(modifier: Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущий месяц")
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.titlecase(RU) },
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            IconButton(onClick = { go(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий месяц")
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                    title = {
                        if (title == null) {
                            monthSwitcher(Modifier)
                        } else {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        onEdit?.let { IconButton(onClick = it) { Icon(Icons.Filled.Edit, "Изменить") } }
                        onTransfer?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Перевод на другой счёт") } }
                        onAdd?.let { IconButton(onClick = it) { Icon(Icons.Filled.Add, "Добавить операцию") } }
                    },
                )
                if (title != null) Surface(tonalElevation = 3.dp) { monthSwitcher(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
            }
        },
    ) { padding ->
        // До загрузки список не рисуем: пустой LazyColumn сбросил бы прокрутку при возврате из правки.
        if (ops == null) return@Scaffold
        if (days.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Операций нет") }
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
                                Text("−" + multi(day.expenses, precisions, compact = false), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                items(day.operations, key = { it.id }) { op ->
                    OperationRow(op, ::categoryLabel, ::accountName, ::accountColor, ::money) { onOpen(op.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun OperationRow(
    op: Operation,
    categoryLabel: (Long?) -> String,
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
        OperationType.EXPENSE -> MaterialTheme.colorScheme.onSurface
        OperationType.INCOME -> incomeColor()
        OperationType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
