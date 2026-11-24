package cz.kutil.dochazka.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Nejnovější vydání na GitHubu. build = číslo sestavení z názvu APK (DochazkaKutil-v9-20.apk → 20). */
data class ReleaseInfo(
    val build: Int,
    val version: String,
    val title: String,
    val apkName: String,
    val apkUrl: String,
    val sha256: String?,
    val size: Long,
)

/**
 * Aktualizace z veřejného repozitáře hincep-lab/dochazka-kutil (GitHub Releases). Kromě této kontroly
 * aplikace na internet nesahá a nic neodesílá – jen stáhne informace o vydání a případně APK.
 * Staženému souboru se ověří SHA-256 otisk, který GitHub k souboru zveřejňuje; instalaci pak
 * potvrzuje uživatel v systémovém instalátoru, který navíc ověří, že je APK podepsané stejným klíčem.
 */
object Updater {
    const val REPO = "hincep-lab/dochazka-kutil"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private val APK_RE = Regex("DochazkaKutil-v([0-9.]+)-(\\d+)\\.apk")

    fun installedBuild(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) {
        0
    }

    /** Rozbor odpovědi GitHub API; null = vydání bez APK. */
    fun parse(json: String): ReleaseInfo? {
        val root = JSONObject(json)
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val m = APK_RE.matchEntire(name) ?: continue
            val digest = a.optString("digest", "").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            return ReleaseInfo(
                build = m.groupValues[2].toInt(),
                version = m.groupValues[1],
                title = root.optString("name", name),
                apkName = name,
                apkUrl = a.getString("browser_download_url"),
                sha256 = digest,
                size = a.optLong("size", 0),
            )
        }
        return null
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "DochazkaKutil")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val c = open(API)
        try {
            if (c.responseCode != 200) throw IOException("GitHub odpověděl kódem ${c.responseCode}.")
            parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** Stáhne APK do mezipaměti a ověří otisk. progress: 0..1 (nebo -1, když velikost není známá). */
    suspend fun download(ctx: Context, rel: ReleaseInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, rel.apkName)
        val c = open(rel.apkUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != 200) throw IOException("Stažení selhalo (kód ${c.responseCode}).")
            val total = if (rel.size > 0) rel.size else c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        progress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (rel.sha256 != null && !hex.equals(rel.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("Stažený soubor nesouhlasí s otiskem na GitHubu – instalace zrušena.")
        }
        file
    }

    /** Smí aplikace otevřít systémový instalátor? Jinak je potřeba jednou povolit „Instalace neznámých aplikací“. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
