package cz.kutil.dochazka

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.data.PendingEventEntity
import cz.kutil.dochazka.data.SettingsEntity
import cz.kutil.dochazka.data.ShiftEntity
import cz.kutil.dochazka.data.ShiftStatus
import cz.kutil.dochazka.ui.AppRoot
import cz.kutil.dochazka.ui.DochazkaTheme
import cz.kutil.dochazka.ui.HomeScreen
import cz.kutil.dochazka.ui.MainViewModel
import cz.kutil.dochazka.ui.UiState
import cz.kutil.dochazka.ui.WetBackground
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Snímky obrazovek vykreslené Robolectricem (skutečné vykreslování Skia).
 * Neověřují nic automaticky – slouží k vizuální kontrole vzhledu bez telefonu.
 * Ukládají se do app/build/screenshots a v GitHub Actions se nahrají jako artefakt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTests {

    private fun shoot(name: String, prepare: (ComponentActivity) -> Unit = {}, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        prepare(activity)
        activity.setContent { DochazkaTheme { content() } }
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            Thread.sleep(5)
        }
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // zmenšený JPEG pro výpis do záznamu sestavení
        val small = Bitmap.createScaledBitmap(bmp, bmp.width * 6 / 10, bmp.height * 6 / 10, true)
        File(dir, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    }

    private fun ms(d: LocalDate, h: Int, m: Int) = d.atTime(LocalTime.of(h, m)).atZone(PRAGUE).toInstant().toEpochMilli()

    private fun craftedState(open: Boolean, pending: Boolean): UiState {
        val today = LocalDate.now(PRAGUE)
        val now = System.currentTimeMillis()
        val y = today.minusDays(1)
        val shifts = mutableListOf(
            ShiftEntity(id = 1, rawStart = ms(y, 7, 4), rawEnd = ms(y, 15, 41), status = ShiftStatus.CLOSED),
        )
        if (open) {
            val start = ms(today, 7, 2).takeIf { it < now - 30 * 60_000 } ?: (now - 56 * 60_000)
            shifts += ShiftEntity(id = 2, rawStart = start, rawEnd = null, status = ShiftStatus.OPEN)
        }
        val pend = if (pending) {
            listOf(PendingEventEntity(id = 5, type = "START", eventDetectedAt = now - 9 * 60_000, promptCreatedAt = now - 8 * 60_000))
        } else {
            emptyList()
        }
        return UiState(
            loaded = true,
            settings = SettingsEntity(onboardingDone = true, trackingEnabled = true, trackState = if (open) "WORKING" else "HOME"),
            shifts = shifts,
            pending = pend,
        )
    }

    private fun home(name: String, state: UiState) {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        shoot(name) {
            Box(Modifier.fillMaxSize()) {
                WetBackground()
                Box(Modifier.statusBarsPadding()) {
                    HomeScreen(vm, state, onOpenDay = {}, onOpenTab = {}, onEndWork = {}, onOpenDiag = {})
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h1700dp-xxhdpi")
    fun homeWorking() = home("01_prehled_v_praci", craftedState(open = true, pending = false))

    @Test
    @Config(qualifiers = "w412dp-h1700dp-xxhdpi", fontScale = 1.3f)
    fun homeWorkingBigFont() = home("09_prehled_velke_pismo", craftedState(open = true, pending = false))

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun homeWorkingNarrow() = home("10_prehled_uzky_displej", craftedState(open = true, pending = false))

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun homeIdle() = home("02_prehled_doma", craftedState(open = false, pending = false))

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun homePending() = home("03_prehled_dotaz", craftedState(open = false, pending = true))

    /** Celá aplikace s lištami nad ukázkovými daty (demo režim). */
    private fun app(name: String, tab: String?) {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        vm.setDemo(true)
        repeat(200) {
            if (vm.state.value.demo && vm.state.value.shifts.isNotEmpty()) return@repeat
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        val requested = mutableStateOf(tab)
        shoot(name) { AppRoot(vm, requested) }
    }

    @Test
    fun appHome() = app("04_aplikace_prehled", null)

    @Test
    fun appCalendar() = app("05_kalendar", "calendar")

    @Test
    fun appMonth() = app("06_vykaz", "month:" + java.time.YearMonth.now(PRAGUE))

    @Test
    fun appPending() = app("07_potvrdit", "pending")

    @Test
    fun appSettings() = app("08_nastaveni", "settings")
}
