package cz.kutil.dochazka.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import cz.kutil.dochazka.track.Notifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Záložka vyžádaná klepnutím na oznámení. */
    private val requestedTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Aplikace je vždy tmavá – světlé ikony systémových lišt i ve světlém režimu telefonu.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        requestedTab.value = requested(intent)
        setContent {
            DochazkaTheme {
                AppRoot(vm, requestedTab)
            }
        }
    }

    /** "pending" nebo "month:2026-10" podle toho, na které oznámení uživatel klepl. */
    private fun requested(intent: Intent?): String? {
        val tab = intent?.getStringExtra(Notifier.EXTRA_OPEN_TAB) ?: return null
        val month = intent.getStringExtra(Notifier.EXTRA_MONTH)
        return if (tab == "month" && month != null) "month:$month" else tab
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = requested(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshZone()
    }
}
