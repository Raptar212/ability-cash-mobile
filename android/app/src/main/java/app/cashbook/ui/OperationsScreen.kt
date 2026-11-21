package app.cashbook.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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

/** Лента операций месяца [month] по дням; нажатие на строку — [onOpen] с id операции. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(
    dao: CashDao,
    month: YearMonth,
    listState: LazyListState,
    onMonth: (YearMonth) -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    // Список помнит свой месяц: до загрузки нового операции прошлого не показываются ни кадра.
    val loaded by produceState<Pair<YearMonth, List<Operation>>?>(null, month) {
        dao.operations(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay()).collect { value = month to it }
    }
    val ops = loaded?.takeIf { it.first == month }?.second
    val accounts by remember { dao.allAccounts() }.collectAsState(initial = emptyList())
    val currencies by remember { dao.currencies() }.collectAsState(initial = emptyList())
    val categories by remember { dao.allCategories() }.collectAsState(initial = emptyList())

    val currencyById = currencies.associateBy { it.id }
    val accountById = accounts.associateBy { it.id }
    val currencyOf = accounts.associate { a -> a.id to (currencyById[a.currencyId]?.code ?: "?") }
    val precisions = currencies.associate { it.code to it.precision }
    val catById = categories.associateBy { it.id }
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                },
            )
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
                    Surface(tonalElevation = 2.dp) {
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
                    OperationRow(op, ::categoryLabel, ::accountName, ::money) { onOpen(op.id) }
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
    money: (Long?, Long?) -> String,
    onClick: () -> Unit,
) {
    val type = op.type
    val title = if (type == OperationType.TRANSFER) {
        "${accountName(op.expenseAccountId)} › ${accountName(op.incomeAccountId)}"
    } else {
        categoryLabel(op.categoryId)
    }
    val account = when (type) {
        OperationType.EXPENSE -> accountName(op.expenseAccountId)
        OperationType.INCOME -> accountName(op.incomeAccountId)
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
            val sub = listOfNotNull(account, op.comment.ifBlank { null }).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(amount, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
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
