package cz.kutil.dochazka.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import cz.kutil.dochazka.track.Notifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Záložka vyžádaná klepnutím na oznámení. */
    private val requestedTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedTab.value = intent?.getStringExtra(Notifier.EXTRA_OPEN_TAB)
        setContent {
            DochazkaTheme {
                AppRoot(vm, requestedTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = intent.getStringExtra(Notifier.EXTRA_OPEN_TAB)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshZone()
    }
}
