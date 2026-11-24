package cz.kutil.dochazka.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cz.kutil.dochazka.R
import cz.kutil.dochazka.core.WorkCalc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dlaždice v rychlém nastavení (stažená lišta): aktivní = v práci. Klepnutí začne nebo ukončí práci.
 * Na zamčeném telefonu se nejdřív vyžádá odemknutí, aby se směna nespustila omylem v kapse.
 */
class DochazkaTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        scope.launch {
            val s = try {
                QuickActions.status(applicationContext)
            } catch (e: Exception) {
                return@launch
            }
            val tile = qsTile ?: return@launch
            tile.icon = Icon.createWithResource(this@DochazkaTile, R.drawable.ic_notif)
            tile.label = if (s.working) "V práci" else "Docházka"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = if (s.working) "od ${s.sinceText} · dnes ${WorkCalc.formatHm(s.todayMinutes)}" else "Začít práci"
            }
            tile.state = if (s.working) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val run = Runnable {
            scope.launch {
                try {
                    QuickActions.toggle(applicationContext)
                } catch (e: Exception) {
                    // stav zůstává v databázi
                }
                refresh()
            }
        }
        if (isLocked) unlockAndRun(run) else run.run()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestRefresh(ctx: Context) {
            runCatching { requestListeningState(ctx.applicationContext, ComponentName(ctx.applicationContext, DochazkaTile::class.java)) }
        }
    }
}
