package app.cashbook.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF9FF2E6),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
)

/** Тема приложения: бирюзовый акцент, светлая или тёмная — как в системе. */
@Composable
fun CashbookTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)

/** Столбики доходов: на тёмном фоне индиго светлее (#3F51B5 там даёт контраст ~2,6:1, нужно 3:1). */
@Composable
fun chartIncomeColor() = if (isSystemInDarkTheme()) Color(0xFF7986CB) else IncomeColor

/** Цвета счетов в ленте: средние тона, читаются и на светлом, и на тёмном фоне. Порядок не менять — по нему авто-цвет. */
val ACCOUNT_COLORS = listOf(
    0xFF1E88E5, 0xFFE53935, 0xFF43A047, 0xFFE65100, 0xFFAB47BC, 0xFF00ACC1,
    0xFFEC407A, 0xFF8D6E63, 0xFF5C6BC0, 0xFF00897B, 0xFF78909C, 0xFF827717,
).map { it.toInt() }

/** Названия [ACCOUNT_COLORS] по порядку — для TalkBack в выборе цвета. */
val ACCOUNT_COLOR_NAMES = listOf(
    "Синий", "Красный", "Зелёный", "Оранжевый", "Фиолетовый", "Голубой",
    "Розовый", "Коричневый", "Индиго", "Бирюзовый", "Серо-голубой", "Оливковый",
)

/** ARGB цвета счёта: выбранный или из палитры по id — разные счета различаются и без настройки. */
fun accountArgb(id: Long, color: Int?): Int = color ?: ACCOUNT_COLORS[Math.floorMod(id, ACCOUNT_COLORS.size.toLong()).toInt()]

/** Цвет суммы дохода в ленте — зелёный, читаемый на светлом и тёмном фоне. */
@Composable
fun incomeColor() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
