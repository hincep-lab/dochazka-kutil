package cz.kutil.dochazka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Průmyslový instalatérský design: tmavě modré mokré pozadí, chromové trubky a armatury,
 * světlé karty z kartáčovaného kovu, lesklá tlačítka. Zelená = běžící směna, červená = konec.
 * Nemění se podle tapety ani systémového režimu.
 */
object Ind {
    val bgTop = Color(0xFF17304F)
    val bg = Color(0xFF0F1E33)
    val bgDeep = Color(0xFF0A1424)
    val surface = Color(0xFF172840)
    val surfaceHi = Color(0xFF1F3554)
    val outline = Color(0xFF2E4A72)

    val metalLight = Color(0xFFEEF2F6)
    val metalMid = Color(0xFFAEB8C4)
    val metalDark = Color(0xFF66717F)
    val htGray = Color(0xFF9AA3AD)
    val copper = Color(0xFFC7743A)

    val orange = Color(0xFFF59E1B)
    val orangeDeep = Color(0xFFC97508)
    val onOrange = Color(0xFF1F1200)
    val green = Color(0xFF3AA34C)
    val greenDeep = Color(0xFF1D6A2C)
    val greenLight = Color(0xFF6EE27F)
    val red = Color(0xFFE5483C)
    val redDeep = Color(0xFFA51F17)
    val amber = Color(0xFFF5B841)
    val amberDeep = Color(0xFF8A5A00)
    /** Oranžový text čitelný na světlé kovové kartě. */
    val amberText = Color(0xFFB45F00)
    val blue = Color(0xFF2F6FE0)
    val blueLight = Color(0xFF9FC5FF)

    // Text na SVĚTLÝCH kovových kartách (většina obsahu).
    val text = Color(0xFF14233A)
    val textDim = Color(0xFF45566E)
    val textMuted = Color(0xFF66768C)

    // Text přímo na TMAVÉM mokrém pozadí.
    val onBg = Color(0xFFF4F7FB)
    val onBgDim = Color(0xFFC3D1E4)
}

private val Scheme = darkColorScheme(
    primary = Ind.orange,
    onPrimary = Ind.onOrange,
    primaryContainer = Color(0xFF4A3305),
    onPrimaryContainer = Color(0xFFFFE0B0),
    secondary = Ind.blueLight,
    onSecondary = Color(0xFF06203F),
    secondaryContainer = Ind.surfaceHi,
    onSecondaryContainer = Ind.onBg,
    tertiary = Ind.amber,
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF3D2C08),
    onTertiaryContainer = Color(0xFFFFE2A8),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF5C1A15),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Ind.bg,
    onBackground = Ind.onBg,
    surface = Ind.bg,
    onSurface = Ind.onBg,
    surfaceVariant = Ind.surfaceHi,
    onSurfaceVariant = Ind.onBgDim,
    surfaceContainerLowest = Ind.bgDeep,
    surfaceContainerLow = Color(0xFF13243B),
    surfaceContainer = Ind.surface,
    surfaceContainerHigh = Ind.surface,
    surfaceContainerHighest = Ind.surfaceHi,
    outline = Ind.outline,
    outlineVariant = Color(0xFF24395A),
    inverseSurface = Ind.metalLight,
    inverseOnSurface = Ind.bg,
)

/** Barvy uvnitř světlých kovových karet – tmavý text, modré ovládací prvky. */
val CardScheme = lightColorScheme(
    primary = Color(0xFF1F5FCC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FA),
    onPrimaryContainer = Color(0xFF0B2A5C),
    secondary = Color(0xFF3A5478),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4EF),
    onSecondaryContainer = Ind.text,
    tertiary = Ind.amberText,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3C2),
    onTertiaryContainer = Color(0xFF3A2000),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF5C0E0A),
    background = Color(0xFFE9EDF2),
    onBackground = Ind.text,
    surface = Color(0xFFF2F5F8),
    onSurface = Ind.text,
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Ind.textDim,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F5F8),
    surfaceContainer = Color(0xFFE9EDF2),
    surfaceContainerHigh = Color(0xFFE3E8EE),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    outline = Color(0xFF7D8B9E),
    outlineVariant = Color(0xFFB7C1CE),
)

private val Type = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
fun DochazkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
