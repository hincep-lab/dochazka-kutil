package cz.kutil.dochazka.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.kutil.dochazka.core.PRAGUE
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Automatická záloha jednou denně v nastavený čas (výchozí 18:00) do složky, kterou uživatel jednou vybere (Storage Access Framework).
 * Když se od poslední zálohy nic nezměnilo, nový soubor nevzniká.
 * Soubory se jmenují Dochazka_auto_RRRR-MM-DD.json; ponechá se posledních [KEEP], starší automatické
 * zálohy (jen s tímto názvem) se smažou. Ručně uložené zálohy ani jiné soubory ve složce se nemění.
 */
object AutoBackup {
    const val PREFIX = "Dochazka_auto_"
    const val KEEP = 14

    fun folder(ctx: Context): Uri? = AppPrefs.autoBackupFolder(ctx)?.let { Uri.parse(it) }

    fun setFolder(ctx: Context, tree: Uri?) {
        val old = folder(ctx)
        if (old != null && old != tree) {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        if (tree != null) {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        AppPrefs.setAutoBackupFolder(ctx, tree?.toString())
    }

    /** Čitelný název složky pro zobrazení. */
    fun folderName(ctx: Context): String? {
        val tree = folder(ctx) ?: return null
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':')
    }

    /**
     * Vytvoří zálohu teď. Vrací název souboru, nebo null, když se od poslední automatické zálohy
     * nic nezměnilo (jen při [force] = false).
     */
    suspend fun runNow(ctx: Context, force: Boolean = true): String? {
        val json = Backup.export(Graph.realDb(ctx), AppPrefs.toJson(ctx))
        val digest = org.json.JSONObject(json).optString("sha256")
        if (!force && digest.isNotEmpty() && digest == AppPrefs.autoBackupDigest(ctx)) {
            // Poslední záloha je stále aktuální – počítá se jako zálohováno (bez zbytečné připomínky).
            AppPrefs.setLastBackupAt(ctx, System.currentTimeMillis())
            AppPrefs.setAutoBackupResult(ctx, System.currentTimeMillis(), "")
            return null
        }
        val tree = folder(ctx) ?: throw IOException("Složka pro automatickou zálohu není vybraná.")
        val resolver = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val existing = ArrayList<Pair<String, String>>() // název, id
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) existing.add(c.getString(0) to c.getString(1)) }
            ?: throw IOException("Do vybrané složky nejde zapisovat – vyber ji prosím znovu.")

        val name = "$PREFIX${LocalDate.now(PRAGUE)}.json"
        // Dnešní automatická záloha se přepíše novou.
        existing.filter { it.first == name }.forEach {
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.second))
        }
        val doc = DocumentsContract.createDocument(resolver, parent, "application/json", name)
            ?: throw IOException("Soubor zálohy se nepodařilo vytvořit.")
        resolver.openOutputStream(doc, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Do souboru zálohy nejde zapisovat.")
        // Ověření po zápisu – otisk musí souhlasit.
        val check = resolver.openInputStream(doc)?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: throw IOException("Zálohu se nepodařilo po uložení ověřit.")
        Backup.inspect(check)

        // Ponechat posledních KEEP automatických záloh (jiné soubory se nemažou).
        val autos = existing.filter { it.first.startsWith(PREFIX) && it.first.endsWith(".json") && it.first != name }
            .sortedByDescending { it.first }
        autos.drop(KEEP - 1).forEach {
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.second)) }
        }
        val now = System.currentTimeMillis()
        AppPrefs.setLastBackupAt(ctx, now)
        AppPrefs.setAutoBackupResult(ctx, now, "")
        AppPrefs.setAutoBackupDigest(ctx, digest)
        return name
    }

    /** Jak dlouho zbývá do nejbližšího nastaveného času zálohy (dnes, nebo zítra). */
    fun delayToNext(ctx: Context, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now(PRAGUE)): Long {
        val m = AppPrefs.autoBackupMinute(ctx)
        var next = now.toLocalDate().atTime(m / 60, m % 60).atZone(PRAGUE)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        return java.time.Duration.between(now, next).toMillis()
    }

    /**
     * Záloha každý den v nastavený čas (výchozí 18:00). Plánuje se vždy jen ta příští;
     * po proběhnutí si worker naplánuje další. [replace] = po změně času v Nastavení.
     */
    fun ensureScheduled(ctx: Context, replace: Boolean = false, fromWorker: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork("auto_backup") // dřívější plán „jednou za 24 h kdykoli“
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(delayToNext(ctx), TimeUnit.MILLISECONDS)
            .build()
        val policy = when {
            fromWorker -> ExistingWorkPolicy.APPEND_OR_REPLACE
            replace -> ExistingWorkPolicy.REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        wm.enqueueUniqueWork("auto_backup_daily", policy, request)
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        // další záloha zítra ve stejný čas (naplánuje se hned, ať se plán nikdy nepřetrhne)
        AutoBackup.ensureScheduled(app, fromWorker = true)
        if (AutoBackup.folder(app) == null) return Result.success()
        return try {
            // nový soubor = záloha provedena (když se nic nezměnilo, nic se nehlásí)
            if (AutoBackup.runNow(app, force = false) != null) {
                cz.kutil.dochazka.track.Notifier.showBackupDone(app, System.currentTimeMillis(), AutoBackup.folderName(app))
            }
            Result.success()
        } catch (e: Exception) {
            val msg = e.message ?: "Záloha se nepodařila."
            AppPrefs.setAutoBackupResult(app, System.currentTimeMillis(), msg)
            cz.kutil.dochazka.track.Notifier.showBackupFailed(app, msg)
            Result.success()
        }
    }
}
