package app.cashbook.data

/** Деньги — Long в десятитысячных долях (как в `.cash`): 12,50 → 125000. */
object Money {
    const val SCALE = 10_000L
    private const val DIGITS = 4
    private const val MAX_WHOLE_DIGITS = 14 // 10^14 * 10^4 < Long.MAX_VALUE
    private const val MAX_ABS = Long.MAX_VALUE - SCALE // запас под округление в format

    /** «1 234,56», «1234.56», «-12» → Long ×10000. Мусор и больше 4 знаков дроби — исключение. */
    fun parse(text: String): Long {
        val s = text.filterNot { it == ' ' || it == ' ' || it == ' ' }.replace(',', '.')
        val negative = s.startsWith("-")
        val body = if (negative || s.startsWith("+")) s.substring(1) else s
        val parts = body.split('.')
        require(parts.size <= 2) { "Не сумма: $text" }
        val whole = parts[0]
        val frac = parts.getOrElse(1) { "" }
        require(whole.isNotEmpty() || frac.isNotEmpty()) { "Не сумма: $text" }
        require(whole.all { it in '0'..'9' } && frac.all { it in '0'..'9' }) { "Не сумма: $text" }
        require(frac.length <= DIGITS) { "Больше $DIGITS знаков после запятой: $text" }
        require(whole.length <= MAX_WHOLE_DIGITS) { "Слишком большая сумма: $text" }
        val value = (whole.ifEmpty { "0" }.toLong()) * SCALE + frac.padEnd(DIGITS, '0').toLong()
        return if (negative) -value else value
    }

    /** 1234560000 → «123 456,00». Округление половины от нуля до [precision] знаков. */
    fun format(amount: Long, precision: Int = 2): String {
        require(precision in 0..DIGITS) { "precision вне 0..$DIGITS: $precision" }
        require(amount in -MAX_ABS..MAX_ABS) { "Сумма вне диапазона" }
        val step = pow10(DIGITS - precision)
        val unit = pow10(precision)
        val rounded = (Math.abs(amount) + step / 2) / step
        val whole = (rounded / unit).toString().reversed().chunked(3).joinToString(" ").reversed()
        val sign = if (amount < 0 && rounded != 0L) "-" else ""
        if (precision == 0) return sign + whole
        return "$sign$whole,${(rounded % unit).toString().padStart(precision, '0')}"
    }

    internal fun pow10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }
}
