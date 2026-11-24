package cz.kutil.dochazka.track

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
    const val CHANNEL_STATUS = "work_status"
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
        notifySafe(ctx, DONE_ID, builder)
    }

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
            NotificationManagerCompat.from(ctx).notify(MONTHLY_ID, builder.build())
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

    fun detail(p: PendingEventEntity): String = when {
        p.type == "END" -> "Návrat domů zaznamenán ${day(p.effectiveAt)} v ${time(p.effectiveAt)}."
        p.pairedEndAt != null -> "Mimo domov ${day(p.effectiveAt)} ${time(p.effectiveAt)}–${time(p.pairedEndAt)}. Zapsat jako práci?"
        else -> "Odchod z domova zaznamenán ${day(p.effectiveAt)} v ${time(p.effectiveAt)}."
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail(p) + " Čas půjde v aplikaci upravit."))
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
            NotificationManagerCompat.from(ctx).notify(notificationId(p.id), builder.build())
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
        return notifySafe(ctx, LONG_SHIFT_ID, builder)
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
        val text = (if (lastBackupMs > 0) "Poslední záloha: ${day(lastBackupMs)}" else "Docházka ještě nebyla zálohována") +
            ". Klepni a ulož si zálohu, ať o data nepřijdeš při ztrátě telefonu."
        val builder = NotificationCompat.Builder(ctx, CHANNEL_MONTHLY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Zálohuj docházku")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
        return notifySafe(ctx, BACKUP_ID, builder)
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
        return notifySafe(ctx, TEST_ID, builder)
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
