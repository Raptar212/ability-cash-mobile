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

/** Цвет суммы дохода в ленте — зелёный, читаемый на светлом и тёмном фоне. */
@Composable
fun incomeColor() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
