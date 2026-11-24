package cz.kutil.dochazka.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cz.kutil.dochazka.track.GeofenceManager
import cz.kutil.dochazka.track.Notifier

data class PermStatus(
    val fine: Boolean,
    val background: Boolean,
    val notifications: Boolean,
    val locationOn: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val trackingReady: Boolean get() = fine && background && locationOn
}

fun readPermStatus(ctx: Context): PermStatus {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return PermStatus(
        fine = GeofenceManager.hasFine(ctx),
        background = GeofenceManager.hasBackground(ctx),
        notifications = Notifier.canNotify(ctx),
        locationOn = GeofenceManager.locationEnabled(ctx),
        batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false,
    )
}

/** Stav oprávnění, který se znovu načte při každém návratu do aplikace (např. ze systémového nastavení). */
@Composable
fun rememberPermStatus(extraKey: Int = 0): PermStatus {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return remember(tick, extraKey) { readPermStatus(context) }
}

private fun start(ctx: Context, intent: Intent, fallback: Intent? = null) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        if (fallback != null) {
            try {
                ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                // zařízení nemá odpovídající obrazovku nastavení
            }
        }
    }
}

object SystemScreens {
    private fun appDetails(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

    fun appSettings(ctx: Context) = start(ctx, appDetails(ctx))

    fun locationSettings(ctx: Context) = start(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), appDetails(ctx))

    fun notificationSettings(ctx: Context) = start(
        ctx,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
        appDetails(ctx),
    )

    fun batterySettings(ctx: Context) = start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(ctx))
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MetalCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ind.text)
        content()
    }
}
