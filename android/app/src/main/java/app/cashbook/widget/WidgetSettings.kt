package app.cashbook.widget

import android.content.Context
import app.cashbook.data.AccountBalance
import app.cashbook.ui.fmt
import app.cashbook.ui.symbol

enum class WidgetTheme(val title: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлый"),
    DARK("Тёмный"),
    TRANSPARENT("Прозрачный"),
}

/**
 * Что видно на виджете. Одни настройки на все виджеты, хранятся в SharedPreferences `widget`.
 * Валюты — список скрытых: новая валюта видна сразу. [accountIds] пуст — все активные счета.
 * [addAccountId] null или счёт исчез — первый активный.
 */
data class WidgetSettings(
    val showTotals: Boolean = true,
    val hiddenCurrencies: Set<String> = emptySet(),
    val showExpenses: Boolean = false,
    val showAccounts: Boolean = false,
    val accountIds: Set<Long> = emptySet(),
    val showAdd: Boolean = true,
    val addAccountId: Long? = null,
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
) {
    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("widget", Context.MODE_PRIVATE)

        fun load(context: Context): WidgetSettings {
            val p = prefs(context)
            return WidgetSettings(
                showTotals = p.getBoolean("totals", true),
                hiddenCurrencies = p.getStringSet("hiddenCurrencies", null).orEmpty().toSet(),
                showExpenses = p.getBoolean("expenses", false),
                showAccounts = p.getBoolean("accounts", false),
                accountIds = p.getStringSet("accountIds", null).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
                showAdd = p.getBoolean("add", true),
                addAccountId = p.getLong("addAccountId", 0L).takeIf { it > 0 },
                theme = WidgetTheme.entries.find { it.name == p.getString("theme", null) } ?: WidgetTheme.SYSTEM,
            )
        }

        fun save(context: Context, s: WidgetSettings) {
            prefs(context).edit()
                .putBoolean("totals", s.showTotals)
                .putStringSet("hiddenCurrencies", s.hiddenCurrencies)
                .putBoolean("expenses", s.showExpenses)
                .putBoolean("accounts", s.showAccounts)
                .putStringSet("accountIds", s.accountIds.map { it.toString() }.toSet())
                .putBoolean("add", s.showAdd)
                .putLong("addAccountId", s.addAccountId ?: 0L)
                .putString("theme", s.theme.name)
                .apply()
        }
    }
}

/** Текст виджета; null — раздел выключен. [addAccountId] null — кнопки «+» нет. */
data class WidgetContent(val totals: String?, val expenses: String?, val accounts: String?, val addAccountId: Long?)

/**
 * Содержимое виджета: [balances] — активные счета, [monthExpenses] — расходы текущего месяца по
 * коду валюты, [precisions] — точность по коду. Суммы одной валюты складываются (счета без пересчёта).
 */
fun widgetContent(
    s: WidgetSettings,
    balances: List<AccountBalance>,
    monthExpenses: Map<String, Long>,
    precisions: Map<String, Int>,
): WidgetContent {
    // Нет сумм — «0»; суммы есть, но все валюты скрыты — «—», а не ложный ноль.
    fun line(sums: Map<String, Long>, sep: String = " · ") = sums.filterKeys { it !in s.hiddenCurrencies }
        .map { (code, v) -> "${fmt(v, precisions[code] ?: 2)} ${symbol(code)}" }
        .ifEmpty { listOf(if (sums.isEmpty()) "0" else "—") }
        .joinToString(sep)
    val totals = balances.groupBy { it.currencyCode }.mapValues { (_, v) -> v.sumOf { it.balance } }
    val shown = if (s.accountIds.isEmpty()) balances else balances.filter { it.accountId in s.accountIds }
    return WidgetContent(
        totals = line(totals, sep = "\n").takeIf { s.showTotals }, // валюта на строку: в одну не влезают
        expenses = "Расходы за месяц: ${line(monthExpenses)}".takeIf { s.showExpenses },
        accounts = shown.joinToString("\n") { "${it.name}: ${fmt(it.balance, it.precision)} ${symbol(it.currencyCode)}" }
            .ifEmpty { "Счетов нет" }
            .takeIf { s.showAccounts },
        addAccountId = if (s.showAdd) (balances.find { it.accountId == s.addAccountId } ?: balances.firstOrNull())?.accountId else null,
    )
}
