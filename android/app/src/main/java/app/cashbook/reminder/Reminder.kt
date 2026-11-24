package app.cashbook.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.cashbook.MainActivity
import app.cashbook.R
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Ежедневное напоминание «Запишите свои траты за сегодня». Вкл/время — SharedPreferences `app`.
 * Будильник один, на ближайший раз; сработал — ставится следующий ([ReminderReceiver]).
 */
object Reminder {
    private const val ACTION_FIRE = "app.cashbook.REMINDER"
    private const val CHANNEL = "reminder"
    private val DEFAULT_TIME: LocalTime = LocalTime.of(21, 0)

    private fun prefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("reminder", false)

    fun time(context: Context): LocalTime =
        prefs(context).getInt("reminderMinute", -1).takeIf { it in 0 until 24 * 60 }
            ?.let { LocalTime.of(it / 60, it % 60) } ?: DEFAULT_TIME

    fun save(context: Context, enabled: Boolean, time: LocalTime) {
        prefs(context).edit()
            .putBoolean("reminder", enabled)
            .putInt("reminderMinute", time.hour * 60 + time.minute)
            .apply()
        schedule(context)
    }

    /** Ближайший момент [time] строго после [now]: сегодня, если ещё не наступил, иначе завтра. */
    fun next(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Поставить будильник на ближайший раз или снять, если напоминание выключено. Повторный вызов безвреден. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val fire = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (!enabled(context)) return alarms.cancel(fire)
        val at = next(LocalDateTime.now(), time(context)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Точный: неточному Android даёт окно до часа. Право — USE_EXACT_ALARM (13+) / SCHEDULE_EXACT_ALARM (12),
        // оба без действий пользователя; отозвали вручную — неточный как запасной.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    /** Нет разрешения (Android 13+) — уведомление не показать. */
    fun permitted(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun isFire(intent: Intent) = intent.action == ACTION_FIRE

    fun show(context: Context) {
        if (!permitted(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Напоминание о тратах", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Cashbook")
            .setContentText("Запишите свои траты за сегодня")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, n)
    }
}

/** Будильник напоминания и события, после которых его надо поставить заново (загрузка, обновление, смена времени). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Reminder.isFire(intent) && Reminder.enabled(context)) Reminder.show(context)
        Reminder.schedule(context)
    }
}
