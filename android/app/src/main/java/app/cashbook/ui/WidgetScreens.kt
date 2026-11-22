package app.cashbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.cashbook.data.CashDao
import app.cashbook.data.CategoryKind
import app.cashbook.widget.BalanceWidget
import app.cashbook.widget.WidgetSettings
import app.cashbook.widget.WidgetTheme

/** Меню → «Виджет»: что видно на виджете «Баланс». Каждое изменение сразу сохраняется и видно на столе. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(dao: CashDao, onBack: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(WidgetSettings.load(context)) }
    val unordered by remember { dao.balances() }.collectAsState(initial = emptyList())
    val balances = unordered.inUserOrder()
    fun update(n: WidgetSettings) {
        s = n
        WidgetSettings.save(context, n)
        BalanceWidget.refresh(context)
    }
    val codes = balances.map { it.currencyCode }.distinct()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Виджет") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Долгое нажатие на рабочем столе → Виджеты → Cashbook. Виджет растягивается: внизу видны расходы и счета.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SwitchRow("Остаток по валютам", "Сумма всех счетов одной валюты", s.showTotals) { update(s.copy(showTotals = it)) }
            if (s.showTotals || s.showExpenses) {
                Chips {
                    for (code in codes) {
                        FilterChip(
                            selected = code !in s.hiddenCurrencies,
                            onClick = {
                                val hidden = if (code in s.hiddenCurrencies) s.hiddenCurrencies - code else s.hiddenCurrencies + code
                                update(s.copy(hiddenCurrencies = hidden))
                            },
                            label = { Text("$code ${symbol(code)}") },
                        )
                    }
                }
            }
            SwitchRow("Расходы за месяц", null, s.showExpenses) { update(s.copy(showExpenses = it)) }
            SwitchRow("Отдельные счета", "Ничего не отмечено — все счета", s.showAccounts) { update(s.copy(showAccounts = it)) }
            if (s.showAccounts) {
                Chips {
                    for (b in balances) {
                        FilterChip(
                            selected = b.accountId in s.accountIds,
                            onClick = {
                                val ids = if (b.accountId in s.accountIds) s.accountIds - b.accountId else s.accountIds + b.accountId
                                update(s.copy(accountIds = ids))
                            },
                            label = { Text(b.name) },
                        )
                    }
                }
            }
            SwitchRow("Кнопка «+ расход»", "Счёт по умолчанию:", s.showAdd) { update(s.copy(showAdd = it)) }
            if (s.showAdd) {
                val current = balances.find { it.accountId == s.addAccountId } ?: balances.firstOrNull()
                Chips {
                    for (b in balances) {
                        FilterChip(
                            selected = b.accountId == current?.accountId,
                            onClick = { update(s.copy(addAccountId = b.accountId)) },
                            label = { Text(b.name) },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Вид", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Chips {
                for (t in WidgetTheme.entries) {
                    FilterChip(selected = s.theme == t, onClick = { update(s.copy(theme = t)) }, label = { Text(t.title) })
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = hint?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        // Роль переключателя: TalkBack говорит «переключатель, включено/выключено».
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * «+» на виджете: счёт (по умолчанию [accountId]) и статья расхода верхнего уровня → окно суммы
 * через [onPick] (подстатья выбирается уже там).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickExpenseScreen(dao: CashDao, accountId: Long, onPick: (account: Long, category: Long) -> Unit, onBack: () -> Unit) {
    val balances by remember { dao.balances() }.collectAsState(initial = null)
    val categories by remember { dao.categories(CategoryKind.EXPENSE) }.collectAsState(initial = null)
    var account by rememberSaveable { mutableStateOf(accountId) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Новый расход") },
            )
        },
    ) { padding ->
        val accs = balances?.inUserOrder() ?: return@Scaffold
        val tops = categories?.filter { it.parentId == null }?.sortedWith(ByName) ?: return@Scaffold
        val selected = accs.find { it.accountId == account } ?: accs.firstOrNull()
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("Со счёта", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 8.dp))
            Chips {
                for (b in accs) {
                    FilterChip(
                        selected = b.accountId == selected?.accountId,
                        onClick = { account = b.accountId },
                        label = { Text("${b.name} · ${fmt(b.balance, 0)} ${symbol(b.currencyCode)}") },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            if (selected == null) {
                Text("Счетов нет — заведите счёт в приложении", modifier = Modifier.padding(16.dp))
                return@Column
            }
            LazyColumn {
                items(tops, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(selected.accountId, c.id) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text(c.name, style = MaterialTheme.typography.bodyLarge) }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Перевод без перетаскивания (TalkBack, моторика): лента счёта [fromId] → «Перевод» → счёт
 * зачисления → окно суммы через [onPick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferTargetScreen(dao: CashDao, fromId: Long, onPick: (to: Long) -> Unit, onBack: () -> Unit) {
    val balances by remember { dao.balances() }.collectAsState(initial = null)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Перевод на счёт") },
            )
        },
    ) { padding ->
        val accs = balances?.inUserOrder() ?: return@Scaffold
        val from = accs.find { it.accountId == fromId }
        val targets = accs.filter { it.accountId != fromId }
        Column(Modifier.padding(padding).fillMaxSize()) {
            from?.let {
                Text("Со счёта «${it.name}»", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 8.dp))
                HorizontalDivider()
            }
            if (from == null || targets.isEmpty()) {
                Text("Нужен ещё один активный счёт", modifier = Modifier.padding(16.dp))
                return@Column
            }
            LazyColumn {
                items(targets, key = { it.accountId }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(b.accountId) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(b.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${fmt(b.balance, 0)} ${symbol(b.currencyCode)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
