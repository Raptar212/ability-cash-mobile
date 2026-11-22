package app.cashbook.ui

import androidx.compose.runtime.saveable.listSaver
import app.cashbook.data.Account
import app.cashbook.data.Currency
import app.cashbook.data.Money

/** Поля формы счёта как ввёл пользователь — строки, до проверки. */
data class AccountDraft(
    val name: String = "",
    val currencyId: Long? = null,
    val balance: String = "0",
    val comment: String = "",
    /** ARGB; null — не выбран (палитра по id). */
    val color: Int? = null,
) {
    companion object {
        fun of(account: Account, precision: Int) = AccountDraft(
            name = account.name,
            currencyId = account.currencyId,
            balance = Money.format(account.startingBalance, precision),
            comment = account.comment,
            color = account.color,
        )
    }
}

/** Черновик формы переживает поворот экрана и смерть процесса (Bundle). */
val DraftSaver = listSaver<AccountDraft, Any?>(
    save = { listOf(it.name, it.currencyId, it.balance, it.comment, it.color) },
    restore = { AccountDraft(it[0] as String, it[1] as Long?, it[2] as String, it[3] as String, it[4] as Int?) },
)

val ErrorsSaver = listSaver<Validated.Errors, String?>(
    save = { listOf(it.name, it.currency, it.balance) },
    restore = { Validated.Errors(it[0], it[1], it[2]) },
)

sealed interface Validated {
    data class Ok(val account: Account) : Validated
    data class Errors(val name: String? = null, val currency: String? = null, val balance: String? = null) : Validated
}

/** Проверка формы. [existing] — правка этого счёта (id и архив сохраняются), null — новый счёт. */
fun AccountDraft.validate(currencies: List<Currency>, existing: Account? = null): Validated {
    val nameError = if (name.isBlank()) "Введите название" else null
    val currency = currencies.find { it.id == currencyId }
    val currencyError = if (currency == null) "Выберите валюту" else null
    var amount = 0L
    val balanceError = when {
        balance.isBlank() -> null
        else -> try {
            amount = Money.parse(balance)
            val step = Money.pow10(4 - (currency?.precision ?: 4))
            if (amount % step != 0L) "Не больше ${currency?.precision} знаков после запятой" else null
        } catch (e: IllegalArgumentException) {
            "Неверная сумма"
        }
    }
    if (nameError != null || currencyError != null || balanceError != null) {
        return Validated.Errors(nameError, currencyError, balanceError)
    }
    val base = existing ?: Account(name = "", currencyId = currency!!.id)
    return Validated.Ok(
        base.copy(name = name.trim(), currencyId = currency!!.id, startingBalance = amount, comment = comment.trim(), color = color),
    )
}
