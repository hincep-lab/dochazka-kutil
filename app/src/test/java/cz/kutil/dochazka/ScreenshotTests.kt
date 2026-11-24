package cz.kutil.dochazka

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    private fun craftedState(open: Boolean, pending: Boolean, hoursAgo: Double? = null): UiState {
        val today = LocalDate.now(PRAGUE)
        val now = System.currentTimeMillis()
        val y = today.minusDays(1)
        val shifts = mutableListOf(
            ShiftEntity(id = 1, rawStart = ms(y, 7, 4), rawEnd = ms(y, 15, 41), status = ShiftStatus.CLOSED),
        )
        if (open) {
            val start = hoursAgo?.let { now - (it * 3_600_000).toLong() } ?: (ms(today, 7, 2).takeIf { it < now - 30 * 60_000 } ?: (now - 56 * 60_000))
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
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun homeOvertime() = home("11_prehled_prescas", craftedState(open = true, pending = false, hoursAgo = 9.4))

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun homeShortShift() = home("12_prehled_kratka_smena", craftedState(open = true, pending = false, hoursAgo = 2.2))

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

    /** Scénka s instalatérem v několika okamžicích pod sebou. */
    @Test
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    fun plumberGag() {
        val times = listOf(2.0f, 3.9f, 5.25f, 6.3f, 7.6f, 10.4f, 12.0f, 14.2f, 15.3f)
        shoot("13_instalater_scenka") {
            Box(Modifier.fillMaxSize()) {
                WetBackground()
                androidx.compose.foundation.layout.Column {
                    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(37.3f) }
                    for (e in times) {
                        cz.kutil.dochazka.ui.PlumberWorkshop(
                            clock, cz.kutil.dochazka.ui.WaterState(1f, 0f), animate = true, working = false,
                            onSound = {}, fixedElapsed = e, fixedScene = cz.kutil.dochazka.ui.GagScene.PIPE,
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    fun toiletGag() {
        val times = listOf(2.0f, 3.8f, 5.2f, 7.4f, 8.8f, 10.6f, 11.6f, 12.8f, 14.5f)
        shoot("14_instalater_zachod") {
            Box(Modifier.fillMaxSize()) {
                WetBackground()
                androidx.compose.foundation.layout.Column {
                    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(37.3f) }
                    for (e in times) {
                        cz.kutil.dochazka.ui.PlumberWorkshop(
                            clock, cz.kutil.dochazka.ui.WaterState(1f, 0f), animate = true, working = false,
                            onSound = {}, fixedElapsed = e, fixedScene = cz.kutil.dochazka.ui.GagScene.TOILET,
                        )
                    }
                }
            }
        }
    }

    private fun stage(name: String, act: cz.kutil.dochazka.ui.Act, times: List<Float>) {
        shoot(name) {
            Box(Modifier.fillMaxSize()) {
                WetBackground()
                androidx.compose.foundation.layout.Column {
                    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(37.3f) }
                    for (e in times) {
                        cz.kutil.dochazka.ui.PlumberStage(
                            clock, cz.kutil.dochazka.ui.WaterState(1f, 0f), animate = true, onSound = {},
                            modifier = Modifier.fillMaxWidth().height(cz.kutil.dochazka.ui.StageHeight),
                            fixedAct = act, fixedElapsed = e,
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageLadder() = stage("15_stafle", cz.kutil.dochazka.ui.Act.LADDER, listOf(2f, 5.5f, 8f, 10.2f, 11.8f, 13.4f, 15f, 16.5f, 17f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageSink() = stage("16_drez", cz.kutil.dochazka.ui.Act.SINK, listOf(2f, 4.6f, 7f, 10.3f, 11.2f, 12.5f, 13.6f, 15f, 16f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageTub() = stage("17_vana", cz.kutil.dochazka.ui.Act.TUB, listOf(2f, 3.8f, 6f, 10f, 12.15f, 12.6f, 13.6f, 14.8f, 16.5f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageLounge() {
        shoot("19_televize_a_spanek") {
            Box(Modifier.fillMaxSize()) {
                WetBackground()
                androidx.compose.foundation.layout.Column {
                    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(37.3f) }
                    for ((act, e) in listOf(cz.kutil.dochazka.ui.Act.TV to 2f, cz.kutil.dochazka.ui.Act.TV to 4.2f, cz.kutil.dochazka.ui.Act.TV to 9.5f,
                        cz.kutil.dochazka.ui.Act.TV to 18.5f, cz.kutil.dochazka.ui.Act.SLEEP to 3f, cz.kutil.dochazka.ui.Act.SLEEP to 10f)) {
                        cz.kutil.dochazka.ui.PlumberStage(
                            clock, cz.kutil.dochazka.ui.WaterState(0f, 0f), animate = true, onSound = {},
                            modifier = Modifier.fillMaxWidth().height(cz.kutil.dochazka.ui.StageHeight),
                            fixedAct = act, fixedElapsed = e,
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageValve() = stage("20_ventil", cz.kutil.dochazka.ui.Act.VALVE, listOf(4f, 6.5f, 7.6f, 9f, 10.6f, 12f, 13.5f, 14f, 15.5f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageTape() = stage("21_paska", cz.kutil.dochazka.ui.Act.TAPE, listOf(4f, 6.5f, 8f, 9.8f, 10.6f, 11.6f, 13.4f, 14.2f, 15.5f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageBucket() = stage("22_kbelik", cz.kutil.dochazka.ui.Act.BUCKET, listOf(2f, 4f, 5.8f, 7f, 8.2f, 9.2f, 10.5f, 12.8f, 13.6f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageFlood() = stage("23_povoden", cz.kutil.dochazka.ui.Act.FLOOD, listOf(1.5f, 4f, 7f, 10.5f, 11.8f, 13f, 13.8f, 15f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageCoffee() = stage("24_kava", cz.kutil.dochazka.ui.Act.COFFEE, listOf(2f, 5.1f, 6.4f, 8.4f, 9.8f, 10.5f, 13.5f, 15.4f, 16.4f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageSpiral() = stage("25_spirala", cz.kutil.dochazka.ui.Act.SPIRAL, listOf(3.5f, 5f, 6.9f, 8f, 9.5f, 11f, 12f, 13.4f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageEndShift() = stage("26_konec_smeny", cz.kutil.dochazka.ui.Act.END_SHIFT, listOf(1f, 2.8f, 3.7f, 5.5f, 6.8f, 7.8f, 8.8f, 10f))

    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun stageWander() = stage("18_prochazka", cz.kutil.dochazka.ui.Act.WANDER, listOf(2f, 5f, 8f))

    /**
     * Ukázkové video (snímky 24/s) – běží jen když je nastavené MAKE_VIDEO=1.
     * Přehled v přesčasu: přetéká nádoba, kape kohoutek a Kutil hraje dvě scénky.
     */
    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun demoVideo() {
        org.junit.Assume.assumeTrue(System.getenv("MAKE_VIDEO") == "1")
        android.provider.Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
        cz.kutil.dochazka.ui.PlumberDebug.forcedOrder = listOf(cz.kutil.dochazka.ui.Act.VALVE, cz.kutil.dochazka.ui.Act.BUCKET, cz.kutil.dochazka.ui.Act.COFFEE)
        val fps = 24
        var frame = 0
        val manualClock = androidx.compose.runtime.mutableFloatStateOf(0f)
        cz.kutil.dochazka.ui.WaterClockDebug.manual = manualClock
        cz.kutil.dochazka.ui.SoundTap.listener = { name -> println("SOUND_EVENT $frame $name") }
        // scénář videa: utržený ventil, hasák a kbelík, přestávka na kávu
        cz.kutil.dochazka.ui.PlumberDebug.script = { t ->
            when {
                t in 1f..18f -> cz.kutil.dochazka.ui.Act.VALVE to (t - 1f)
                t in 18.5f..35f -> cz.kutil.dochazka.ui.Act.BUCKET to (t - 18.5f)
                t in 35.5f..54f -> cz.kutil.dochazka.ui.Act.COFFEE to (t - 35.5f)
                else -> null
            }
        }
        val state = craftedState(open = true, pending = false, hoursAgo = 5.5)
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            DochazkaTheme {
                Box(Modifier.fillMaxSize()) {
                    WetBackground()
                    Box(Modifier.statusBarsPadding()) {
                        HomeScreen(vm, state, onOpenDay = {}, onOpenTab = {}, onEndWork = {}, onOpenDiag = {})
                    }
                }
            }
        }
        val dir = File("build/video").apply { mkdirs() }
        val total = (54.5 * fps).toInt()
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        while (frame < total) {
            manualClock.floatValue = frame / fps.toFloat()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000L / fps))
            bmp.eraseColor(android.graphics.Color.BLACK)
            view.draw(canvas)
            val small = Bitmap.createScaledBitmap(bmp, 720, bmp.height * 720 / bmp.width / 2 * 2, true)
            File(dir, "f%05d.jpg".format(frame)).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            small.recycle()
            frame++
        }
        cz.kutil.dochazka.ui.SoundTap.listener = null
        cz.kutil.dochazka.ui.PlumberDebug.forcedOrder = null
        cz.kutil.dochazka.ui.PlumberDebug.script = null
        cz.kutil.dochazka.ui.WaterClockDebug.manual = null
    }

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
        println("SNIMEK $name: pozadavek=$tab zbyva=${requested.value} demo=${vm.state.value.demo} loaded=${vm.state.value.loaded}")
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
