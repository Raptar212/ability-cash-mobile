package app.cashbook.ui

import app.cashbook.data.Account
import app.cashbook.data.Currency
import app.cashbook.data.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDraftTest {
    private val uah = Currency(id = 1, code = "UAH", name = "Гривна")
    private val jpy = Currency(id = 2, code = "JPY", name = "Иена", precision = 0)
    private val currencies = listOf(uah, jpy)

    private fun ok(v: Validated): Account = (v as Validated.Ok).account
    private fun errors(v: Validated): Validated.Errors = v as Validated.Errors

    @Test fun newAccountFromValidDraft() {
        val a = ok(AccountDraft(name = "  Карта ", currencyId = 1, balance = "1 234,50", comment = " моно ").validate(currencies))
        assertEquals(0L, a.id)
        assertEquals("Карта", a.name)
        assertEquals(1L, a.currencyId)
        assertEquals(Money.parse("1234,5"), a.startingBalance)
        assertEquals("моно", a.comment)
    }

    @Test fun blankBalanceIsZeroAndNegativeAllowed() {
        assertEquals(0L, ok(AccountDraft(name = "A", currencyId = 1, balance = "").validate(currencies)).startingBalance)
        assertEquals(Money.parse("-50"), ok(AccountDraft(name = "A", currencyId = 1, balance = "-50").validate(currencies)).startingBalance)
    }

    @Test fun reportsAllErrorsAtOnce() {
        val e = errors(AccountDraft(name = " ", currencyId = 99, balance = "abc").validate(currencies))
        assertTrue(e.name != null && e.currency != null && e.balance != null)
    }

    @Test fun balanceLimitedToCurrencyPrecision() {
        assertTrue(errors(AccountDraft(name = "A", currencyId = 1, balance = "1,001").validate(currencies)).balance != null)
        assertTrue(errors(AccountDraft(name = "A", currencyId = 2, balance = "1,5").validate(currencies)).balance != null)
        assertEquals(Money.parse("15"), ok(AccountDraft(name = "A", currencyId = 2, balance = "15").validate(currencies)).startingBalance)
    }

    @Test fun editKeepsIdAndArchiveFlag() {
        val existing = Account(id = 7, name = "Old", currencyId = 1, startingBalance = 0, archived = true)
        val draft = AccountDraft.of(existing.copy(startingBalance = Money.parse("10")), uah.precision)
        assertEquals("10,00", draft.balance)
        val a = ok(draft.copy(name = "New").validate(currencies, existing))
        assertEquals(7L, a.id)
        assertEquals("New", a.name)
        assertEquals(Money.parse("10"), a.startingBalance)
        assertTrue(a.archived)
    }
}
