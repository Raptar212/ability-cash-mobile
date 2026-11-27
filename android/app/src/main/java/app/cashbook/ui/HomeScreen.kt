package app.cashbook.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import app.cashbook.data.CashDao
import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import app.cashbook.data.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

val IncomeColor = Color(0xFF3F51B5)
val AccountColor = Color(0xFFF2A900)
val ExpenseColor = Color(0xFF00796B)

/** Инициалы на кружке: на жёлтом счёте — тёмные (белые на жёлтом не читаются). */
fun onCircle(color: Color) = if (color == AccountColor) Color(0xFF3D2A00) else Color.White

private val BubbleSize = 56.dp

fun symbol(code: String) = when (code) {
    "UAH" -> "₴"
    "USD" -> "$"
    "EUR" -> "€"
    else -> code
}

/** Формат для экрана: минус — «−» (U+2212, шириной с «+»), вне диапазона `Money.format` — «—». */
fun fmt(amount: Long, precision: Int): String =
    runCatching { Money.format(amount, precision).replace('-', '−') }.getOrDefault("—")

/** «1 234 ₴ · 20 $»; пусто — «0». [precisions] — точность по коду валюты, [sep] — между валютами. */
fun multi(sums: Map<String, Long>?, precisions: Map<String, Int>, compact: Boolean, sep: String = " · "): String {
    if (sums.isNullOrEmpty()) return "0"
    return sums.entries.joinToString(sep) { (code, v) ->
        "${fmt(v, if (compact) 0 else precisions[code] ?: 2)} ${symbol(code)}"
    }
}

/** Ячейка ряда: кружок статьи или счёта с подписью и суммой; [negative] — сумма красным. */
private data class Cell(
    val node: Node, val label: String, val amount: String, val color: Color,
    val negative: Boolean = false, val initials: String = "",
    val icon: ImageVector? = null, // иконка статьи; null — инициалы
)

/** Инициалы ряда — уникальные в ряду ([circleInitials]). */
private fun List<Cell>.withInitials() = zip(circleInitials(map { it.label })) { c, i -> c.copy(initials = i) }

private data class Dragging(val cell: Cell, val pos: Offset)

/** Принятый бросок: кружок [cell] летит из [start] в центр цели [end] (координаты корня), затем onDrop. */
private data class Absorb(val cell: Cell, val start: Offset, val end: Offset, val from: Node, val to: Node)

/** Горб 0→1→0 по фазе анимации 0..1 — пульс и подсветка суммы. */
private fun bump(phase: Float) = sin(PI * phase).toFloat()

@Composable
fun HomeScreen(
    dao: CashDao,
    onAccount: (Long) -> Unit,
    onExpenseCategory: (Long) -> Unit,
    onNewAccount: () -> Unit,
    onDrop: (Node, Node) -> Unit,
    onSettings: () -> Unit,
    onOperations: () -> Unit,
    onToday: (epochDay: Long) -> Unit,
    pulse: Set<Node> = emptySet(),
    onPulsed: () -> Unit = {},
) {
    var newCategory by remember { mutableStateOf<CategoryKind?>(null) } // «+» в заголовке «Доходы»/«Расходы»
    val month = remember {
        val now = LocalDate.now()
        now.withDayOfMonth(1).toEpochDay() to now.withDayOfMonth(now.lengthOfMonth()).toEpochDay()
    }
    val balances by remember { dao.balances() }.collectAsState(initial = emptyList())
    val categories by remember { dao.allCategories() }.collectAsState(initial = emptyList())
    val sums by remember { dao.categorySums(month.first, month.second) }.collectAsState(initial = emptyList())
    val today = remember { LocalDate.now().toEpochDay() }
    val daySums by remember { dao.categorySums(today, today) }.collectAsState(initial = emptyList())
    val currencies by remember { dao.currencies() }.collectAsState(initial = emptyList())

    val precisions = currencies.associate { it.code to it.precision }
    val rolled = remember(categories, sums) { rollUp(categories, sums) }
    val tops = categories.filter { it.parentId == null }
    val incomeCells = tops.filter { it.kind == CategoryKind.INCOME }.map {
        Cell(Node(NodeKind.INCOME, it.id), it.name, multi(rolled[it.id], precisions, compact = true), IncomeColor, icon = categoryIcon(it.icon))
    }.withInitials()
    val expenseTops = tops.filter { it.kind == CategoryKind.EXPENSE }
    val expenseCells = expenseTops.map {
        Cell(Node(NodeKind.EXPENSE, it.id), it.name, multi(rolled[it.id], precisions, compact = true), ExpenseColor, icon = categoryIcon(it.icon))
    }.withInitials()
    val accountCells = balances.inUserOrder().map {
        // На кружке счёта — знак его валюты (₴, $, €; прочие — код): различает счета по валюте, имя — подпись.
        Cell(
            Node(NodeKind.ACCOUNT, it.accountId), it.name, "${fmt(it.balance, 0)} ${symbol(it.currencyCode)}", AccountColor,
            it.balance < 0, initials = symbol(it.currencyCode),
        )
    }

    val balanceByCurrency = balances.groupBy { it.currencyCode }.mapValues { (_, v) -> v.sumOf { it.balance } }
    fun expenses(r: Map<Long, Map<String, Long>>) = expenseTops.mapNotNull { r[it.id] }.flatMap { it.entries }
        .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    val expenseByCurrency = expenses(rolled)
    val dayExpenseByCurrency = expenses(remember(categories, daySums) { rollUp(categories, daySums) })

    // Перетаскивание: прямоугольники кружков в координатах корня → цель под пальцем.
    val bounds = remember { mutableStateMapOf<Node, Rect>() }
    var drag by remember { mutableStateOf<Dragging?>(null) }
    // Конец перетаскивания (uptime): отпускание после перетаскивания — не клик по кружку.
    var dragEndedAt by remember { mutableStateOf(0L) }
    // Бросок принят — кружок «втягивается» в цель (~200 мс), только потом ввод суммы.
    var absorbing by remember { mutableStateOf<Absorb?>(null) }
    val absorb = remember { Animatable(0f) }
    fun clickAllowed() = drag == null && absorbing == null && SystemClock.uptimeMillis() - dragEndedAt > 300
    var origin by remember { mutableStateOf(Offset.Zero) }
    var visible by remember { mutableStateOf(Rect.Zero) } // видимая область рядов (без шапки)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val density = LocalDensity.current
    val half = with(density) { BubbleSize.toPx() / 2 }
    val edge = with(density) { 72.dp.toPx() }
    val scroll = rememberScrollState()

    // Автопрокрутка: палец с кружком у верхнего/нижнего края видимой области — ряды едут.
    // drag меняется каждый кадр — экран читает только производные (derivedStateOf), иначе
    // при перетаскивании перерисовывался бы целиком.
    val dragging by remember { derivedStateOf { drag != null } }
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
    val hover by remember {
        derivedStateOf {
            drag?.let { d ->
                val from = d.cell.node
                bounds.entries.firstOrNull { (n, r) -> n != from && r.contains(d.pos) && visible.contains(d.pos) && dropType(from, n) != null }?.key
            }
        }
    }
    val dragCell by remember { derivedStateOf { drag?.cell } }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(hover) { if (hover != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    // Ушли с экрана посреди полёта — эффект отменён, onDrop не вызывается: бросок не удвоится.
    LaunchedEffect(absorbing) {
        val a = absorbing ?: return@LaunchedEffect
        absorb.snapTo(0f)
        absorb.animateTo(1f, tween(200))
        absorb.snapTo(0f) // иначе следующий бросок кадр рисуется уже «втянутым»
        absorbing = null
        currentOnDrop(a.from, a.to)
    }

    // Только что записанная операция: её кружки один раз пульсируют, суммы под ними подсвечены.
    val pulseAnim = remember { Animatable(0f) }
    val currentOnPulsed by rememberUpdatedState(onPulsed)
    LaunchedEffect(pulse) {
        if (pulse.isEmpty()) return@LaunchedEffect
        // После возврата ряды ещё грузятся — ждём кружки (статью могли удалить — не дольше секунды).
        withTimeoutOrNull(1000) { snapshotFlow { pulse.all { it in bounds } }.first { it } }
        // Ушли с экрана посреди пульса — всё равно сбросить, иначе он сыграет при следующем возврате.
        try {
            pulseAnim.snapTo(0f)
            pulseAnim.animateTo(1f, tween(600))
            pulseAnim.snapTo(0f)
        } finally {
            currentOnPulsed()
        }
    }

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
        // Перетаскивание — сразу, как палец сдвинулся (без задержки); тап — открыть. Экран листается
        // свайпом по заголовкам секций и по расходам: они не тащатся.
        // Расход — только цель броска: сам не тащится.
        val base = Modifier
            .onGloballyPositioned { bounds[node] = it.boundsInRoot() }
            .then(if (node.kind == NodeKind.EXPENSE) Modifier else Modifier.pointerInput(node) {
                detectDragGestures(
                    onDragStart = { p ->
                        if (absorbing == null) bounds[node]?.let {
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
                            val hit = bounds.entries.firstOrNull { it.key != node && it.value.contains(d.pos) }
                            if (hit != null && dropType(node, hit.key) != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                absorbing = Absorb(d.cell, d.pos, hit.value.center, node, hit.key)
                            }
                        }
                    },
                    onDragCancel = {
                        dragEndedAt = SystemClock.uptimeMillis()
                        drag = null
                    },
                )
            })
        // Подпись действия для TalkBack: «Двойное нажатие — открыть операции счёта».
        val action = when (node.kind) {
            NodeKind.ACCOUNT -> "открыть операции счёта"
            NodeKind.EXPENSE -> "открыть операции статьи"
            NodeKind.INCOME -> "добавить доход"
        }
        return base.clickable(onClickLabel = action) {
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
                // Баланс и расходы за месяц/день — три равные колонки через разделитель; расходы — входы в ленты.
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(IntrinsicSize.Min)
                        .padding(end = 16.dp, top = 4.dp, bottom = 12.dp),
                ) {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Menu, "Настройки") }
                    HeaderValue(
                        "Баланс",
                        multi(balanceByCurrency, precisions, compact = false, sep = "\n"),
                        Modifier.weight(1.3f).padding(top = 8.dp), // длиннее сумм расходов
                        maxLines = 4,
                    )
                    VerticalDivider(Modifier.padding(horizontal = 8.dp))
                    // Плашка расходов — вход в ленту операций по месяцам (вместо значка календаря).
                    HeaderValue(
                        "Расходы за месяц",
                        multi(expenseByCurrency, precisions, compact = false, sep = "\n"),
                        Modifier.weight(1f).heightIn(min = 48.dp) // палец не промахивается
                            .clickable(onClickLabel = "Операции за месяц", role = Role.Button, onClick = onOperations)
                            .padding(top = 8.dp),
                    )
                    VerticalDivider(Modifier.padding(horizontal = 8.dp))
                    HeaderValue(
                        "Расходы за день",
                        multi(dayExpenseByCurrency, precisions, compact = false, sep = "\n"),
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Операции за сегодня", role = Role.Button) { onToday(today) } // тот же день, что в сумме
                            .padding(top = 8.dp),
                    )
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
            // Цель подсвечена и пока в неё «втягивается» брошенный кружок.
            val target = hover ?: absorbing?.to
            val pulsePhase = { pulseAnim.value }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Grid("Доходы", incomeCells, target, pulse, pulsePhase, onAdd = { newCategory = CategoryKind.INCOME }) { cellModifier(it) }
                Grid("Счета", accountCells, target, pulse, pulsePhase, onAdd = onNewAccount, addLabel = "Новый счёт") { cellModifier(it) }
                Grid("Расходы", expenseCells, target, pulse, pulsePhase, onAdd = { newCategory = CategoryKind.EXPENSE }) { cellModifier(it) }
            }
            absorbing?.let { a ->
                Circle(
                    a.cell,
                    Modifier
                        .offset {
                            val p = lerp(a.start, a.end, absorb.value)
                            IntOffset((p.x - origin.x - half).roundToInt(), (p.y - origin.y - half).roundToInt())
                        }
                        .graphicsLayer {
                            val t = absorb.value
                            scaleX = 1f - 0.7f * t
                            scaleY = 1f - 0.7f * t
                            alpha = 1f - t
                        },
                )
            }
            dragCell?.let { cell ->
                Circle(
                    cell,
                    Modifier.offset { // позиция читается при раскладке, без перекомпозиции
                        val p = drag?.pos ?: return@offset IntOffset.Zero
                        IntOffset((p.x - origin.x - half).roundToInt(), (p.y - origin.y - half).roundToInt())
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
private fun HeaderValue(title: String, value: String, modifier: Modifier, maxLines: Int = 3) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
/** Ряд кружков с заголовком; [onAdd] — кнопка «+» справа в заголовке (новая статья). */
private fun Grid(
    title: String,
    cells: List<Cell>,
    hover: Node?,
    pulse: Set<Node>,
    pulsePhase: () -> Float,
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
    // Подписи ряда в две строки, если хоть одно название из нескольких слов не влезает в одну (длинное
    // название, крупный шрифт). Одно слово переносится только по слогам, а на это система не всегда
    // способна, — такое слово остаётся в одну строку с «…», а не рвётся посередине.
    // Высота подписи одна на ряд, текст прижат к кружку — кружки ряда стоят ровно.
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Ширина текста: экран − поля ряда 2×8dp, на 4 ячейки, − отступы подписи 2×4dp.
    val textWidth = with(density) { ((LocalConfiguration.current.screenWidthDp.dp - 16.dp) / 4 - 8.dp).roundToPx() }
    val labels = cells.map { it.label }
    val lineHeight = remember(labelStyle, density) { with(density) { measurer.measure("Ag", labelStyle).size.height.toDp() } }
    val twoLines = remember(labels, textWidth, labelStyle, density) {
        val fits = Constraints(maxWidth = textWidth.coerceAtLeast(1))
        labels.map { ' ' in it.trim() && measurer.measure(it, labelStyle, maxLines = 1, constraints = fits).hasVisualOverflow }
            .chunked(4).map { row -> row.any { it } }
    }
    for ((r, row) in cells.chunked(4).withIndex()) {
        val labelLines = if (twoLines.getOrElse(r) { false }) 2 else 1
        val labelHeight = lineHeight * labelLines
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            for (c in row) key(c.node) { // эффекты и bounds следуют за кружком, а не за позицией
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    Column(
                        cellModifier(c).fillMaxWidth().padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.height(labelHeight).padding(horizontal = 4.dp), contentAlignment = Alignment.BottomCenter) {
                            Text(
                                c.label, style = labelStyle, maxLines = if (' ' in c.label.trim()) labelLines else 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            )
                        }
                        val pulsing = c.node in pulse
                        Circle(c, Modifier.padding(4.dp), highlighted = c.node == hover, pulse = if (pulsing) pulsePhase else null)
                        val amountColor = if (c.negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            c.amount,
                            style = MaterialTheme.typography.labelMedium,
                            // Фаза читается в композиции: ряд перерисуется ~600 мс раз за пульс — приемлемо.
                            color = if (pulsing) lerp(amountColor, MaterialTheme.colorScheme.primary, bump(pulsePhase())) else amountColor,
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

/**
 * [highlighted] — цель под пальцем при перетаскивании: крупнее и с обводкой. [pulse] — фаза 0..1
 * пульса после записи операции (читается при отрисовке, без перекомпозиции).
 */
@Composable
private fun Circle(cell: Cell, modifier: Modifier = Modifier, highlighted: Boolean = false, pulse: (() -> Float)? = null) {
    val scale by animateFloatAsState(if (highlighted) 1.18f else 1f, label = "drop-target")
    Box(
        modifier
            .graphicsLayer {
                val s = scale * (1f + 0.15f * (pulse?.let { bump(it()) } ?: 0f))
                scaleX = s
                scaleY = s
            }
            .size(BubbleSize)
            .then(if (highlighted) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(cell.color),
        contentAlignment = Alignment.Center,
    ) {
        // Иконка или инициалы — картинка, TalkBack читает полное имя из подписи.
        if (cell.icon != null) {
            Icon(cell.icon, null, tint = onCircle(cell.color), modifier = Modifier.size(28.dp))
        } else {
            Text(
                cell.initials, color = onCircle(cell.color), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, softWrap = false, // код валюты из трёх букв не переносится при крупном шрифте
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
