package app.cashbook.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.cashbook.data.CashDao
import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import app.cashbook.data.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

val IncomeColor = Color(0xFF3F51B5)
val AccountColor = Color(0xFFF2A900)
val ExpenseColor = Color(0xFF00796B)

/** Инициалы на кружке: на жёлтом счёте — тёмные (белые на жёлтом не читаются). */
private fun onCircle(color: Color) = if (color == AccountColor) Color(0xFF3D2A00) else Color.White

private val BubbleSize = 56.dp

fun symbol(code: String) = when (code) {
    "UAH" -> "₴"
    "USD" -> "$"
    "EUR" -> "€"
    else -> code
}

/** Формат без падения: вне диапазона `Money.format` — «—». */
fun fmt(amount: Long, precision: Int): String = runCatching { Money.format(amount, precision) }.getOrDefault("—")

/** «1 234 ₴ · 20 $»; пусто — «0». [precisions] — точность по коду валюты, [sep] — между валютами. */
fun multi(sums: Map<String, Long>?, precisions: Map<String, Int>, compact: Boolean, sep: String = " · "): String {
    if (sums.isNullOrEmpty()) return "0"
    return sums.entries.joinToString(sep) { (code, v) ->
        "${fmt(v, if (compact) 0 else precisions[code] ?: 2)} ${symbol(code)}"
    }
}

private fun initials(name: String): String {
    val words = name.split(' ', '(', ',', '_').filter { w -> w.any { it.isLetter() } }
    return if (words.size >= 2) {
        "${words[0].first { it.isLetter() }}${words[1].first { it.isLetter() }}".uppercase()
    } else {
        name.filter { it.isLetter() }.take(2)
    }
}

/** Ячейка ряда: кружок статьи или счёта с подписью и суммой; [negative] — сумма красным. */
private data class Cell(val node: Node, val label: String, val amount: String, val color: Color, val negative: Boolean = false)

private data class Dragging(val cell: Cell, val pos: Offset)

@Composable
fun HomeScreen(
    dao: CashDao,
    onAccount: (Long) -> Unit,
    onExpenseCategory: (Long) -> Unit,
    onNewAccount: () -> Unit,
    onDrop: (Node, Node) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onExportCsv: () -> Unit,
    onCharts: () -> Unit,
    onWidget: () -> Unit,
    onCategories: (CategoryKind) -> Unit,
    onArchive: () -> Unit,
    onUpdates: () -> Unit,
    onOperations: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf<CategoryKind?>(null) } // «+» в заголовке «Доходы»/«Расходы»
    val month = remember {
        val now = LocalDate.now()
        now.withDayOfMonth(1).toEpochDay() to now.withDayOfMonth(now.lengthOfMonth()).toEpochDay()
    }
    val balances by remember { dao.balances() }.collectAsState(initial = emptyList())
    val categories by remember { dao.allCategories() }.collectAsState(initial = emptyList())
    val sums by remember { dao.categorySums(month.first, month.second) }.collectAsState(initial = emptyList())
    val currencies by remember { dao.currencies() }.collectAsState(initial = emptyList())

    val precisions = currencies.associate { it.code to it.precision }
    val rolled = remember(categories, sums) { rollUp(categories, sums) }
    val tops = categories.filter { it.parentId == null }
    val incomeCells = tops.filter { it.kind == CategoryKind.INCOME }.map {
        Cell(Node(NodeKind.INCOME, it.id), it.name, multi(rolled[it.id], precisions, compact = true), IncomeColor)
    }
    val expenseTops = tops.filter { it.kind == CategoryKind.EXPENSE }
    val expenseCells = expenseTops.map {
        Cell(Node(NodeKind.EXPENSE, it.id), it.name, multi(rolled[it.id], precisions, compact = true), ExpenseColor)
    }
    val accountCells = balances.map {
        Cell(Node(NodeKind.ACCOUNT, it.accountId), it.name, "${fmt(it.balance, 0)} ${symbol(it.currencyCode)}", AccountColor, it.balance < 0)
    }

    val balanceByCurrency = balances.groupBy { it.currencyCode }.mapValues { (_, v) -> v.sumOf { it.balance } }
    val expenseByCurrency = expenseTops.mapNotNull { rolled[it.id] }.flatMap { it.entries }
        .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

    // Перетаскивание: прямоугольники кружков в координатах корня → цель под пальцем.
    val bounds = remember { mutableStateMapOf<Node, Rect>() }
    var drag by remember { mutableStateOf<Dragging?>(null) }
    // Конец перетаскивания (uptime): отпускание после долгого нажатия — не клик по кружку.
    var dragEndedAt by remember { mutableStateOf(0L) }
    fun clickAllowed() = drag == null && SystemClock.uptimeMillis() - dragEndedAt > 300
    var origin by remember { mutableStateOf(Offset.Zero) }
    var visible by remember { mutableStateOf(Rect.Zero) } // видимая область рядов (без шапки)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val density = LocalDensity.current
    val half = with(density) { BubbleSize.toPx() / 2 }
    val edge = with(density) { 72.dp.toPx() }
    val scroll = rememberScrollState()

    // Автопрокрутка: палец с кружком у верхнего/нижнего края видимой области — ряды едут.
    val dragging = drag != null
    LaunchedEffect(dragging) {
        while (dragging) {
            val y = drag?.pos?.y ?: break
            val step = when {
                y > visible.bottom - edge -> 24f
                y < visible.top + edge -> -24f
                else -> 0f
            }
            if (step != 0f) scroll.scrollBy(step)
            delay(16)
        }
    }

    // Цель под пальцем: кружок, на который бросок создаст операцию (подсвечивается).
    val hover = drag?.let { d ->
        val from = d.cell.node
        bounds.entries.firstOrNull { (n, r) -> n != from && r.contains(d.pos) && visible.contains(d.pos) && dropType(from, n) != null }?.key
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(hover) { if (hover != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    // Тап по статье дохода — операция со счётом последней такой операции (нет — первый счёт).
    val scope = rememberCoroutineScope()
    fun tapCategory(node: Node) = scope.launch {
        val expense = node.kind == NodeKind.EXPENSE
        val account = dao.lastAccount(expense) ?: balances.firstOrNull()?.accountId ?: return@launch
        val acc = Node(NodeKind.ACCOUNT, account)
        if (expense) onDrop(acc, node) else onDrop(node, acc)
    }

    @Composable
    fun cellModifier(cell: Cell): Modifier {
        val node = cell.node
        val currentCell by rememberUpdatedState(cell) // сумма меняется — жест не перезапускается
        DisposableEffect(node) { onDispose { bounds.remove(node) } }
        // Перетаскивание — после долгого нажатия: обычное касание и свайп прокручивают ряды.
        // Расход — только цель броска: сам не тащится.
        val base = Modifier
            .onGloballyPositioned { bounds[node] = it.boundsInRoot() }
            .then(if (node.kind == NodeKind.EXPENSE) Modifier else Modifier.pointerInput(node) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        bounds[node]?.let {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            drag = Dragging(currentCell, it.topLeft + p)
                        }
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        drag = drag?.let { it.copy(pos = it.pos + delta) }
                    },
                    onDragEnd = {
                        dragEndedAt = SystemClock.uptimeMillis()
                        val d = drag
                        drag = null
                        // Палец над шапкой — кружки, прокрученные под неё, не цели.
                        if (d != null && visible.contains(d.pos)) {
                            val target = bounds.entries.firstOrNull { it.key != node && it.value.contains(d.pos) }?.key
                            if (target != null && dropType(node, target) != null) currentOnDrop(node, target)
                        }
                    },
                    onDragCancel = {
                        dragEndedAt = SystemClock.uptimeMillis()
                        drag = null
                    },
                )
            })
        return base.clickable {
            if (!clickAllowed()) return@clickable
            when (node.kind) {
                NodeKind.ACCOUNT -> onAccount(node.id)
                NodeKind.EXPENSE -> onExpenseCategory(node.id)
                NodeKind.INCOME -> tapCategory(node)
            }
        }
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(end = 16.dp, top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Menu, "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Графики") }, onClick = { menu = false; onCharts() })
                            DropdownMenuItem(text = { Text("Экспорт копии базы") }, onClick = { menu = false; onExport() })
                            DropdownMenuItem(text = { Text("Импорт копии базы") }, onClick = { menu = false; onImport() })
                            DropdownMenuItem(text = { Text("Экспорт CSV (CoinKeeper)") }, onClick = { menu = false; onExportCsv() })
                            DropdownMenuItem(text = { Text("Статьи") }, onClick = { menu = false; onCategories(CategoryKind.EXPENSE) })
                            DropdownMenuItem(text = { Text("Архив счетов") }, onClick = { menu = false; onArchive() })
                            DropdownMenuItem(text = { Text("Виджет") }, onClick = { menu = false; onWidget() })
                            DropdownMenuItem(text = { Text("Обновления") }, onClick = { menu = false; onUpdates() })
                        }
                    }
                    IconButton(onClick = onOperations) { Icon(Icons.AutoMirrored.Filled.List, "Операции") }
                    HeaderValue("Баланс", multi(balanceByCurrency, precisions, compact = false, sep = "\n"), Modifier.weight(1f))
                    HeaderValue("Расходы за месяц", multi(expenseByCurrency, precisions, compact = false, sep = "\n"), Modifier.weight(1f))
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().onGloballyPositioned {
                origin = it.positionInRoot()
                visible = it.boundsInRoot()
            },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Grid("Доходы", incomeCells, hover, onAdd = { newCategory = CategoryKind.INCOME }) { cellModifier(it) }
                Grid("Счета", accountCells, hover, onAdd = onNewAccount, addLabel = "Новый счёт") { cellModifier(it) }
                Grid("Расходы", expenseCells, hover, onAdd = { newCategory = CategoryKind.EXPENSE }) { cellModifier(it) }
            }
            drag?.let { d ->
                Circle(
                    d.cell,
                    Modifier.offset {
                        IntOffset((d.pos.x - origin.x - half).roundToInt(), (d.pos.y - origin.y - half).roundToInt())
                    },
                )
            }
        }
    }

    newCategory?.let { kind ->
        NameDialog(if (kind == CategoryKind.INCOME) "Новая статья дохода" else "Новая статья расхода", "", onDismiss = { newCategory = null }) { n ->
            newCategory = null
            scope.launch { dao.insertCategory(Category(name = n, kind = kind)) }
        }
    }
}

@Composable
private fun HeaderValue(title: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
/** Ряд кружков с заголовком; [onAdd] — кнопка «+» справа в заголовке (новая статья). */
private fun Grid(
    title: String,
    cells: List<Cell>,
    hover: Node?,
    onAdd: (() -> Unit)? = null,
    addLabel: String = "Новая статья: $title",
    cellModifier: @Composable (Cell) -> Modifier,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = if (onAdd == null) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (onAdd != null) {
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, addLabel) }
        }
    }
    for (row in cells.chunked(4)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            for (c in row) key(c.node) { // эффекты и bounds следуют за кружком, а не за позицией
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    Column(
                        cellModifier(c).fillMaxWidth().padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            c.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Circle(c, Modifier.padding(4.dp), highlighted = c.node == hover)
                        Text(
                            c.amount,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (c.negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

/** [highlighted] — цель под пальцем при перетаскивании: крупнее и с обводкой. */
@Composable
private fun Circle(cell: Cell, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    Box(
        modifier
            .scale(if (highlighted) 1.18f else 1f)
            .size(BubbleSize)
            .then(if (highlighted) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(cell.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(cell.label), color = onCircle(cell.color), style = MaterialTheme.typography.titleMedium)
    }
}
