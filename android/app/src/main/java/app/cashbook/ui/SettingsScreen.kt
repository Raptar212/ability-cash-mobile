package app.cashbook.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.cashbook.data.AccountBalance
import app.cashbook.data.CashDao
import app.cashbook.reminder.Reminder
import app.cashbook.widget.BalanceWidget
import java.time.LocalTime

/**
 * Порядок счетов — в настройках телефона (SharedPreferences `accounts`, «3,1,7»), не в базе:
 * после импорта копии базы сбрасывается на «по имени» (решение владельца).
 */
object AccountOrder {
    private fun prefs(context: Context) = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    fun load(context: Context): List<Long> =
        prefs(context).getString("order", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    fun save(context: Context, ids: List<Long>) {
        prefs(context).edit().putString("order", ids.joinToString(",")).apply()
    }

    /** [items] в порядке [order]; не упомянутые (новые) — в конце, в прежнем порядке (по имени). */
    fun <T> sort(items: List<T>, order: List<Long>, id: (T) -> Long): List<T> {
        val pos = order.withIndex().associate { (i, v) -> v to i }
        return items.sortedBy { pos[id(it)] ?: Int.MAX_VALUE } // сортировка устойчивая
    }
}

/** Счета в порядке пользователя: порядок читается при входе экрана в композицию. */
@Composable
fun List<AccountBalance>.inUserOrder(): List<AccountBalance> {
    val context = LocalContext.current
    val order = remember { AccountOrder.load(context) }
    return AccountOrder.sort(this, order) { it.accountId }
}

/** Пункт настроек: название, подпись (что внутри раздела) и действие. */
class SettingsItem(val title: String, val subtitle: String? = null, val onClick: () -> Unit)

/** Экран-список пунктов: сами настройки (☰) и их разделы. */
@Composable
fun SettingsScreen(title: String, items: List<SettingsItem>, onBack: () -> Unit) {
    SettingsScaffold(title, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(items) { item ->
                ListItem(
                    headlineContent = { Text(item.title) },
                    supportingContent = item.subtitle?.let { { Text(it) } },
                    modifier = Modifier.clickable(onClick = item.onClick),
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text(title) },
            )
        },
    ) { padding -> Column(Modifier.padding(padding)) { content() } }
}

/** Настройки → «Внешний вид»: тема приложения (сохраняет вызывающий) и переход к настройкам виджета. */
@Composable
fun AppearanceScreen(theme: AppTheme, onTheme: (AppTheme) -> Unit, onWidget: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold("Внешний вид", onBack) {
        Text("Тема", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in AppTheme.entries) {
                FilterChip(selected = theme == t, onClick = { onTheme(t) }, label = { Text(t.title) })
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        ListItem(
            headlineContent = { Text("Виджет") },
            supportingContent = { Text("Что видно на виджете «Баланс»") },
            modifier = Modifier.clickable(onClick = onWidget),
        )
        HorizontalDivider()
    }
}

/** Настройки → «Напоминание»: раз в день «Запишите свои траты за сегодня» в выбранное время. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Разрешение отозвали в настройках телефона — показываем «выключено», а не обещаем напоминание.
    var enabled by remember { mutableStateOf(Reminder.enabled(context) && Reminder.permitted(context)) }
    var time by remember { mutableStateOf(Reminder.time(context)) }
    var picking by remember { mutableStateOf(false) }
    fun save(on: Boolean, at: LocalTime) {
        enabled = on
        time = at
        Reminder.save(context, on, at)
    }
    // Android 13+: без разрешения уведомление не покажется — включаем только после «Разрешить».
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            save(true, time)
        } else {
            Toast.makeText(context, "Разрешите уведомления для Cashbook в настройках телефона", Toast.LENGTH_LONG).show()
        }
    }

    SettingsScaffold("Напоминание", onBack) {
        ListItem(
            headlineContent = { Text("Напоминать каждый день") },
            supportingContent = { Text("«Запишите свои траты за сегодня»") },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = enabled, role = Role.Switch) { on ->
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Reminder.permitted(context)) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    save(on, time)
                }
            },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Время") },
            supportingContent = { Text("%02d:%02d".format(time.hour, time.minute)) },
            modifier = Modifier.clickable(enabled = enabled, onClickLabel = "Изменить время") { picking = true },
        )
        HorizontalDivider()
    }

    if (picking) {
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    save(enabled, LocalTime.of(state.hour, state.minute))
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
            text = { TimePicker(state) },
        )
    }
}

/** Настройки → «Порядок счетов»: стрелки ↑ ↓; каждое изменение сразу сохраняется (и на виджете). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountOrderScreen(dao: CashDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val balances by remember { dao.balances() }.collectAsState(initial = null)
    var order by remember { mutableStateOf(AccountOrder.load(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Порядок счетов") },
            )
        },
    ) { padding ->
        val accs = AccountOrder.sort(balances ?: return@Scaffold, order) { it.accountId }
        fun move(from: Int, to: Int) {
            val ids = accs.map { it.accountId }.toMutableList()
            ids.add(to, ids.removeAt(from))
            order = ids
            AccountOrder.save(context, ids)
            BalanceWidget.refresh(context)
        }
        if (accs.isEmpty()) {
            Text("Счетов нет", Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(accs, key = { _, a -> a.accountId }) { i, a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = { Text("${fmt(a.balance, 0)} ${symbol(a.currencyCode)}") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { move(i, i - 1) }, enabled = i > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, "«${a.name}» выше")
                            }
                            IconButton(onClick = { move(i, i + 1) }, enabled = i < accs.lastIndex) {
                                Icon(Icons.Filled.KeyboardArrowDown, "«${a.name}» ниже")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
