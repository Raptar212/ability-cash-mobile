package app.cashbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cashbook.data.CashDao
import app.cashbook.data.Operation
import app.cashbook.data.OperationType
import app.cashbook.data.type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val KEYS = listOf("789÷", "456×", "123−", ",0⌫+")
private const val FIELD_AMOUNT = 0
private const val FIELD_RATE = 1
private const val FIELD_SECOND = 2
private val KEYPAD_MIN = 240.dp
private val SAVE_HEIGHT = 68.dp // кнопка 52dp + отступы 8dp сверху и снизу

/**
 * Ввод операции после перетаскивания [initialFrom] → [initialTo]; [editing] — правка существующей.
 * У расхода и дохода счёт меняется в окне. [onSaved] — новая операция записана (id, текст для
 * «Отменить»), вызывается перед [onDone]. [template] — новый ввод по образцу прежней операции
 * (суммы, статья, комментарий; дата — сегодня), не правка. [onRepeat] — «Повторить сегодня» в правке.
 * [onDeleted] — операция удалена сразу, без вопроса (для «Отменить»: сама операция и текст).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationScreen(
    dao: CashDao,
    initialFrom: Node,
    initialTo: Node,
    onDone: () -> Unit,
    editing: Operation? = null,
    template: Operation? = null,
    onRepeat: (() -> Unit)? = null,
    onSaved: (id: Long, text: String) -> Unit = { _, _ -> },
    onDeleted: (Operation, String) -> Unit = { _, _ -> },
) {
    val seed = editing ?: template // поля берутся из правимой или образцовой операции
    // Правка — и для операций архивного счёта; новый ввод — только активные счета.
    val balances by remember { if (editing != null) dao.accountsWithArchived() else dao.balances() }
        .collectAsState(initial = null)
    val categories by remember { dao.allCategories() }.collectAsState(initial = null)
    val currencies by remember { dao.currencies() }.collectAsState(initial = null)
    val activeUnordered by remember { dao.balances() }.collectAsState(initial = emptyList()) // для смены счёта
    val active = activeUnordered.inUserOrder()

    // Правка: сумма списания (для дохода — поступления); вторая — зачисление при переводе
    // или сумма в базовой валюте у расхода/дохода. Вторая введена руками — она главнее курса.
    var expr by rememberSaveable { mutableStateOf(seed?.let { toExpr(it.expenseAmount ?: it.incomeAmount!!) } ?: "") }
    var expr2 by rememberSaveable {
        mutableStateOf(
            seed?.let { if (it.type == OperationType.TRANSFER) it.incomeAmount else it.baseAmount }?.let(::toExpr) ?: "",
        )
    }
    var rateExpr by rememberSaveable { mutableStateOf("") }
    var ratePerFrom by rememberSaveable { mutableStateOf(true) }
    var rateFresh by rememberSaveable { mutableStateOf(true) } // первая клавиша в курсе вводит его заново
    var field by rememberSaveable { mutableStateOf(FIELD_AMOUNT) } // куда вводит калькулятор
    var categoryId by rememberSaveable { mutableStateOf(seed?.categoryId) }
    var date by rememberSaveable { mutableStateOf(editing?.date ?: LocalDate.now().toEpochDay()) }
    var comment by rememberSaveable { mutableStateOf(seed?.comment ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var commentFocused by remember { mutableStateOf(false) } // клавиатура открыта — калькулятор спрятан
    var switched by rememberSaveable { mutableStateOf<Long?>(null) } // счёт, выбранный в окне
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current

    val type0 = dropType(initialFrom, initialTo)
    val from = switched?.takeIf { type0 == OperationType.EXPENSE }?.let { Node(NodeKind.ACCOUNT, it) } ?: initialFrom
    val to = switched?.takeIf { type0 == OperationType.INCOME }?.let { Node(NodeKind.ACCOUNT, it) } ?: initialTo

    val accounts = balances?.associateBy { it.accountId } ?: return
    val cats = categories ?: return
    val base = (currencies ?: return).firstOrNull() // базовая — первая по id
    val type = dropType(from, to)
    val fromAcc = if (from.kind == NodeKind.ACCOUNT) accounts[from.id] else null
    val toAcc = if (to.kind == NodeKind.ACCOUNT) accounts[to.id] else null
    // Пара не создаёт операцию или счёт исчез (архив) — закрыть экран.
    if (type == null || (from.kind == NodeKind.ACCOUNT && fromAcc == null) || (to.kind == NodeKind.ACCOUNT && toAcc == null)) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    fun name(n: Node) = if (n.kind == NodeKind.ACCOUNT) accounts[n.id]?.name else cats.find { it.id == n.id }?.name
    val amountAcc = fromAcc ?: toAcc!!
    // Вторая сумма: зачисление при обмене или расход/доход валютного счёта в базовой валюте.
    val (secondCode, secondPrecision) = when {
        type == OperationType.TRANSFER ->
            if (fromAcc!!.currencyCode != toAcc!!.currencyCode) toAcc.currencyCode to toAcc.precision else null to 0
        base != null && amountAcc.currencyCode != base.code -> base.code to base.precision
        else -> null to 0
    }
    val two = secondCode != null
    // Старый расход/доход без пересчёта: правка не навязывает курс и вторую сумму.
    // Счёт сменили в окне — операция уже новая по счёту, курс и вторая сумма нужны как обычно.
    val legacy = switched == null && editing != null && editing.type != OperationType.TRANSFER && editing.baseAmount == null

    // Курс по умолчанию — из последней операции с этой парой валют (и заново при смене счёта).
    LaunchedEffect(amountAcc.currencyCode, secondCode) {
        if (secondCode == null || legacy || rateExpr.isNotEmpty()) return@LaunchedEffect
        // Правка — курс самой операции (стёрли вторую сумму — вернулись к нему), иначе последний.
        // Счёт сменили в окне — пара валют уже другая, курс самой операции не годится.
        val own = editing?.takeIf { switched == null }?.let { e ->
            val second = if (e.type == OperationType.TRANSFER) e.incomeAmount else e.baseAmount
            second?.let { rateOf(e.expenseAmount ?: e.incomeAmount!!, it) }
        }
        val last = own ?: dao.lastExchange(amountAcc.currencyCode, secondCode)?.let { rateFrom(it, amountAcc.currencyCode) }
        if (rateExpr.isNotEmpty()) return@LaunchedEffect // пока грузили, ввели руками
        if (last != null) {
            ratePerFrom = last.perFrom
            rateExpr = toExpr(last.value)
        } else {
            ratePerFrom = amountAcc.currencyCode != base?.code // «1 $ = … ₴», а не «1 ₴ = … $»
        }
    }

    val amount = Calc.eval(expr, amountAcc.precision)
    val manual = expr2.isNotEmpty()
    val typedRate = Calc.eval(rateExpr, 4)?.let { Rate(ratePerFrom, it) }
    val received = when {
        !two -> null
        manual -> Calc.eval(expr2, secondPrecision)
        else -> amount?.takeIf { it > 0 }?.let { a -> typedRate?.let { convert(a, it, secondPrecision) } }
    }
    // Вторая сумма введена — курс показывается из сумм, а не из поля.
    val shownRate = if (manual && amount != null && amount > 0 && received != null && received > 0) rateOf(amount, received) else typedRate
    val rateText = if (manual) shownRate?.let { toExpr(it.value) } ?: "?" else rateExpr.ifEmpty { "?" }

    val rootCategory = when (type) {
        OperationType.INCOME -> from.id
        OperationType.EXPENSE -> to.id
        OperationType.TRANSFER -> null
    }
    val subs = rootCategory?.let { descendants(cats, it) }.orEmpty()
    // Подсказки комментария — по выбранной подстатье, иначе по статье кружка; перевод — без статьи.
    val hintCategory = categoryId ?: rootCategory
    val recent by produceState(emptyList<String>(), hintCategory) {
        value = hintCategory?.let { dao.recentComments(it) }.orEmpty()
    }
    val hints = commentHints(recent, comment)
    val typeLabel = when (type) {
        OperationType.EXPENSE -> "Расход"
        OperationType.INCOME -> "Доход"
        OperationType.TRANSFER -> "Перевод"
    }
    val typeColor = when (type) {
        OperationType.EXPENSE -> MaterialTheme.colorScheme.error
        OperationType.INCOME -> incomeColor()
        OperationType.TRANSFER -> MaterialTheme.colorScheme.primary
    }

    fun save() {
        if (saving) return // повторное ✓ в том же кадре — без второй операции
        error = amountError(amount) ?: when {
            !two -> null
            received == null && !manual -> if (legacy) null else "Укажите курс или сумму в $secondCode"
            else -> amountError(received)?.let { "$secondCode: $it" }
        }
        if (error != null) return
        saving = true
        scope.launch {
            try {
                val op = buildOperation(from, to, amount!!, received, categoryId, date, 0, comment.trim())
                if (editing != null) {
                    dao.saveEdit(editing, op)
                } else {
                    val id = dao.insertAtEndOfDay(op)
                    onSaved(id, "$typeLabel ${fmt(amount, amountAcc.precision)} ${symbol(amountAcc.currencyCode)}")
                }
                onDone() // экран закрывается; saving не сбрасываем — до закрытия ✓ ещё нажимаемо
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                error = "Не удалось сохранить: ${e.message}"
            }
        }
    }

    // Без вопроса: ошибку не прячет диалог, а вернуть можно «Отменить» в снекбаре.
    fun delete() {
        if (editing == null || saving) return
        saving = true
        scope.launch {
            try {
                dao.deleteOperation(editing)
                val acc = accounts[editing.expenseAccountId ?: editing.incomeAccountId]
                val sum = editing.expenseAmount ?: editing.incomeAmount!!
                onDeleted(editing, "$typeLabel ${fmt(sum, acc?.precision ?: 2)} ${symbol(acc?.currencyCode ?: "?")}")
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                error = "Не удалось удалить: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Отмена") } },
                title = {
                    Column {
                        Text(typeLabel, style = MaterialTheme.typography.labelMedium, color = typeColor)
                        Text("${name(from)} › ${name(to)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (editing != null) {
                        onRepeat?.let { IconButton(onClick = it, enabled = !saving) { Icon(Icons.Filled.Refresh, "Повторить сегодня") } }
                        IconButton(onClick = ::delete, enabled = !saving) { Icon(Icons.Filled.Delete, "Удалить") }
                    }
                    IconButton(onClick = ::save, enabled = !saving) { Icon(Icons.Filled.Check, "Сохранить") }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).imePadding().fillMaxSize()) {
            // Калькулятор не ниже KEYPAD_MIN: при крупном шрифте прокручивается верх, а не сжимаются клавиши.
            // Клавиатура телефона открыта (комментарий) — калькулятора нет, верх до кнопки «Сохранить».
            val topMax = if (commentFocused) maxHeight - SAVE_HEIGHT else (maxHeight - KEYPAD_MIN - SAVE_HEIGHT).coerceAtLeast(96.dp)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.heightIn(max = topMax).verticalScroll(rememberScrollState())) {
                    // Расход и доход: счёт меняется здесь (тап по статье берёт последний).
                    val side = when (type) {
                        OperationType.EXPENSE -> from.id
                        OperationType.INCOME -> to.id
                        OperationType.TRANSFER -> null
                    }
                    if (side != null && active.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Box(Modifier.padding(start = 8.dp)) }
                            items(active, key = { it.accountId }) { a ->
                                FilterChip(
                                    selected = a.accountId == side,
                                    onClick = {
                                        if (a.accountId != side) {
                                            // Другая валюта — курс и вторую сумму заново (эффект курса перезапустится
                                            // по смене валюты); та же — оставить, иначе курс пропал бы без перезагрузки.
                                            if (a.currencyCode != accounts[side]?.currencyCode) {
                                                expr2 = ""
                                                rateExpr = ""
                                                rateFresh = true
                                                field = FIELD_AMOUNT
                                            }
                                            switched = a.accountId
                                            error = null
                                        }
                                    },
                                    label = { Text("${a.name} · ${fmt(a.balance, 0)} ${symbol(a.currencyCode)}") },
                                )
                            }
                        }
                    }
                    val firstLabel = if (type == OperationType.INCOME) "Зачислить" else "Списать"
                    AmountLine(firstLabel.takeIf { two }, expr, amountAcc.currencyCode, amountAcc.precision, active = field == FIELD_AMOUNT) {
                        field = FIELD_AMOUNT
                        focus.clearFocus()
                    }
                    if (secondCode != null) {
                        val perFrom = if (manual) shownRate?.perFrom ?: ratePerFrom else ratePerFrom
                        RateLine(
                            rateText,
                            unit = if (perFrom) amountAcc.currencyCode else secondCode,
                            other = if (perFrom) secondCode else amountAcc.currencyCode,
                            active = field == FIELD_RATE,
                            derived = manual,
                        ) {
                            field = FIELD_RATE
                            rateFresh = true
                            focus.clearFocus()
                        }
                        val secondLabel = when (type) {
                            OperationType.TRANSFER -> "Зачислить"
                            OperationType.EXPENSE -> "Расход"
                            OperationType.INCOME -> "Доход"
                        }
                        AmountLine(
                            secondLabel, expr2, secondCode, secondPrecision, active = field == FIELD_SECOND,
                            hint = received?.takeIf { !manual }?.let(::toExpr),
                        ) {
                            field = FIELD_SECOND
                            focus.clearFocus()
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    if (subs.isNotEmpty()) {
                        LazyRow(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item { Box(Modifier.padding(start = 8.dp)) }
                            items(subs, key = { it.first.id }) { (c, depth) ->
                                FilterChip(
                                    selected = categoryId == c.id,
                                    onClick = { categoryId = if (categoryId == c.id) null else c.id },
                                    label = { Text((if (depth > 0) "· " else "") + c.name) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("Комментарий") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged { commentFocused = it.isFocused },
                    )
                    if (hints.isNotEmpty()) {
                        LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Box(Modifier.padding(start = 8.dp)) }
                            items(hints, key = { it }) { h ->
                                SuggestionChip(
                                    onClick = { comment = h },
                                    label = { Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                                )
                            }
                        }
                    }
                    DateRow(date, onPick = { date = it }, onCalendar = { picking = true })
                }
                if (commentFocused) {
                    Spacer(Modifier.weight(1f))
                } else {
                    Keypad(
                        Modifier.weight(1f),
                        onClear = {
                            error = null
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            when {
                                !two || field == FIELD_AMOUNT -> expr = ""
                                field == FIELD_SECOND -> expr2 = ""
                                else -> rateExpr = ""
                            }
                        },
                    ) { key ->
                    error = null
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    when {
                        !two || field == FIELD_AMOUNT -> expr = Calc.press(expr, key)
                        field == FIELD_SECOND -> expr2 = Calc.press(expr2, key)
                        rateFresh || manual -> {
                            // Курс вводится заново (⌫ — правит показанный); ручная вторая сумма сбрасывается.
                            if (manual) shownRate?.let { ratePerFrom = it.perFrom }
                            val start = if (key == '⌫') rateText.takeIf { it != "?" }.orEmpty() else ""
                            expr2 = ""
                            rateExpr = Calc.press(start, key)
                            rateFresh = false
                        }
                        else -> rateExpr = Calc.press(rateExpr, key)
                    }
                    }
                }
                Button(
                    onClick = ::save,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
                ) { Text(if (editing != null) "Сохранить изменения" else "Сохранить", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }
}

/** Строка курса «1 $ = 41,5 ₴»; [derived] — посчитан из введённых сумм (серым). */
@Composable
private fun RateLine(text: String, unit: String, other: String, active: Boolean, derived: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Курс", style = MaterialTheme.typography.labelLarge)
        Text(
            "1 ${symbol(unit)} = $text ${symbol(other)}",
            style = MaterialTheme.typography.titleMedium,
            color = if (derived) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** [hint] — посчитанная сумма, пока [expr] пуст (серым). */
@Composable
private fun AmountLine(
    label: String?,
    expr: String,
    code: String,
    precision: Int,
    active: Boolean,
    hint: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (active && label != null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label != null) Text(label, style = MaterialTheme.typography.labelLarge)
            // Длинное выражение не обрезает конец: строка прокручивается и стоит на последних цифрах.
            Text(
                groupDigits(expr).ifEmpty { hint ?: "0" },
                color = if (expr.isEmpty() && hint != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f).padding(start = 8.dp).horizontalScroll(rememberScrollState(), reverseScrolling = true),
            )
            Text(" ${symbol(code)}", fontSize = 24.sp)
        }
        // Итог выражения, пока в нём есть действие: «= 18,50».
        if (expr.any { it in Calc.OPS }) {
            Text(
                "= " + (Calc.eval(expr, precision)?.let { fmt(it, precision) } ?: "?"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateRow(date: Long, onPick: (Long) -> Unit, onCalendar: () -> Unit) {
    val today = LocalDate.now().toEpochDay()
    val other = date != today && date != today - 1
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = date == today - 1, onClick = { onPick(today - 1) }, label = { Text("Вчера") })
        FilterChip(selected = date == today, onClick = { onPick(today) }, label = { Text("Сегодня") })
        FilterChip(
            selected = other,
            onClick = onCalendar,
            label = {
                Text(if (other) LocalDate.ofEpochDay(date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) else "Дата…")
            },
        )
    }
}

/** Названия клавиш для TalkBack. */
private val KEY_NAMES = mapOf('÷' to "Разделить", '×' to "Умножить", '−' to "Минус", '+' to "Плюс", ',' to "Запятая", '⌫' to "Стереть")

/** Калькулятор; долгое нажатие ⌫ — [onClear] (стереть поле целиком). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(modifier: Modifier, onClear: () -> Unit, onKey: (Char) -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        for (row in KEYS) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (k in row) {
                    val click = Modifier.combinedClickable(onLongClick = if (k == '⌫') onClear else null) { onKey(k) }
                    val name = KEY_NAMES[k]
                    Box(
                        Modifier.weight(1f).fillMaxSize().then(click)
                            .then(if (name != null) Modifier.semantics { contentDescription = name } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            k.toString(),
                            fontSize = 28.sp,
                            color = if (k in Calc.OPS) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    }
                }
            }
        }
    }
}
