package app.cashbook.update

import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/** Релиз на GitHub: версия «1.2.3», ссылка на APK и его SHA-256 (hex, строчные). */
data class Release(val version: String, val url: String, val sha256: String)

/**
 * Разбор последнего релиза GitHub (`releases/latest` — без черновиков и pre-release). Сети здесь
 * нет: скачивает [Updates], здесь решается, годится ли релиз.
 */
object UpdateCheck {
    private const val REPO = "Raptar212/ability-cash-mobile"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** Скачивается только файл из релизов этого репозитория. */
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    fun apkName(version: String) = "Cashbook-$version.apk"

    /**
     * Релиз из JSON или null, если он не годится: тег не `v1.2.3`, нет `Cashbook-1.2.3.apk`, файл
     * не из этого репозитория или без SHA-256.
     */
    fun parse(json: String): Release? {
        val (version, root) = read(json) ?: return null
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != apkName(version)) continue
            val url = asset.optString("browser_download_url")
            val digest = asset.optString("digest")
            val hex = digest.removePrefix("sha256:")
            if (!url.startsWith(DOWNLOAD_PREFIX) || !digest.startsWith("sha256:") || !hex.matches(HEX64)) return null
            return Release(version, url, hex.lowercase())
        }
        return null
    }

    /** Версия по тегу, даже если APK у релиза негодный: ручная проверка скажет «без файла», а не «последняя». */
    fun tagVersion(json: String): String? = read(json)?.first

    private fun read(json: String): Pair<String, JSONObject>? {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        }
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name")
        val version = tag.removePrefix("v").takeIf { tag.startsWith("v") && parts(it) != null } ?: return null
        return version to root
    }

    /** Сравнение major.minor.patch; суффикс установленной версии («1.0.0-debug») не учитывается. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest) ?: return false
        val b = parts(current.substringBefore('-')) ?: return true
        return compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] }) > 0
    }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** «1.2.3» → [1, 2, 3]; ровно три числа, иначе null. */
    private fun parts(v: String): List<Int>? =
        v.split('.').takeIf { p -> p.size == 3 && p.all { it.isNotEmpty() && it.length <= 9 && it.all { c -> c in '0'..'9' } } }
            ?.map { it.toInt() }

    private val HEX64 = Regex("[0-9a-fA-F]{64}")
}
