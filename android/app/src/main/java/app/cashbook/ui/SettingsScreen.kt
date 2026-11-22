package app.cashbook.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cashbook.data.AccountBalance
import app.cashbook.data.CashDao
import app.cashbook.widget.BalanceWidget

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

/** Пункт настроек: название и действие. */
class SettingsItem(val title: String, val onClick: () -> Unit)

/** Настройки — отдельным экраном вместо выпадающего меню. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(items: List<SettingsItem>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Настройки") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(items) { item ->
                ListItem(headlineContent = { Text(item.title) }, modifier = Modifier.clickable(onClick = item.onClick))
                HorizontalDivider()
            }
        }
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
