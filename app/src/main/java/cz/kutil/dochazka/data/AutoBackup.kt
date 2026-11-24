package cz.kutil.dochazka.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.kutil.dochazka.core.PRAGUE
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Automatická záloha jednou týdně do složky, kterou uživatel jednou vybere (Storage Access Framework).
 * Soubory se jmenují Dochazka_auto_RRRR-MM-DD.json; ponechá se posledních [KEEP], starší automatické
 * zálohy (jen s tímto názvem) se smažou. Ručně uložené zálohy ani jiné soubory ve složce se nemění.
 */
object AutoBackup {
    const val PREFIX = "Dochazka_auto_"
    const val KEEP = 6

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

    /** Vytvoří zálohu teď. Vrací název souboru. */
    suspend fun runNow(ctx: Context): String {
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
        val json = Backup.export(Graph.realDb(ctx), AppPrefs.toJson(ctx))
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
        return name
    }

    fun ensureScheduled(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("auto_backup", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (AutoBackup.folder(app) == null) return Result.success()
        return try {
            AutoBackup.runNow(app)
            Result.success()
        } catch (e: Exception) {
            AppPrefs.setAutoBackupResult(app, System.currentTimeMillis(), e.message ?: "Záloha se nepodařila.")
            Result.success()
        }
    }
}
