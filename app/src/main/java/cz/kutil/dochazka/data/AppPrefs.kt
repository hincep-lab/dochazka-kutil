package cz.kutil.dochazka.data

import android.content.Context
import org.json.JSONObject

/**
 * Nastavení a provozní údaje mimo databázi (SharedPreferences). Uživatelská nastavení se zálohují,
 * provozní údaje (poslední odchod, poslední registrace zóny…) slouží jen diagnostice.
 */
object AppPrefs {
    private const val FILE = "app_flags"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- uživatelská nastavení -------------------------------------------------------------
    fun reminderEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("monthly_reminder", true)
    fun setReminderEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("monthly_reminder", on).apply()

    fun reportEmail(ctx: Context): String = prefs(ctx).getString("report_email", "") ?: ""
    fun setReportEmail(ctx: Context, email: String) = prefs(ctx).edit().putString("report_email", email.trim()).apply()

    /** Po kolika hodinách otevřené směny přijde upozornění na zapomenuté ukončení. */
    fun longShiftHours(ctx: Context): Int = prefs(ctx).getInt("long_shift_hours", 12).coerceIn(4, 24)
    fun setLongShiftHours(ctx: Context, hours: Int) = prefs(ctx).edit().putInt("long_shift_hours", hours.coerceIn(4, 24)).apply()

    /** Trvalé oznámení „V práci od …“, dokud běží směna. */
    /** Dekorativní animace (kapající kohoutek, vlnící se voda, proudění v potrubí). */
    fun decorAnimations(ctx: Context): Boolean = prefs(ctx).getBoolean("decor_animations", true)
    fun setDecorAnimations(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("decor_animations", on).apply()

    /** Mluvící oznámení: Kutil řekne nahlas, co se děje (jen v normálním režimu zvonění, 6–22 h). */
    fun talkingNotifications(ctx: Context): Boolean = prefs(ctx).getBoolean("talking_notifications", true)
    fun setTalkingNotifications(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("talking_notifications", on).apply()

    /** Plynulost animací v aplikaci: 60 nebo 120 snímků za sekundu (120 = plná frekvence displeje). */
    fun smoothFps(ctx: Context): Int = if (prefs(ctx).getInt("smooth_fps", 60) >= 120) 120 else 60
    fun setSmoothFps(ctx: Context, fps: Int) = prefs(ctx).edit().putInt("smooth_fps", if (fps >= 120) 120 else 60).apply()

    /** Zvuky vody (kapka, přetékání). Hrají jen v aplikaci a ne v tichém režimu. */
    fun waterSounds(ctx: Context): Boolean = prefs(ctx).getBoolean("water_sounds", true)
    fun setWaterSounds(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("water_sounds", on).apply()

    /** Vtipná postavička instalatéra dole na hlavní obrazovce. */
    fun plumberGag(ctx: Context): Boolean = prefs(ctx).getBoolean("plumber_gag", true)
    fun setPlumberGag(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("plumber_gag", on).apply()

    fun statusNotification(ctx: Context): Boolean = prefs(ctx).getBoolean("status_notification", true)
    fun setStatusNotification(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("status_notification", on).apply()

    fun backupReminder(ctx: Context): Boolean = prefs(ctx).getBoolean("backup_reminder", true)
    fun setBackupReminder(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("backup_reminder", on).apply()

    /** Denní kontrola nové verze na GitHubu. */
    fun autoUpdateCheck(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_update_check", true)
    fun setAutoUpdateCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_update_check", on).apply()

    /** Složka (SAF strom) pro automatickou týdenní zálohu; null = vypnuto. */
    fun autoBackupFolder(ctx: Context): String? = prefs(ctx).getString("auto_backup_folder", null)
    fun setAutoBackupFolder(ctx: Context, uri: String?) = prefs(ctx).edit().putString("auto_backup_folder", uri).apply()

    // --- provozní údaje ----------------------------------------------------------------------
    /** Měsíc (yyyy-MM), za který už přišlo připomenutí uzávěrky. */
    fun lastReminded(ctx: Context): String = prefs(ctx).getString("last_reminded_month", "") ?: ""
    fun setLastReminded(ctx: Context, month: String) = prefs(ctx).edit().putString("last_reminded_month", month).apply()

    fun lastBackupReminded(ctx: Context): String = prefs(ctx).getString("last_backup_reminded", "") ?: ""
    fun setLastBackupReminded(ctx: Context, month: String) = prefs(ctx).edit().putString("last_backup_reminded", month).apply()

    fun latestKnownBuild(ctx: Context): Int = prefs(ctx).getInt("latest_known_build", 0)
    fun setLatestKnownBuild(ctx: Context, build: Int) = prefs(ctx).edit().putInt("latest_known_build", build).apply()
    fun lastUpdateNotified(ctx: Context): Int = prefs(ctx).getInt("last_update_notified", 0)
    fun setLastUpdateNotified(ctx: Context, build: Int) = prefs(ctx).edit().putInt("last_update_notified", build).apply()

    /** Výsledek poslední automatické zálohy: čas a chyba (prázdná = v pořádku). */
    fun autoBackupResult(ctx: Context): Pair<Long, String> =
        prefs(ctx).getLong("auto_backup_at", 0) to (prefs(ctx).getString("auto_backup_err", "") ?: "")
    fun setAutoBackupResult(ctx: Context, at: Long, error: String) =
        prefs(ctx).edit().putLong("auto_backup_at", at).putString("auto_backup_err", error).apply()

    /** Otisk obsahu poslední automatické zálohy – beze změny dat se nová nevytváří. */
    fun autoBackupDigest(ctx: Context): String = prefs(ctx).getString("auto_backup_digest", "") ?: ""
    fun setAutoBackupDigest(ctx: Context, digest: String) = prefs(ctx).edit().putString("auto_backup_digest", digest).apply()

    fun lastBackupAt(ctx: Context): Long = prefs(ctx).getLong("last_backup_at", 0)
    fun setLastBackupAt(ctx: Context, at: Long) = prefs(ctx).edit().putLong("last_backup_at", at).apply()

    /** „Stále pracuji“ – směna a čas, kdy to uživatel potvrdil. */
    fun longShiftAck(ctx: Context): Pair<Long, Long> = prefs(ctx).getLong("long_shift_ack_id", -1) to prefs(ctx).getLong("long_shift_ack_at", 0)
    fun setLongShiftAck(ctx: Context, shiftId: Long, at: Long) =
        prefs(ctx).edit().putLong("long_shift_ack_id", shiftId).putLong("long_shift_ack_at", at).apply()

    /** Kdy naposledy přišlo upozornění na dlouhou směnu (aby se neopakovalo při každé kontrole). */
    fun longShiftNotified(ctx: Context): Pair<Long, Long> =
        prefs(ctx).getLong("long_shift_notified_id", -1) to prefs(ctx).getLong("long_shift_notified_at", 0)
    fun setLongShiftNotified(ctx: Context, shiftId: Long, at: Long) =
        prefs(ctx).edit().putLong("long_shift_notified_id", shiftId).putLong("long_shift_notified_at", at).apply()

    // Diagnostika: skutečně zaznamenané události a výsledky registrace zóny.
    fun lastExitAt(ctx: Context): Long = prefs(ctx).getLong("diag_last_exit", 0)
    fun lastEnterAt(ctx: Context): Long = prefs(ctx).getLong("diag_last_enter", 0)
    fun noteTransition(ctx: Context, exit: Boolean, at: Long) =
        prefs(ctx).edit().putLong(if (exit) "diag_last_exit" else "diag_last_enter", at).apply()

    // Skutečné přechody, které automat přijal (bez duplicit a bez potvrzení zóny při její nové registraci).
    fun lastRealExitAt(ctx: Context): Long = prefs(ctx).getLong("real_last_exit", 0)
    fun lastRealEnterAt(ctx: Context): Long = prefs(ctx).getLong("real_last_enter", 0)
    fun noteRealTransition(ctx: Context, exit: Boolean, at: Long) =
        prefs(ctx).edit().putLong(if (exit) "real_last_exit" else "real_last_enter", at).apply()

    fun lastZoneOkAt(ctx: Context): Long = prefs(ctx).getLong("diag_zone_ok_at", 0)
    fun lastZoneError(ctx: Context): Pair<Long, String> =
        prefs(ctx).getLong("diag_zone_err_at", 0) to (prefs(ctx).getString("diag_zone_err", "") ?: "")
    fun noteZoneResult(ctx: Context, ok: Boolean, message: String) {
        val e = prefs(ctx).edit()
        val now = System.currentTimeMillis()
        if (ok) e.putLong("diag_zone_ok_at", now).putLong("diag_zone_err_at", 0).putString("diag_zone_err", "")
        else e.putLong("diag_zone_err_at", now).putString("diag_zone_err", message)
        e.apply()
    }
    fun noteZoneRemoved(ctx: Context) = prefs(ctx).edit().putLong("diag_zone_ok_at", 0).apply()

    // --- záloha uživatelských nastavení -----------------------------------------------------
    fun toJson(ctx: Context): JSONObject = JSONObject()
        .put("monthlyReminder", reminderEnabled(ctx))
        .put("reportEmail", reportEmail(ctx))
        .put("longShiftHours", longShiftHours(ctx))
        .put("backupReminder", backupReminder(ctx))
        .put("statusNotification", statusNotification(ctx))
        .put("autoUpdateCheck", autoUpdateCheck(ctx))

    fun fromJson(ctx: Context, json: JSONObject) {
        if (json.has("monthlyReminder")) setReminderEnabled(ctx, json.optBoolean("monthlyReminder", true))
        if (json.has("reportEmail")) setReportEmail(ctx, json.optString("reportEmail", ""))
        if (json.has("longShiftHours")) setLongShiftHours(ctx, json.optInt("longShiftHours", 12))
        if (json.has("autoUpdateCheck")) setAutoUpdateCheck(ctx, json.optBoolean("autoUpdateCheck", true))
        if (json.has("statusNotification")) setStatusNotification(ctx, json.optBoolean("statusNotification", true))
        if (json.has("backupReminder")) setBackupReminder(ctx, json.optBoolean("backupReminder", true))
    }
}
