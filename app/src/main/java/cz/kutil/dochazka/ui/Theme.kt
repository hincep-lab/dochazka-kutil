package cz.kutil.dochazka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Průmyslový instalatérský design: tmavě modré až antracitové pozadí, kovové trubky,
 * oranžové zvýraznění ovládání, zelená pro běžící směnu. Vždy tmavý – je to firemní vzhled,
 * nemění se podle tapety ani systémového režimu (kontrast textu je spočítaný na tmavé pozadí).
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
    val blue = Color(0xFF7FB2FF)

    val text = Color(0xFFF2F5F9)
    val textDim = Color(0xFFB9C6D9)
    val textMuted = Color(0xFF8C9BB2)
}

private val Scheme = darkColorScheme(
    primary = Ind.orange,
    onPrimary = Ind.onOrange,
    primaryContainer = Color(0xFF4A3305),
    onPrimaryContainer = Color(0xFFFFE0B0),
    secondary = Ind.blue,
    onSecondary = Color(0xFF06203F),
    secondaryContainer = Ind.surfaceHi,
    onSecondaryContainer = Ind.text,
    tertiary = Ind.amber,
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF3D2C08),
    onTertiaryContainer = Color(0xFFFFE2A8),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF5C1A15),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Ind.bg,
    onBackground = Ind.text,
    surface = Ind.bg,
    onSurface = Ind.text,
    surfaceVariant = Ind.surfaceHi,
    onSurfaceVariant = Ind.textDim,
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
