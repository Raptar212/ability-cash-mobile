package app.cashbook.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import app.cashbook.MainActivity
import app.cashbook.R
import app.cashbook.data.AppDatabase
import app.cashbook.data.Backup
import app.cashbook.ui.AccountOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Виджет «Баланс»: что на нём видно — [WidgetSettings] (меню приложения → «Виджет»). */
class BalanceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Под замком Backup.restore: иначе база может открыться между reset() и заменой
                // файла — и приложение осталось бы на удалённом старом файле.
                val dao = synchronized(Backup) { AppDatabase.get(context) }.dao()
                val month = YearMonth.now()
                val expenses = dao.daySums(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay()).first()
                    .filter { it.expense }
                    .groupBy({ it.currencyCode }, { it.amount })
                    .mapValues { it.value.sum() }
                val precisions = dao.currencies().first().associate { it.code to it.precision }
                val settings = WidgetSettings.load(context)
                val views = views(context, widgetContent(settings, AccountOrder.sort(dao.balances().first(), AccountOrder.load(context)) { it.accountId }, expenses, precisions), settings.theme)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                Log.w("BalanceWidget", "Не обновился, остаётся прежнее значение", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Extra интента MainActivity: id счёта — открыть быстрый ввод расхода. */
        const val EXTRA_ADD_ACCOUNT = "app.cashbook.ADD_EXPENSE_ACCOUNT"

        /** Перерисовать все виджеты на рабочем столе; виджетов нет — ничего. */
        fun refresh(context: Context) {
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, BalanceWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, BalanceWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        /**
         * Цвета темы: фон (непрозрачный цвет + прозрачность 0..255, 0 — без фона), основной текст,
         * подписи; SYSTEM — null, всё из ресурсов (values-night). Только числа, без id ресурсов:
         * часть лаунчеров ищет id из setInt в своих ресурсах.
         */
        private data class Palette(val background: Int, val alpha: Int, val text: Int, val muted: Int)

        private fun palette(theme: WidgetTheme): Palette? = when (theme) {
            WidgetTheme.SYSTEM -> null
            WidgetTheme.LIGHT -> Palette(0xFFFFFFFF.toInt(), 242, 0xFF212121.toInt(), 0xFF616161.toInt())
            WidgetTheme.DARK -> Palette(0xFF202124.toInt(), 230, 0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt())
            WidgetTheme.TRANSPARENT -> Palette(0, 0, 0xFFFFFFFF.toInt(), 0xFFEEEEEE.toInt())
        }

        private fun views(context: Context, c: WidgetContent, theme: WidgetTheme) =
            // «Прозрачный» — текст прямо на обоях: макет с тенью, иначе на светлых обоях не прочитать.
            RemoteViews(
                context.packageName,
                if (theme == WidgetTheme.TRANSPARENT) R.layout.widget_balance_shadow else R.layout.widget_balance,
            ).apply {
                fun show(id: Int, text: String?) {
                    setViewVisibility(id, if (text == null) View.GONE else View.VISIBLE)
                    if (text != null) setTextViewText(id, text)
                }
                setViewVisibility(R.id.title, if (c.totals == null) View.GONE else View.VISIBLE)
                show(R.id.amounts, c.totals)
                show(R.id.expenses, c.expenses)
                show(R.id.accounts, c.accounts)

                palette(theme)?.let { p ->
                    if (p.alpha == 0) {
                        setViewVisibility(R.id.bg, View.GONE)
                    } else {
                        setInt(R.id.bg, "setColorFilter", p.background) // SRC_ATOP: форма (скругление) сохраняется
                        setInt(R.id.bg, "setImageAlpha", p.alpha)
                    }
                    for (id in listOf(R.id.amounts, R.id.accounts)) setTextColor(id, p.text)
                    for (id in listOf(R.id.title, R.id.expenses)) setTextColor(id, p.muted)
                }

                val open = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(android.R.id.background, open)

                setViewVisibility(R.id.add, if (c.addAccountId == null) View.GONE else View.VISIBLE)
                if (c.addAccountId != null) {
                    // Свежая задача: быстрый ввод не ложится поверх открытого экрана приложения.
                    val add = Intent(context, MainActivity::class.java)
                        .putExtra(EXTRA_ADD_ACCOUNT, c.addAccountId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    setOnClickPendingIntent(
                        R.id.add,
                        PendingIntent.getActivity(context, 1, add, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
    }
}
