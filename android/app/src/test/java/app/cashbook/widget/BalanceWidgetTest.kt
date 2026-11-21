package app.cashbook.widget

import androidx.test.core.app.ApplicationProvider
import app.cashbook.data.AccountBalance
import app.cashbook.data.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceWidgetTest {
    private fun acc(id: Long, code: String, balance: String, precision: Int = 2) =
        AccountBalance(id, "Счёт $id", code, precision, Money.parse(balance))

    private val wallet = AccountBalance(1, "Кошелёк", "UAH", 2, Money.parse("1000"))
    private val card = AccountBalance(2, "Карта", "UAH", 2, Money.parse("2300"))
    private val box = AccountBalance(3, "Коробка", "USD", 2, Money.parse("300"))
    private val all = listOf(wallet, card, box)
    private val precisions = mapOf("UAH" to 2, "USD" to 2, "JPY" to 0)

    private fun plain(s: String?) = s?.replace(' ', ' ')

    @Test fun sameCurrencyAccountsAreSummed() {
        val c = widgetContent(WidgetSettings(), all, emptyMap(), precisions)
        assertEquals("3 300,00 ₴ · 300,00 $", plain(c.totals)) // 1000 + 2300
        assertNull(c.expenses)
        assertNull(c.accounts)
        assertEquals(1L, c.addAccountId) // счёт не выбран — первый
    }

    @Test fun precisionAndNegativePerCurrency() {
        val c = widgetContent(WidgetSettings(), listOf(acc(1, "UAH", "1000,5"), acc(3, "UAH", "-200"), acc(4, "JPY", "15", 0)), emptyMap(), precisions)
        assertEquals("800,50 ₴ · 15 JPY", plain(c.totals))
    }

    @Test fun hiddenCurrenciesSelectedAccountsAndExpenses() {
        val s = WidgetSettings(
            hiddenCurrencies = setOf("USD"), showExpenses = true, showAccounts = true,
            accountIds = setOf(2, 3), addAccountId = 3,
        )
        val c = widgetContent(s, all, mapOf("UAH" to Money.parse("450"), "USD" to Money.parse("5")), precisions)
        assertEquals("3 300,00 ₴", plain(c.totals))
        assertEquals("Расходы за месяц: 450,00 ₴", plain(c.expenses))
        assertEquals("Карта: 2 300,00 ₴\nКоробка: 300,00 $", plain(c.accounts)) // счёт показывается и в скрытой валюте
        assertEquals(3L, c.addAccountId)
        val allHidden = widgetContent(WidgetSettings(hiddenCurrencies = setOf("UAH", "USD")), all, emptyMap(), precisions)
        assertEquals("—", allHidden.totals) // не ложный ноль
    }

    @Test fun sectionsOffAndEmptyData() {
        val off = widgetContent(WidgetSettings(showTotals = false, showAdd = false), all, emptyMap(), precisions)
        assertNull(off.totals)
        assertNull(off.addAccountId)
        val empty = widgetContent(WidgetSettings(showExpenses = true, showAccounts = true, addAccountId = 99), emptyList(), emptyMap(), precisions)
        assertEquals("0", empty.totals)
        assertEquals("Расходы за месяц: 0", empty.expenses)
        assertEquals("Счетов нет", empty.accounts)
        assertNull(empty.addAccountId) // счетов нет — кнопки нет
    }

    @Test fun settingsRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(WidgetSettings(), WidgetSettings.load(ctx)) // по умолчанию
        val s = WidgetSettings(false, setOf("EUR"), true, true, setOf(5, 7), false, 7, WidgetTheme.DARK)
        WidgetSettings.save(ctx, s)
        assertEquals(s, WidgetSettings.load(ctx))
    }
}
