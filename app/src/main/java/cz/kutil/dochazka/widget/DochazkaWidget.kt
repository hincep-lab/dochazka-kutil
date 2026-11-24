package cz.kutil.dochazka.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import cz.kutil.dochazka.R
import cz.kutil.dochazka.core.WorkCalc
import cz.kutil.dochazka.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Widget na plochu: stav, dnešní a měsíční hodiny, tlačítko Začít / Ukončit práci. */
class DochazkaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val pending = goAsync()
            scope.launch {
                try {
                    QuickActions.toggle(context)
                } catch (e: Exception) {
                    // stav zůstává v databázi
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "cz.kutil.dochazka.WIDGET_TOGGLE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        suspend fun updateAll(ctx: Context) {
            val app = ctx.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, DochazkaWidget::class.java))
            if (ids.isEmpty()) return
            val s = QuickActions.status(app)
            val views = build(app, s)
            manager.updateAppWidget(ids, views)
            scheduleLevelRefresh(app, s.working)
        }

        /** Sestaví vzhled widgetu (oddělené kvůli testu vzhledu). */
        fun build(app: Context, s: QuickStatus, nowMs: Long = System.currentTimeMillis()): RemoteViews {
            val views = RemoteViews(app.packageName, R.layout.widget_dochazka)
            val start = s.startMs
            applyWater(app, views, s, nowMs)
            if (s.working && start != null) {
                views.setTextViewText(R.id.w_status, "PRÁVĚ PRACUJEŠ")
                views.setInt(R.id.w_status_bar, "setBackgroundResource", R.drawable.widget_status_working)
                views.setTextViewText(R.id.w_start, "Začátek ${s.sinceText}")
                // Živý časovač počítá sám launcher – aplikace kvůli němu nemusí běžet ani widget obnovovat.
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - start)
                views.setChronometer(R.id.w_timer, base, null, true)
                views.setViewVisibility(R.id.w_timer, View.VISIBLE)
                views.setViewVisibility(R.id.w_today, View.GONE)
                views.setTextViewText(R.id.w_button, "Ukončit práci")
                views.setInt(R.id.w_button, "setBackgroundResource", R.drawable.widget_button_stop)
            } else {
                views.setTextViewText(R.id.w_status, if (s.pending > 0) "ČEKÁ NA POTVRZENÍ" else "DOMA")
                views.setInt(R.id.w_status_bar, "setBackgroundResource", if (s.pending > 0) R.drawable.widget_status_pending else R.drawable.widget_status_idle)
                views.setTextViewText(R.id.w_start, "Dnes odpracováno")
                views.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime(), null, false)
                views.setViewVisibility(R.id.w_timer, View.GONE)
                views.setViewVisibility(R.id.w_today, View.VISIBLE)
                views.setTextViewText(R.id.w_today, "${WorkCalc.formatHm(s.todayMinutes)} h")
                views.setTextViewText(R.id.w_button, "Začít práci")
                views.setInt(R.id.w_button, "setBackgroundResource", R.drawable.widget_button_go)
            }
            if (s.pending > 0) {
                views.setViewVisibility(R.id.w_pending, View.VISIBLE)
                views.setTextViewText(R.id.w_pending, "K potvrzení: ${s.pending}")
            } else {
                views.setViewVisibility(R.id.w_pending, View.GONE)
            }
            val toggle = PendingIntent.getBroadcast(
                app, 601,
                Intent(app, DochazkaWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                app, 602,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.w_button, toggle)
            views.setOnClickPendingIntent(R.id.w_root, open)
            return views
        }

        /**
         * Voda v nádobě jako v aplikaci: v práci z kohoutku teče proud a nádoba se plní k 8 hodinám,
         * po nich přetéká; doma jen kape. Animace (60+ snímků/s) počítá launcher sám, bez zvuku.
         */
        private fun applyWater(app: Context, views: RemoteViews, s: QuickStatus, nowMs: Long) {
            val start = s.startMs
            val working = s.working && start != null
            val level = if (working) (nowMs - start!!) / (8f * 3_600_000f) else 0f
            val over = level > 1f
            val fill = level.coerceIn(0.12f, 1f)
            views.setFloat(R.id.w_level, "setWeightSum", 1f / fill)
            views.setInt(R.id.w_vessel, "setBackgroundResource", if (!working && s.pending > 0) R.drawable.widget_glass_pending else R.drawable.widget_glass)
            val animate = animationsAllowed(app)
            fun show(id: Int, on: Boolean) = views.setViewVisibility(id, if (on) View.VISIBLE else View.GONE)
            show(R.id.w_wave, animate)
            show(R.id.w_wave_static, !animate)
            show(R.id.w_stream, animate && working && !over)
            show(R.id.w_foam, animate && working && !over)
            show(R.id.w_drip, animate && !working)
            show(R.id.w_ripple, animate && !working)
            show(R.id.w_over, animate && over)
        }

        private fun animationsAllowed(ctx: Context): Boolean {
            val system = runCatching {
                android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            return cz.kutil.dochazka.data.AppPrefs.decorAnimations(ctx) && system > 0f
        }

        /** Během směny widget každých 10 minut přepočítá výšku hladiny (jinak se nic neděje). */
        private fun scheduleLevelRefresh(ctx: Context, working: Boolean) {
            val wm = androidx.work.WorkManager.getInstance(ctx)
            if (working) {
                wm.enqueueUniqueWork(
                    "widget_level", androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<WidgetLevelWorker>().setInitialDelay(10, java.util.concurrent.TimeUnit.MINUTES).build(),
                )
            } else {
                wm.cancelUniqueWork("widget_level")
            }
        }
    }
}

/** Obnoví výšku vody ve widgetu během směny. */
class WidgetLevelWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { DochazkaWidget.updateAll(applicationContext) }
        return Result.success()
    }
}
