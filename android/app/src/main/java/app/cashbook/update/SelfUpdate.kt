package app.cashbook.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Установка обновления системным установщиком (PackageInstaller), как в BirthDay Checker (BDC-67).
 * Android 12+ при разрешении «Установка неизвестных приложений» обновляет приложение без вопросов
 * (UPDATE_PACKAGES_WITHOUT_USER_ACTION); Android 8–11 или отказ тихого пути — системное окно «Обновить?».
 */
object SelfUpdate {
    /** Id сессии со скачанным APK; забирается атомарно — два commit одной сессии не случатся. */
    private val staged = AtomicReference<Int?>(null)

    /** Окно установщика «Обновить?», ждёт, пока приложение на экране (из фона Android его не покажет). */
    @Volatile var confirm: Intent? = null

    /** Приложение на экране (между onResume и onPause). */
    @Volatile var onScreen = false

    /** Открыт свой выбор файла: Android считает это уходом из приложения, ставить нельзя. */
    @Volatile var ownActivity = false

    /** Без вопросов Android ставит обновление с 12-й версии. */
    val silentSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** APK в сессии ждёт [commit]. */
    val isStaged get() = staged.get() != null

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Wi-Fi или другая безлимитная сеть: по мобильной — только по «Обновить». */
    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    /** Переключатель «Установка неизвестных приложений» этого приложения. */
    fun askPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Скачать APK, сверить SHA-256 с релизом и записать в новую сессию установки. Ничего не ставится
     * до [commit]. Блокирующий вызов — не из главного потока.
     */
    @Synchronized
    fun stage(context: Context, release: Release) {
        val installer = context.packageManager.packageInstaller
        for (old in installer.mySessions) abandon(installer, old.sessionId) // от процесса, убитого до commit
        staged.set(null)
        val file = File(context.cacheDir, "update.apk")
        var id: Int? = null
        try {
            download(release.url, file)
            val hash = file.inputStream().use(UpdateCheck::sha256)
            if (hash != release.sha256) throw IOException("Скачанный файл не совпал с релизом (SHA-256). Попробуйте позже.")
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("app.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            staged.set(id)
        } catch (e: Exception) {
            id?.let { abandon(installer, it) }
            throw e
        } finally {
            file.delete()
        }
    }

    private fun download(url: String, to: File) {
        // GitHub отдаёт файл редиректом на свой CDN (https → https) — HttpURLConnection идёт за ним сам.
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IOException("GitHub ответил ${conn.responseCode}.")
            conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun abandon(installer: PackageInstaller, id: Int) {
        try {
            installer.abandonSession(id)
        } catch (e: Exception) {
            // уже установлена или исчезла — отменять нечего
        }
    }

    /** Тихая установка, когда пользователь ушёл с главного экрана, а не в свой выбор файла. */
    fun commitIfIdle(context: Context, onHome: Boolean) {
        if (onHome && !ownActivity) commit(context)
    }

    /** Отдать скачанный APK системе: на 12+ ставит молча и закрывает приложение, иначе спрашивает. */
    fun commit(context: Context) {
        val id = staged.getAndSet(null) ?: return
        val installer = context.packageManager.packageInstaller
        try {
            // Mutable: система пишет в него результат. Явный получатель — Android 14 не примет mutable неявный.
            val flags = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) or
                PendingIntent.FLAG_UPDATE_CURRENT
            val status = PendingIntent.getBroadcast(context, 0, Intent(context, InstallStatusReceiver::class.java), flags)
            installer.openSession(id).use { it.commit(status.intentSender) }
        } catch (e: Exception) {
            // Вызывается и из жизненного цикла Activity — исключение уронило бы приложение.
            abandon(installer, id)
            InstallStatusReceiver.failed(context, e.message, retry = true)
        }
    }
}

/** Ответ установщика: спросить пользователя (сейчас или когда вернётся) или сообщить об ошибке. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") // типизированная перегрузка — только с Android 13
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                SelfUpdate.confirm = confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (SelfUpdate.onScreen) showConfirm(context)
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // «Отмена» — спросим завтра
            // Подпись, конфликт, битый APK — не пройдут и завтра: не качать заново при каждом открытии.
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INVALID ->
                failed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), retry = false)
            else -> failed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), retry = true)
        }
    }

    companion object {
        fun showConfirm(context: Context) {
            val intent = SelfUpdate.confirm ?: return
            SelfUpdate.confirm = null
            context.startActivity(intent)
        }

        /** Сообщить; [retry] — следующее открытие проверит снова, а не завтра. Toast — с главного потока. */
        fun failed(context: Context, reason: String?, retry: Boolean) {
            if (retry) Updates.forgetToday(context)
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, "Обновление не установлено: ${reason ?: "ошибка установщика"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
