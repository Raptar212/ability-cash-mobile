package app.cashbook.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate

/** Что показать после проверки или установки. */
sealed interface Outcome {
    /** Есть новая версия — предложить «Обновить / Позже». */
    data class Available(val release: Release, val current: String) : Outcome

    /** Нет разрешения «Установка неизвестных приложений» — открыть настройки или скачать браузером. */
    data class NeedPermission(val release: Release) : Outcome

    data class Message(val title: String, val text: String) : Outcome
}

/**
 * Проверка обновлений (как BDC-61/67): при открытии раз в день (настройка) и из меню. Автопроверка
 * с [silent] на Android 12+ по Wi-Fi и с разрешением на установку ничего не спрашивает: APK
 * скачивается, а ставится, когда пользователь уходит с главного экрана.
 */
object Updates {
    private const val CHECKED = "checkedOn"
    @Volatile private var running = false

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Автопроверка при открытии; выключена — только из меню. */
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    /** Автопроверка ставит новую версию без вопроса (где Android позволяет). */
    fun silent(context: Context) = prefs(context).getBoolean("silent", true)
    fun setSilent(context: Context, on: Boolean) = prefs(context).edit().putBoolean("silent", on).apply()

    fun forgetToday(context: Context) = prefs(context).edit().remove(CHECKED).apply()
    private fun markToday(context: Context) = prefs(context).edit().putString(CHECKED, LocalDate.now().toString()).apply()

    fun current(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    /** Отладочная сборка — отдельное приложение `.debug`, релизный APK её не обновит. */
    private fun isDebugBuild(context: Context) = context.packageName.endsWith(".debug")

    /**
     * Автоматическая ([manual] = false): не чаще раза в день (считается удачная), молча, если нового
     * нет. Ручная — всегда и говорит, что нашла. [onHome] — сейчас открыт главный экран.
     */
    suspend fun check(context: Context, manual: Boolean, onHome: () -> Boolean): Outcome? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if ((!manual && (running || !enabled(app) || prefs(app).getString(CHECKED, null) == LocalDate.now().toString()))) {
            return@withContext null
        }
        if (isDebugBuild(app)) {
            return@withContext if (manual) Outcome.Message("Обновления", "Отладочная сборка не обновляется.") else null
        }
        if (running) {
            return@withContext if (manual) Outcome.Message("Проверка уже идёт", "Подождите несколько секунд.") else null
        }
        running = true
        try {
            val current = current(app)
            val json = fetch(UpdateCheck.LATEST_URL, current) // null — релизов ещё нет
            val release = json?.let(UpdateCheck::parse)
            val newer = release != null && UpdateCheck.isNewer(release.version, current)
            if (!manual && newer && silent(app) && SelfUpdate.silentSupported && SelfUpdate.canInstall(app) &&
                SelfUpdate.unmetered(app) && !SelfUpdate.isStaged
            ) {
                SelfUpdate.stage(app, release!!)
                markToday(app)
                if (!SelfUpdate.onScreen) SelfUpdate.commitIfIdle(app, onHome()) // ушёл, пока качалось
                return@withContext null
            }
            // Пока ждали ответ, могли открыть форму: поверх неё не предлагаем и не считаем проверку.
            if (!manual && newer && !onHome()) return@withContext null
            markToday(app)
            val tagged = json?.let(UpdateCheck::tagVersion)
            when {
                newer -> Outcome.Available(release!!, current)
                !manual -> null
                tagged != null && UpdateCheck.isNewer(tagged, current) ->
                    Outcome.Message("Обновление недоступно", "У версии $tagged нет файла APK. Попробуйте позже.")
                else -> Outcome.Message("Обновлений нет", "Установлена последняя версия ($current).")
            }
        } catch (e: Exception) {
            // Автоматическая: нет сети — обычное дело, следующее открытие попробует снова.
            if (manual) Outcome.Message("Не удалось проверить обновления", reason(e)) else null
        } finally {
            running = false
        }
    }

    /**
     * «Обновить» в диалоге: скачать, сверить и поставить (12+ молча, старше — окном системы).
     * Пока качалось, могли открыть форму — тогда установка ждёт ухода с главного экрана ([onHome]),
     * иначе несохранённый ввод пропал бы. Без разрешения на установку — [Outcome.NeedPermission].
     */
    suspend fun install(context: Context, release: Release, onHome: () -> Boolean): Outcome? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!SelfUpdate.canInstall(app)) return@withContext Outcome.NeedPermission(release)
        try {
            if (!SelfUpdate.isStaged) SelfUpdate.stage(app, release)
            SelfUpdate.commitIfIdle(app, onHome())
            null
        } catch (e: Exception) {
            Outcome.Message("Обновление не загружено", reason(e))
        }
    }

    /** Тело ответа или null на 404 (у репозитория ещё нет релизов). */
    private fun fetch(url: String, version: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Cashbook/$version") // GitHub API требует User-Agent
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                404 -> null
                else -> throw HttpError(code)
            }
        } finally {
            conn.disconnect()
        }
    }

    private class HttpError(val code: Int) : IOException("HTTP $code")

    /** По-русски: сетевые ошибки приходят по-английски; свои сообщения (SHA-256) — уже по-русски. */
    private fun reason(e: Exception) = when (e) {
        is SocketTimeoutException -> "GitHub не ответил вовремя."
        is HttpError -> "GitHub ответил ${e.code}."
        is IOException -> e.message?.takeIf { m -> m.any { it in 'а'..'я' } } ?: "Нет связи с GitHub."
        else -> e.message ?: e.toString()
    }
}
