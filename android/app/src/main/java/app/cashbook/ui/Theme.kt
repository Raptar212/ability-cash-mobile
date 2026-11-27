package app.cashbook.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Фон и поверхности — нейтральные с бирюзовым оттенком (тональная палитра Material для бирюзы),
// иначе остаются сиреневые по умолчанию из Material 3.
private val Light = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    background = Color(0xFFF4FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF4FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    surfaceDim = Color(0xFFD5DBD9),
    surfaceBright = Color(0xFFF4FBF8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFF9FF2E6),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    inverseSurface = Color(0xFFDDE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    surfaceDim = Color(0xFF0E1513),
    surfaceBright = Color(0xFF343B39),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303634),
)

/** Типографика Material с моноширинными цифрами (`tnum`): суммы в столбик встают по разрядам. */
private val CashbookTypography = Typography().run {
    fun TextStyle.tnum() = copy(fontFeatureSettings = "tnum")
    Typography(
        displayLarge.tnum(), displayMedium.tnum(), displaySmall.tnum(),
        headlineLarge.tnum(), headlineMedium.tnum(), headlineSmall.tnum(),
        titleLarge.tnum(), titleMedium.tnum(), titleSmall.tnum(),
        bodyLarge.tnum(), bodyMedium.tnum(), bodySmall.tnum(),
        labelLarge.tnum(), labelMedium.tnum(), labelSmall.tnum(),
    )
}

/** Тема приложения (Настройки → Внешний вид); хранится в SharedPreferences `app`. Виджет — своя настройка. */
enum class AppTheme(val title: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная");

    @Composable
    fun isDark() = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)

        fun load(context: Context) = entries.find { it.name == prefs(context).getString("theme", null) } ?: SYSTEM

        fun save(context: Context, theme: AppTheme) = prefs(context).edit().putString("theme", theme.name).apply()
    }
}

/** Тема приложения: бирюзовый акцент, светлая или тёмная по [dark]. */
@Composable
fun CashbookTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = CashbookTypography, content = content)

/** Тёмная ли тема сейчас — по фону схемы, а не по системе: тема могла быть выбрана вручную. */
@Composable
private fun darkScheme() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Столбики доходов: на тёмном фоне индиго светлее (#3F51B5 там даёт контраст ~2,6:1, нужно 3:1). */
@Composable
fun chartIncomeColor() = if (darkScheme()) Color(0xFF7986CB) else IncomeColor

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
fun incomeColor() = if (darkScheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

// Собранные иконки: путь разбирается один раз на слаг (вызывается только из UI-потока).
private val iconCache = HashMap<String, ImageVector>()

/**
 * Иконка статьи по слагу ([CATEGORY_ICON_PATHS]): обводка белым, цвет задаёт `tint` у `Icon`.
 * null — иконки нет или слаг неизвестен (убрали из набора): на кружке остаются инициалы.
 */
fun categoryIcon(slug: String?): ImageVector? {
    val paths = CATEGORY_ICON_PATHS[slug ?: return null] ?: return null
    return iconCache.getOrPut(slug) {
        ImageVector.Builder(slug, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.White), strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter,
                )
            }
        }.build()
    }
}
