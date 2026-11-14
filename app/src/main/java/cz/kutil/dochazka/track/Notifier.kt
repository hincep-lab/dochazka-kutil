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

    private val TIME = DateTimeFormatter.ofPattern("H:mm")
    private val DAY = DateTimeFormatter.ofPattern("d. M.")

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "Potvrzení docházky", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Dotazy na začátek a konec práce při odchodu z domova a návratu."
        nm.createNotificationChannel(channel)
        val monthly = NotificationChannel(CHANNEL_MONTHLY, "Měsíční uzávěrka", NotificationManager.IMPORTANCE_DEFAULT)
        monthly.description = "Připomenutí prvního dne v měsíci: zkontrolovat a odeslat docházku za minulý měsíc."
        nm.createNotificationChannel(monthly)
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
        p.type == "END" -> "Návrat domů zaznamenán ${day(p.eventDetectedAt)} v ${time(p.eventDetectedAt)}."
        p.pairedEndAt != null -> "Mimo domov ${day(p.eventDetectedAt)} ${time(p.eventDetectedAt)}–${time(p.pairedEndAt)}. Zapsat jako práci?"
        else -> "Odchod z domova zaznamenán ${day(p.eventDetectedAt)} v ${time(p.eventDetectedAt)}."
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
            .setWhen(p.eventDetectedAt)
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
            builder.addAction(0, if (yes) "ANO" else "NE", pi)
        }
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId(p.id), builder.build())
        } catch (e: SecurityException) {
            // Oprávnění bylo mezitím odebráno – návrh zůstává viditelný v aplikaci.
        }
    }

    fun cancel(ctx: Context, pendingId: Long) {
        NotificationManagerCompat.from(ctx).cancel(notificationId(pendingId))
    }
}
