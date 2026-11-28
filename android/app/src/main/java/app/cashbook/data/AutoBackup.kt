package app.cashbook.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Автокопия базы в папку, выбранную владельцем (SAF): `cashbook-auto-YYYY-MM-DD.db`, не чаще раза
 * в сутки, хранятся [KEEP] последних. Настройки — SharedPreferences, не база: схема Room не растёт.
 */
object AutoBackup {
    const val PREFIX = "cashbook-auto-"
    const val KEEP = 7
    const val EVERY_MS = 24 * 3600 * 1000L

    private const val FOLDER = "folder"
    private const val LAST = "last"
    private const val ERROR = "error"

    private fun prefs(context: Context) = context.getSharedPreferences("autobackup", Context.MODE_PRIVATE)

    /** Имя уникально до секунды: существующие файлы никогда не перезаписываются. */
    fun fileName(at: LocalDateTime) = PREFIX + at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".db"

    // Старые копии прошлых версий без времени (`…-2026-11-28.db`) тоже свои.
    private val OWN = Regex("""^cashbook-auto-\d{4}-\d{2}-\d{2}(-\d{6})?\.db$""")

    /**
     * Какие из [names] лишние: свои файлы за вычетом [keep] последних. Порядок — по имени без `.db`:
     * иначе `…-28.db` ('.' после '-') встал бы новее `…-28-090000.db`. Чужие и похожие имена не
     * считаются и не удаляются; [exclude] — только что записанный файл — не удаляется никогда.
     */
    fun staleNames(names: Collection<String>, keep: Int = KEEP, exclude: String? = null): List<String> =
        names.filter { OWN.matches(it) }.sortedByDescending { it.removeSuffix(".db") }.drop(keep).filter { it != exclude }

    /** Пора ли копировать: прошло не меньше суток с [last] (мс; 0 — копий не было; часы ушли назад — пора). */
    fun due(last: Long, now: Long) = last <= 0 || now < last || now - last >= EVERY_MS

    /** Подпись пункта «Автокопия»; ошибка важнее даты — её надо заметить. */
    fun statusText(enabled: Boolean, last: Long, error: String?, zone: ZoneId = ZoneId.systemDefault()): String = when {
        !enabled -> "Выключена"
        error != null -> "Ошибка: $error"
        last > 0 -> "Последняя: " + Instant.ofEpochMilli(last).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        else -> "Включена, копии ещё не было"
    }

    fun folder(context: Context): Uri? = prefs(context).getString(FOLDER, null)?.let(Uri::parse)

    fun status(context: Context): String {
        val p = prefs(context)
        return statusText(folder(context) != null, p.getLong(LAST, 0), p.getString(ERROR, null))
    }

    /** Включить (или сменить папку); разрешение на [uri] уже взято вызывающим. */
    fun enable(context: Context, uri: Uri) {
        folder(context)?.takeIf { it != uri }?.let { release(context, it) }
        prefs(context).edit().clear().putString(FOLDER, uri.toString()).apply()
    }

    fun disable(context: Context) {
        folder(context)?.let { release(context, it) }
        prefs(context).edit().clear().apply()
    }

    private fun release(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Копия в фоне при уходе из приложения, если пора; вне главного потока и вне Activity. */
    fun runIfDue(context: Context) {
        val app = context.applicationContext
        if (folder(app) == null) return
        Thread { run(app, force = false) }.start()
    }

    /**
     * Сделать копию ([force] — без проверки срока). Возвращает ошибку для показа или null; результат
     * запоминается. Идёт под замком [Backup]: не пересекается с восстановлением базы.
     */
    fun run(context: Context, force: Boolean, now: Long = System.currentTimeMillis()): String? = synchronized(Backup) {
        val tree = folder(context) ?: return null
        val p = prefs(context)
        if (!force && !due(p.getLong(LAST, 0), now)) return null
        val error = try {
            write(context, tree, LocalDateTime.now())
            null
        } catch (e: SecurityException) {
            "нет доступа к папке, выберите её заново"
        } catch (e: Exception) {
            e.message ?: "не удалось записать"
        }
        p.edit().apply { if (error == null) putLong(LAST, now).remove(ERROR) else putString(ERROR, error) }.apply()
        error
    }

    private class Entry(val id: String, val dir: Boolean)

    /** Имя → документ для содержимого папки [tree]; строки без имени или id пропускаются. */
    private fun children(context: Context, tree: Uri): Map<String, Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = mutableMapOf<String, Entry>()
        (context.contentResolver.query(uri, cols, null, null, null) ?: error("Папка недоступна")).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out[name] = Entry(id, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    /**
     * Ничего не перезаписываем: копия пишется в новый документ `<имя>.db.tmp` (имя с секундами,
     * уникально) и переименовывается в `<имя>.db`. Сбой на любом шаге (в том числе провайдер без
     * `renameDocument`) удаляет только этот `.tmp`; прежние копии не тронуты. Только после успешного
     * rename — уборка чужих остатков `cashbook-auto-*.db.tmp` / `.bak` (не папок) и ротация; их сбой
     * копию не портит.
     */
    private fun write(context: Context, tree: Uri, at: LocalDateTime) {
        val resolver = context.contentResolver
        fun doc(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val final = fileName(at)
        var tmp: Uri? = null
        try {
            Backup.export(context, AppDatabase.get(context)) {
                val uri = DocumentsContract.createDocument(
                    resolver, doc(DocumentsContract.getTreeDocumentId(tree)), "application/octet-stream", "$final.tmp",
                ) ?: error("Не создать файл в папке")
                tmp = uri
                resolver.openOutputStream(uri, "wt") ?: error("Не открыть файл для записи")
            }
            val written = tmp ?: error("Копия не записана")
            DocumentsContract.renameDocument(resolver, written, final) ?: error("Папка не умеет переименовывать файлы")
            tmp = null
        } catch (e: Exception) {
            tmp?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw e
        }
        runCatching {
            val now = children(context, tree)
            for ((n, e) in now) {
                if (!e.dir && n.startsWith(PREFIX) && (n.endsWith(".db.tmp") || n.endsWith(".bak"))) {
                    runCatching { DocumentsContract.deleteDocument(resolver, doc(e.id)) }
                }
            }
            for (n in staleNames(now.keys, exclude = final)) {
                val e = now.getValue(n)
                if (!e.dir) runCatching { DocumentsContract.deleteDocument(resolver, doc(e.id)) }
            }
        }
    }
}
