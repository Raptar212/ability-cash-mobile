package app.cashbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cashbook.data.CashDao
import app.cashbook.data.CategoryKind
import app.cashbook.data.CategorySum
import app.cashbook.data.DaySum
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val RU = Locale("ru")

/**
 * Категориальная палитра (dataviz, светлая тема, слоты 1–6) — по порядку долей; «Другое» — серый.
 * Долей не больше 6 (`fold`), так что у каждой своей статьи — свой цвет.
 */
private val SLICE_COLORS = listOf(
    Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4), Color(0xFF008300),
)
private val OTHER_COLOR = Color(0xFF9E9E9E)

private fun monthTitle(m: YearMonth, pattern: String) =
    m.format(DateTimeFormatter.ofPattern(pattern, RU)).replaceFirstChar { it.titlecase(RU) }

/**
 * Графики за месяц, год или всё время: круг по статьям за период и столбики расходов/доходов —
 * 12 месяцев до выбранного, месяцы года или годы.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChartsScreen(dao: CashDao, onBack: () -> Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var periodName by rememberSaveable { mutableStateOf(Period.MONTH.name) }
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var expense by rememberSaveable { mutableStateOf(true) }
    var parent by rememberSaveable { mutableStateOf<Long?>(null) }
    val month = YearMonth.parse(monthText)
    val period = Period.valueOf(periodName)
    fun show(p: Period, m: YearMonth = month) {
        periodName = p.name
        monthText = m.toString()
        parent = null
    }

    // Диапазон круга (дни эпохи); у «всего времени» — без границ. Столбики месяца — 12 месяцев до него.
    val year = month.withMonth(1)
    val range = when (period) {
        Period.MONTH -> month.atDay(1).toEpochDay() to month.atEndOfMonth().toEpochDay()
        Period.YEAR -> year.atDay(1).toEpochDay() to year.plusMonths(11).atEndOfMonth().toEpochDay()
        Period.ALL -> Long.MIN_VALUE to Long.MAX_VALUE
    }
    val barsRange = if (period == Period.MONTH) month.minusMonths(11).atDay(1).toEpochDay() to range.second else range

    val currencies by remember { dao.currencies() }.collectAsState(initial = emptyList())
    val categories by remember { dao.allCategories() }.collectAsState(initial = emptyList())
    // Данные помнят свой диапазон: при листании чужой период не показывается ни кадра.
    val sums by produceState<Pair<Pair<Long, Long>, List<CategorySum>>?>(null, range) {
        dao.categorySums(range.first, range.second).collect { value = range to it }
    }
    val days by produceState<Pair<Pair<Long, Long>, List<DaySum>>?>(null, barsRange) {
        dao.daySums(barsRange.first, barsRange.second).collect { value = barsRange to it }
    }

    BackHandler(enabled = parent != null) { parent = null }

    val cur = code ?: currencies.firstOrNull { it.code == "UAH" }?.code ?: currencies.firstOrNull()?.code
    val precision = currencies.firstOrNull { it.code == cur }?.precision ?: 2
    fun money(v: Long) = "${fmt(v, precision)} ${symbol(cur.orEmpty())}"
    val kind = if (expense) CategoryKind.EXPENSE else CategoryKind.INCOME
    val monthSums = sums?.takeIf { it.first == range }?.second
    val all = if (monthSums == null || cur == null) emptyList() else slices(categories, monthSums, kind, cur, parent)
    val pie = fold(all)
    val total = all.sumOf { it.amount }
    // Сколько строк легенды имеют свой цвет: при свёртке в «Другое» остальные — серые.
    val colored = if (pie.lastOrNull()?.id == null) pie.size - 1 else pie.size
    val dayRows = days?.takeIf { it.first == barsRange }?.second?.takeIf { cur != null }
    val periodTitle = when (period) {
        Period.MONTH -> monthTitle(month, "LLLL").lowercase(RU)
        Period.YEAR -> "${month.year} год"
        Period.ALL -> "всё время"
    }

    fun colorOf(index: Int, s: Slice) = if (s.id == null || index >= SLICE_COLORS.size) OTHER_COLOR else SLICE_COLORS[index]
    fun open(s: Slice) {
        if (s.hasChildren) parent = s.id
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                    title = { Text("Графики") },
                )
                when (period) {
                    Period.MONTH -> MonthSwitcher(month, onChange = { show(Period.MONTH, it) })
                    Period.YEAR -> Switcher(
                        "${month.year} год", "Предыдущий год", "Следующий год",
                        onPrev = { show(Period.YEAR, month.minusYears(1)) }, onNext = { show(Period.YEAR, month.plusYears(1)) },
                    )
                    Period.ALL -> Unit
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            // Группы чипов: не влезают в строку — группа целиком переносится вниз, край не режет чип.
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((p, label) in listOf(Period.MONTH to "Месяц", Period.YEAR to "Год", Period.ALL to "Всё время")) {
                        FilterChip(selected = period == p, onClick = { show(p) }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = expense, onClick = { expense = true; parent = null }, label = { Text("Расходы") })
                    FilterChip(selected = !expense, onClick = { expense = false; parent = null }, label = { Text("Доходы") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in currencies) {
                        FilterChip(selected = c.code == cur, onClick = { code = c.code; parent = null }, label = { Text(c.code) })
                    }
                }
            }

            // Круг.
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (parent != null) {
                    TextButton(onClick = { parent = null }) { Text("‹ Все статьи") }
                    Text(
                        categories.firstOrNull { it.id == parent }?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        (if (expense) "Расходы" else "Доходы") + " за " + periodTitle,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f),
                    )
                }
            }
            // «Нет данных» — только когда загружены и суммы, и справочники (иначе мигает при открытии).
            if (monthSums != null && cur != null && categories.isNotEmpty() && all.isEmpty()) {
                EmptyState("Нет данных", "За этот период операций в этой валюте нет.", Modifier.fillMaxWidth())
            } else if (pie.isNotEmpty()) {
                // Кольцо: сумма — в центре, вместо строки заголовка.
                Pie(
                    pie, total, money(total), ::colorOf, onTap = ::open,
                    modifier = Modifier.padding(16.dp).size(220.dp).align(Alignment.CenterHorizontally),
                )
                // Легенда-список: все статьи с суммой и долей.
                for ((i, s) in all.withIndex()) {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = s.hasChildren) { open(s) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if (i < colored) colorOf(i, s) else OTHER_COLOR))
                        Text(
                            s.name + if (s.hasChildren) " ›" else "",
                            modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // Название, сумма и доля — одним размером (bodyLarge), доля — приглушённым цветом.
                        Text(money(s.amount))
                        Text(
                            "${percent(s.amount, total)}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 8.dp).widthIn(min = 44.dp),
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Столбики: 12 месяцев до выбранного, месяцы года или годы; нажатие — переход к этому периоду.
            Text(
                if (period == Period.ALL) "По годам" else "По месяцам",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(ExpenseColor, "Расходы")
                Spacer(Modifier.width(16.dp))
                LegendSwatch(chartIncomeColor(), "Доходы")
            }
            if (dayRows != null && cur != null) {
                val monthLabel = { m: YearMonth -> monthTitle(m, "LLL").trimEnd('.').take(3) }
                when (period) {
                    Period.MONTH -> BarChart(
                        monthly(dayRows, month, cur), month, monthLabel, { show(Period.MONTH, it) },
                        "За последние 12 месяцев операций в этой валюте нет.", money = ::money,
                        summary = { "${monthTitle(month, "LLLL yyyy")}: расходы ${money(it.last().expense)} · доходы ${money(it.last().income)}" },
                    )
                    Period.YEAR -> BarChart(
                        monthly(dayRows, year.plusMonths(11), cur), null, monthLabel, { show(Period.MONTH, it) },
                        "За этот год операций в этой валюте нет.", money = ::money,
                    )
                    Period.ALL -> BarChart(
                        yearly(dayRows, cur, YearMonth.now().year), null, { it.toString() },
                        { show(Period.YEAR, YearMonth.of(it, month.monthValue)) },
                        "Операций в этой валюте нет.", money = ::money,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
}

/** Доля радиуса под дырку кольца. */
private const val HOLE = 0.6f

/** Кольцо долей с зазором цвета фона между секторами и [label] (сумма) в центре; нажатие на сектор — [onTap]. */
@Composable
private fun Pie(pie: List<Slice>, total: Long, label: String, colorOf: (Int, Slice) -> Color, onTap: (Slice) -> Unit, modifier: Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    // Углы — только для рисования (деньги в модели остаются Long).
    val sweeps = pie.map { (it.amount.toDouble() / total * 360.0).toFloat() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(pie) {
                detectTapGestures { p ->
                    val dx = p.x - size.width / 2f
                    val dy = p.y - size.height / 2f
                    val d = hypot(dx, dy)
                    if (d > size.width / 2f || d < size.width / 2f * HOLE) return@detectTapGestures // мимо кольца
                    var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f // 0° — сверху, по часовой
                    if (angle < 0) angle += 360f
                    // Последняя доля — остаток до 360°: округление углов не теряет нажатие у шва.
                    var acc = 0f
                    val hit = pie.withIndex().firstOrNull { (i, _) -> acc += sweeps[i]; angle <= acc }?.value ?: pie.last()
                    onTap(hit)
                }
            },
        ) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            var start = -90f
            for ((i, s) in pie.withIndex()) {
                drawArc(colorOf(i, s), start, sweeps[i], useCenter = true, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r))
                start += sweeps[i]
            }
            if (pie.size > 1) {
                start = -90f
                for (sw in sweeps) {
                    val a = start * PI / 180.0
                    drawLine(surface, c, c + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()), strokeWidth = 2.dp.toPx())
                    start += sw
                }
            }
            drawCircle(surface, r * HOLE, c)
        }
        // Не шире дырки (0,6 диаметра), иначе сумма легла бы на цветное кольцо; длинная — мельче.
        Text(
            label,
            style = if (label.length > 12) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(HOLE * 0.9f),
        )
    }
}

/**
 * Столбики с подписями и итогом под ними: [summary] — своя строка итога, по умолчанию — сумма всех
 * столбиков; все нули — [empty]. [selected] null — все столбики яркие.
 */
@Composable
private fun <K> BarChart(
    bars: List<Bar<K>>,
    selected: K?,
    label: (K) -> String,
    onTap: (K) -> Unit,
    empty: String,
    money: (Long) -> String,
    summary: (List<Bar<K>>) -> String = { "Итого: расходы ${money(it.sumOf { b -> b.expense })} · доходы ${money(it.sumOf { b -> b.income })}" },
) {
    if (bars.all { it.expense == 0L && it.income == 0L }) {
        EmptyState("Нет данных", empty, Modifier.fillMaxWidth())
        return
    }
    // ponytail: годы — по столбику на год без прокрутки; при 15+ годах подписи тесные, тогда — горизонтальный скролл.
    Bars(bars, selected, onTap, Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 8.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        for (b in bars) {
            Text(
                label(b.key),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (b.key == selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1,
                // Область нажатия 48dp по высоте, текст по центру.
                modifier = Modifier.weight(1f).clickable { onTap(b.key) }.heightIn(min = 48.dp).wrapContentHeight(),
            )
        }
    }
    Text(summary(bars), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
}

/** Пары столбиков расходы/доходы; выбранный ([selected]) ярче, null — все яркие; нажатие — [onTap]. */
@Composable
private fun <K> Bars(bars: List<Bar<K>>, selected: K?, onTap: (K) -> Unit, modifier: Modifier) {
    val axis = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val income = chartIncomeColor()
    val max = bars.maxOf { maxOf(it.expense, it.income) }.coerceAtLeast(1L)
    Canvas(
        modifier.pointerInput(bars) {
            detectTapGestures { p ->
                val i = (p.x / (size.width / bars.size)).toInt().coerceIn(0, bars.lastIndex)
                onTap(bars[i].key)
            }
        },
    ) {
        val group = size.width / bars.size
        val barW = minOf(group * 0.32f, 24.dp.toPx()) // мало столбиков (1–2 года) — не во всю ширину
        val gap = 2.dp.toPx()
        val radius = 4.dp.toPx()
        val h = size.height
        for ((i, b) in bars.withIndex()) {
            // Невыбранный месяц бледнее: непрозрачный цвет, смешанный с фоном (прозрачность
            // дала бы тёмную полосу там, где квадратный низ ложится на скруглённый столбик).
            val fade = if (selected == null || b.key == selected) 1f else 0.45f
            val x0 = i * group + (group - 2 * barW - gap) / 2f
            for ((j, pair) in listOf(b.expense to ExpenseColor, b.income to income).withIndex()) {
                val (v, color) = pair
                if (v <= 0) continue
                // Высота — только для рисования; минимум 2dp, чтобы малое значение было видно.
                val bh = (v.toDouble() / max * h).toFloat().coerceAtLeast(2.dp.toPx())
                val x = x0 + j * (barW + gap)
                val fill = lerp(surface, color, fade)
                drawRoundRect(fill, Offset(x, h - bh), Size(barW, bh), CornerRadius(radius, radius))
                if (bh > radius) drawRect(fill, Offset(x, h - radius), Size(barW, radius)) // низ без скругления
            }
        }
        drawLine(axis, Offset(0f, h), Offset(size.width, h), strokeWidth = 1.dp.toPx())
    }
}
