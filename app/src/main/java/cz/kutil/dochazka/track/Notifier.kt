package cz.kutil.dochazka.track

import cz.kutil.dochazka.data.AppPrefs

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.kutil.dochazka.R
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.ui.MainActivity
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Potvrzovací oznámení „Začal jsi pracovat?“ / „Ukončil jsi práci?“ s akcemi ANO / NE. */
object Notifier {
    const val CHANNEL = "attendance_prompts"
    const val CHANNEL_MONTHLY = "monthly_report"
    const val EXTRA_MONTH = "open_month"
    private const val MONTHLY_ID = 500
    const val EXTRA_PENDING_ID = "pending_id"
    const val EXTRA_YES = "yes"
    const val EXTRA_OPEN_TAB = "open_tab"
    const val CHANNEL_DONE = "answer_done"
    /** Oranžová firemní barva (ikona a tlačítka oznámení). */
    private const val ACCENT = 0xFFF59E1B.toInt()
    const val CHANNEL_STATUS = "work_status"
    const val CHANNEL_BACKUP = "backups"
    const val BACKUP_DONE_ID = 507
    const val BACKUP_FAIL_ID = 508
    private const val STATUS_ID = 505

    /**
     * Trvalé oznámení po celou dobu směny: „V práci od 7:00“ s běžícími hodinami a tlačítkem
     * pro ukončení přímo v oznámení. Zmizí, jakmile se směna ukončí.
     */
    fun showWorking(ctx: Context, shiftId: Long, rawStartMs: Long, reportStartMs: Long, returnAtMs: Long?) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, STATUS_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun endAt(code: Int, at: Long?) = PendingIntent.getBroadcast(
            ctx, STATUS_ID * 10 + code,
            Intent(ctx, ShiftActionReceiver::class.java).setAction(ACTION_END_AT)
                .putExtra(EXTRA_SHIFT_ID, shiftId).putExtra(EXTRA_END_AT, at ?: -1L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Bez běžících stopek (Samsung je ukazuje vedle času a matou) – odpracovaný čas se obnovuje každou půlhodinu.
        val sofar = ((System.currentTimeMillis() - reportStartMs).coerceAtLeast(0) / 60_000L)
        val text = "Zatím ${sofar / 60}:%02d h · zaznamenáno v ${time(rawStartMs)}".format(sofar % 60) +
            (returnAtMs?.let { " · návrat domů v ${time(it)}" } ?: "")
        val builder = NotificationCompat.Builder(ctx, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("V práci od ${time(reportStartMs)}")
            .setColor(0xFF3AA34C.toInt())
            .setColorized(false)
            .setContentText(text)
            .setShowWhen(false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        if (returnAtMs != null) builder.addAction(quickAction("Konec v ${time(returnAtMs)}", endAt(1, returnAtMs)))
        builder.addAction(quickAction("Ukončit práci teď", endAt(2, null)))
        notifySafe(ctx, STATUS_ID, builder)
    }

    /** Je k dispozici novější verze aplikace. Klepnutí otevře Nastavení → Aktualizace. */
    fun showUpdate(ctx: Context, build: Int): Boolean {
        if (!canNotify(ctx)) return false
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 506,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "settings")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_MONTHLY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Nová verze Docházky")
            .setContentText("Je k dispozici sestavení $build. Klepni a v Nastavení dej Stáhnout a nainstalovat.")
            .setAutoCancel(true)
            .setContentIntent(open)
        val fresh = !isActive(ctx, 506)
        return notifySafe(ctx, 506, builder).also { if (it && fresh) NotifyVoice.say(ctx, NotifyVoice.Line.UPDATE) }
    }

    fun cancelWorking(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(STATUS_ID)
    private const val DONE_ID = 504
    const val ACTION_END_AT = "cz.kutil.dochazka.END_AT"
    const val EXTRA_END_AT = "end_at"

    /**
     * Akce, která se vyřídí přímo v oznámení (i na zamčené obrazovce a na hodinkách):
     * systém ví, že neotevírá žádnou obrazovku, takže aplikace se nespustí do popředí.
     */
    private fun quickAction(label: String, pi: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, label, pi).setShowsUserInterface(false).build()

    /** Krátké potvrzení, co se po klepnutí v oznámení zapsalo. Samo zmizí po pár vteřinách, bez zvuku. */
    fun showDone(ctx: Context, title: String, text: String) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, DONE_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(open)
        if (notifySafe(ctx, DONE_ID, builder)) when (title) {
            "Začátek práce zapsán" -> NotifyVoice.say(ctx, NotifyVoice.Line.STARTED, 150)
            "Konec práce zapsán" -> NotifyVoice.say(ctx, NotifyVoice.Line.ENDED, 150)
        }
    }

    /** Je oznámení s tímto id už zobrazené? (pak se při obnovení znovu nemluví) */
    private fun isActive(ctx: Context, id: Int): Boolean = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).activeNotifications.any { it.id == id }
    }.getOrDefault(false)

    private val TIME = DateTimeFormatter.ofPattern("H:mm")
    private val DAY = DateTimeFormatter.ofPattern("d. M.")

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "Potvrzení docházky", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Dotazy na začátek a konec práce při odchodu z domova a návratu."
        channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(channel)
        val monthly = NotificationChannel(CHANNEL_MONTHLY, "Měsíční uzávěrka", NotificationManager.IMPORTANCE_DEFAULT)
        monthly.description = "Připomenutí prvního dne v měsíci: zkontrolovat a odeslat docházku za minulý měsíc."
        nm.createNotificationChannel(monthly)
        val done = NotificationChannel(CHANNEL_DONE, "Potvrzení zápisu", NotificationManager.IMPORTANCE_LOW)
        done.description = "Krátká potvrzení po odpovědi ANO / NE přímo v oznámení."
        nm.createNotificationChannel(done)
        val status = NotificationChannel(CHANNEL_STATUS, "Stav práce", NotificationManager.IMPORTANCE_LOW)
        status.description = "Trvalé oznámení „V práci od …“, dokud běží směna."
        status.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(status)
        val backups = NotificationChannel(CHANNEL_BACKUP, "Zálohy", NotificationManager.IMPORTANCE_LOW)
        backups.description = "Potvrzení „Záloha provedena“ po každé uložené záloze."
        nm.createNotificationChannel(backups)
    }

    /** „Záloha provedena“ – potvrzení po uložené záloze (tiché oznámení, Kutil to řekne přes den). */
    fun showBackupDone(ctx: Context, atMs: Long, where: String?): Boolean {
        if (!AppPrefs.backupNotices(ctx) || !canNotify(ctx)) return false
        ensureChannel(ctx)
        NotificationManagerCompat.from(ctx).cancel(BACKUP_FAIL_ID)
        NotificationManagerCompat.from(ctx).cancel(BACKUP_ID)
        val open = PendingIntent.getActivity(
            ctx, BACKUP_DONE_ID,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "settings")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Uloženo ${day(atMs)} v ${time(atMs)}" + (where?.let { " do složky $it" } ?: "") + ". Data jsou v bezpečí."
        val builder = NotificationCompat.Builder(ctx, CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Záloha provedena ✓")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFF22C493.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(open)
        return notifySafe(ctx, BACKUP_DONE_ID, builder).also { if (it) NotifyVoice.say(ctx, NotifyVoice.Line.BACKUP_OK, 200) }
    }

    /** Záloha selhala – ozve se hned, ať se to dá opravit (např. znovu vybrat složku). */
    fun showBackupFailed(ctx: Context, reason: String): Boolean {
        if (!AppPrefs.backupNotices(ctx) || !canNotify(ctx)) return false
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, BACKUP_FAIL_ID,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "settings")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "$reason Klepni a v Nastavení zálohu zopakuj nebo vyber složku znovu."
        val builder = NotificationCompat.Builder(ctx, CHANNEL_MONTHLY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Záloha se nepovedla")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFFFF7A45.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
        val fresh = !isActive(ctx, BACKUP_FAIL_ID)
        return notifySafe(ctx, BACKUP_FAIL_ID, builder).also { if (it && fresh) NotifyVoice.say(ctx, NotifyVoice.Line.BACKUP_FAIL) }
    }

    /** Připomenutí měsíční uzávěrky. Vrací false, pokud oznámení nejde zobrazit. */
    fun showMonthly(ctx: Context, month: java.time.YearMonth, totalMinutes: Long, openShifts: Int): Boolean {
        if (!canNotify(ctx)) return false
        ensureChannel(ctx)
        val name = cz.kutil.dochazka.core.DocxExporter.MONTHS[month.monthValue - 1]
        val open = PendingIntent.getActivity(
            ctx, MONTHLY_ID,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "month").putExtra(EXTRA_MONTH, month.toString())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Odpracováno ${cz.kutil.dochazka.core.WorkCalc.formatHm(totalMinutes)} h." +
            (if (openShifts > 0) " Pozor, je tam neuzavřená směna." else "") +
            " Klepni, projdi dny, doplň co chybí a odešli e-mailem."
        val builder = NotificationCompat.Builder(ctx, CHANNEL_MONTHLY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Docházka za $name ${month.year} – zkontrolovat a odeslat")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
        return try {
            val fresh = !isActive(ctx, MONTHLY_ID)
            NotificationManagerCompat.from(ctx).notify(MONTHLY_ID, builder.build())
            if (fresh) NotifyVoice.say(ctx, NotifyVoice.Line.MONTHLY)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun notificationId(pendingId: Long): Int = 1000 + (pendingId % 1_000_000L).toInt()

    private fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(PRAGUE).format(TIME)
    private fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(PRAGUE).format(DAY)

    fun title(p: PendingEventEntity): String = when {
        p.type == "END" -> "Ukončil jsi práci?"
        p.pairedEndAt != null -> "Byl jsi v práci?"
        else -> "Začal jsi pracovat?"
    }

    /** Den jen tehdy, když to není dnešek – oznámení má být krátké. */
    private fun dayPrefix(ms: Long): String {
        val d = java.time.Instant.ofEpochMilli(ms).atZone(PRAGUE).toLocalDate()
        return if (d == java.time.LocalDate.now(PRAGUE)) "" else "${day(ms)} "
    }

    private fun rounded(ms: Long): String =
        time(cz.kutil.dochazka.core.Rounding.roundToHalfHour(java.time.Instant.ofEpochMilli(ms)).toEpochMilli())

    fun detail(p: PendingEventEntity): String = when {
        p.type == "END" -> "Návrat ${dayPrefix(p.effectiveAt)}v ${time(p.effectiveAt)} · do výkazu ${rounded(p.effectiveAt)}"
        p.pairedEndAt != null -> "Mimo domov ${dayPrefix(p.effectiveAt)}${time(p.effectiveAt)}–${time(p.pairedEndAt)} · zapsat jako práci?"
        else -> "Odchod ${dayPrefix(p.effectiveAt)}v ${time(p.effectiveAt)} · do výkazu ${rounded(p.effectiveAt)}"
    }

    fun show(ctx: Context, p: PendingEventEntity) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, notificationId(p.id),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "pending")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title(p))
            .setContentText(detail(p))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail(p)))
            .setColor(ACCENT)
            .setWhen(p.effectiveAt)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(open)
        for (yes in listOf(true, false)) {
            val intent = Intent(ctx, AnswerReceiver::class.java)
                .setAction("cz.kutil.dochazka.ANSWER")
                .putExtra(EXTRA_PENDING_ID, p.id)
                .putExtra(EXTRA_YES, yes)
            val pi = PendingIntent.getBroadcast(
                ctx, notificationId(p.id) * 2 + (if (yes) 1 else 0), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(quickAction(if (yes) "ANO" else "NE", pi))
        }
        try {
            val fresh = !isActive(ctx, notificationId(p.id))
            NotificationManagerCompat.from(ctx).notify(notificationId(p.id), builder.build())
            if (fresh) NotifyVoice.say(ctx, when {
                p.type == "END" -> NotifyVoice.Line.END
                p.pairedEndAt != null -> NotifyVoice.Line.TRIP
                else -> NotifyVoice.Line.START
            })
        } catch (e: SecurityException) {
            // Oprávnění bylo mezitím odebráno – návrh zůstává viditelný v aplikaci.
        }
    }

    // --- zapomenuté ukončení práce ----------------------------------------------------------

    const val LONG_SHIFT_ID = 501
    const val BACKUP_ID = 502
    const val TEST_ID = 503
    const val TEST_PENDING_ID = -2L
    const val ACTION_STILL_WORKING = "cz.kutil.dochazka.STILL_WORKING"
    const val EXTRA_SHIFT_ID = "shift_id"

    /**
     * Směna trvá déle, než je nastavený limit. Nic se neukončuje samo – uživatel buď potvrdí,
     * že stále pracuje, nebo otevře aplikaci a vybere čas ukončení (nabídne se zaznamenaný návrat).
     */
    fun showLongShift(ctx: Context, shiftId: Long, startMs: Long, hours: Long, returnAtMs: Long?): Boolean {
        if (!canNotify(ctx)) return false
        ensureChannel(ctx)
        // Ukončení přímo z oznámení: časem zaznamenaného návratu, nebo teď (čas „teď“ se vezme až při klepnutí).
        fun endAt(code: Int, at: Long?) = PendingIntent.getBroadcast(
            ctx, LONG_SHIFT_ID * 10 + code,
            Intent(ctx, ShiftActionReceiver::class.java).setAction(ACTION_END_AT)
                .putExtra(EXTRA_SHIFT_ID, shiftId).putExtra(EXTRA_END_AT, at ?: -1L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getActivity(
            ctx, LONG_SHIFT_ID,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "endshift")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val still = PendingIntent.getBroadcast(
            ctx, LONG_SHIFT_ID,
            Intent(ctx, ShiftActionReceiver::class.java).setAction(ACTION_STILL_WORKING).putExtra(EXTRA_SHIFT_ID, shiftId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Směna běží už $hours h (od ${day(startMs)} ${time(startMs)})." +
            (returnAtMs?.let { " Návrat domů byl zaznamenán v ${time(it)}." } ?: "") + " Zapomněl jsi ji ukončit?"
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Pracuješ ještě?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(end)
        if (returnAtMs != null) builder.addAction(quickAction("Konec v ${time(returnAtMs)}", endAt(1, returnAtMs)))
        builder.addAction(quickAction("Konec teď", endAt(2, null)))
        builder.addAction(quickAction("Stále pracuji", still))
        val fresh = !isActive(ctx, LONG_SHIFT_ID)
        return notifySafe(ctx, LONG_SHIFT_ID, builder).also { if (it && fresh) NotifyVoice.say(ctx, NotifyVoice.Line.LONG_SHIFT) }
    }

    fun cancelLongShift(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(LONG_SHIFT_ID)

    fun showBackupReminder(ctx: Context, lastBackupMs: Long): Boolean {
        if (!canNotify(ctx)) return false
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, BACKUP_ID,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "settings")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val days = if (lastBackupMs > 0) ((System.currentTimeMillis() - lastBackupMs) / 86_400_000L) else -1
        val folderSet = cz.kutil.dochazka.data.AutoBackup.folder(ctx) != null
        val text = (if (days >= 0) "Poslední záloha ${day(lastBackupMs)} (před $days ${when (days) { 1L -> "dnem"; else -> "dny" }})" else "Docházka ještě nebyla zálohována") +
            (if (folderSet) ". Automatická záloha se neprovádí – klepni a spusť ji ručně." else ". Klepni a vyber složku pro automatickou zálohu, pak se bude zálohovat každý den sama.")
        val builder = NotificationCompat.Builder(ctx, CHANNEL_MONTHLY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Hlídám zálohy: zálohuj docházku")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
        val fresh = !isActive(ctx, BACKUP_ID)
        return notifySafe(ctx, BACKUP_ID, builder).also { if (it && fresh) NotifyVoice.say(ctx, NotifyVoice.Line.BACKUP) }
    }

    /** Zkušební dotaz – ověří oznámení (i na hodinkách), do docházky nic nezapisuje. */
    fun showTest(ctx: Context): Boolean {
        if (!canNotify(ctx)) return false
        ensureChannel(ctx)
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Zkušební dotaz")
            .setContentText("Takhle bude vypadat dotaz na začátek práce. Tlačítka nic nezapíšou.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        for (yes in listOf(true, false)) {
            val intent = Intent(ctx, AnswerReceiver::class.java).setAction("cz.kutil.dochazka.ANSWER")
                .putExtra(EXTRA_PENDING_ID, TEST_PENDING_ID).putExtra(EXTRA_YES, yes)
            val pi = PendingIntent.getBroadcast(ctx, TEST_ID * 2 + (if (yes) 1 else 0), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(quickAction(if (yes) "ANO" else "NE", pi))
        }
        val fresh = !isActive(ctx, TEST_ID)
        return notifySafe(ctx, TEST_ID, builder).also { if (it && fresh) NotifyVoice.say(ctx, NotifyVoice.Line.TEST) }
    }

    fun cancelTest(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(TEST_ID)

    private fun notifySafe(ctx: Context, id: Int, builder: NotificationCompat.Builder): Boolean = try {
        NotificationManagerCompat.from(ctx).notify(id, builder.build())
        true
    } catch (e: SecurityException) {
        false
    }

    fun cancel(ctx: Context, pendingId: Long) {
        NotificationManagerCompat.from(ctx).cancel(notificationId(pendingId))
    }
}
