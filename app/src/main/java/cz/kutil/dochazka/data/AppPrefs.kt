package cz.kutil.dochazka.data

import android.content.Context

/** Drobná nastavení mimo databázi (měsíční připomenutí, e-mail příjemce výkazu). */
object AppPrefs {
    private const val FILE = "app_flags"
    private const val KEY_REMINDER = "monthly_reminder"
    private const val KEY_EMAIL = "report_email"
    private const val KEY_LAST_REMINDED = "last_reminded_month"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun reminderEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_REMINDER, true)
    fun setReminderEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_REMINDER, on).apply()

    fun reportEmail(ctx: Context): String = prefs(ctx).getString(KEY_EMAIL, "") ?: ""
    fun setReportEmail(ctx: Context, email: String) = prefs(ctx).edit().putString(KEY_EMAIL, email.trim()).apply()

    /** Měsíc (yyyy-MM), za který už připomenutí přišlo. */
    fun lastReminded(ctx: Context): String = prefs(ctx).getString(KEY_LAST_REMINDED, "") ?: ""
    fun setLastReminded(ctx: Context, month: String) = prefs(ctx).edit().putString(KEY_LAST_REMINDED, month).apply()
}
