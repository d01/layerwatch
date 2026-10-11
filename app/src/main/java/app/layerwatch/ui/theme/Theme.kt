package app.layerwatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.layerwatch.R

/** The app's palette: graphite ground, amber for heat/progress/primary, blue for connection/info. */
@Immutable
object Palette {
    val ground = Color(0xFF0B0D0F)
    val card = Color(0xFF15181B)
    val cardRaised = Color(0xFF1C2024)
    val well = Color(0xFF0F1214)
    val track = Color(0xFF262B30)
    val hairline = Color(0xFF23282D)
    val outline = Color(0xFF2A2F35)
    val text = Color(0xFFEDEFF1)
    val muted = Color(0xFFA2AAB2)
    val faint = Color(0xFF858E97)
    val idleBar = Color(0xFF5C646C)

    /** Heat, progress and the primary action. */
    val amber = Color(0xFFF4B04A)
    val onAmber = Color(0xFF1A1306)
    val amberSoft = Color(0xFF2A2213)
    val amberText = Color(0xFFF4C77A)

    /** A heater still climbing to its target: hotter than amber's steady hold. */
    val ember = Color(0xFFFF6B2C)
    val emberText = Color(0xFFFF9A5C)

    /** Connection and information. */
    val blue = Color(0xFF8AB8FF)
    val onBlue = Color(0xFF0B1A2E)

    /** Destructive: stop. */
    val danger = Color(0xFFFF9E94)
    val dangerSoft = Color(0xFF1A1213)
    val dangerOutline = Color(0xFF5A2A2A)
    val dangerCaption = Color(0xFFC98A84)
    val errorSoft = Color(0xFF2A1718)
    val errorText = Color(0xFFFFB3AB)
}

private fun variable(res: Int, weight: Int) = Font(
    res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Geist = FontFamily(
    variable(R.font.geist, 400),
    variable(R.font.geist, 500),
    variable(R.font.geist, 600),
    variable(R.font.geist, 700),
)

val GeistMono = FontFamily(
    variable(R.font.geist_mono, 400),
    variable(R.font.geist_mono, 500),
    variable(R.font.geist_mono, 600),
)

/** Text styles the dashboard uses, named for their role rather than size. */
@Immutable
data class BambuType(
    val title: TextStyle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = (-0.01).em),
    val jobName: TextStyle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    val section: TextStyle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    val eyebrow: TextStyle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.08.em),
    val body: TextStyle = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp),
    val label: TextStyle = TextStyle(fontFamily = Geist, fontSize = 13.sp),
    val caption: TextStyle = TextStyle(fontFamily = Geist, fontSize = 12.sp),
    val tiny: TextStyle = TextStyle(fontFamily = Geist, fontSize = 11.sp),
    val button: TextStyle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    val heroNumber: TextStyle = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 64.sp, letterSpacing = (-0.04).em, lineHeight = 64.sp),
    val heroUnit: TextStyle = TextStyle(fontFamily = GeistMono, fontSize = 26.sp),
    val metric: TextStyle = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 26.sp, letterSpacing = (-0.02).em),
    val metricSmall: TextStyle = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    val mono: TextStyle = TextStyle(fontFamily = GeistMono, fontSize = 13.sp),
)

val LocalBambuType = staticCompositionLocalOf { BambuType() }

@Composable
fun LayerwatchTheme(content: @Composable () -> Unit) {
    // Always dark: the camera is the hero and a dark ground keeps it so.
    val scheme = darkColorScheme(
        primary = Palette.amber,
        onPrimary = Palette.onAmber,
        secondary = Palette.blue,
        onSecondary = Palette.onBlue,
        background = Palette.ground,
        onBackground = Palette.text,
        surface = Palette.ground,
        onSurface = Palette.text,
        surfaceVariant = Palette.card,
        onSurfaceVariant = Palette.muted,
        outline = Palette.outline,
        error = Palette.danger,
    )
    val base = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = Geist),
            bodyMedium = base.bodyMedium.copy(fontFamily = Geist),
            bodySmall = base.bodySmall.copy(fontFamily = Geist),
            labelLarge = base.labelLarge.copy(fontFamily = Geist),
            titleMedium = base.titleMedium.copy(fontFamily = Geist),
            headlineSmall = base.headlineSmall.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBambuType provides BambuType(), content = content)
    }
}

/** Single-line ellipsized text is the default for labels in tight cards. */
val Ellipsis = TextOverflow.Ellipsis
