package app.raah.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.raah.R

/**
 * The device's palette, so the phone and the handlebar read as one product: pure black, white figures,
 * and four signal hues that each mean one thing (GO green, NEAR yellow, LINK blue, ALERT red).
 * Keep in step with ui/ui_theme.h.
 */
object Raah {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF101318)
    val SurfaceHi = Color(0xFF1A1F26)
    val Line = Color(0xFF2C333D)
    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0xFFB4BDC9)
    val Text3 = Color(0xFF737D8C)
    val Go = Color(0xFF1FF08A)
    val Near = Color(0xFFFFD000)
    val Link = Color(0xFF38AEFF)
    val Alert = Color(0xFFFF4545)
    val OnSignal = Color(0xFF000000)

    /** Body text: Barlow, the device's typeface. */
    val Body = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
    )

    /** Figures and headings: Barlow Semi Condensed, as on the device's distance readout. */
    val Display = FontFamily(
        Font(R.font.barlowsemicondensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlowsemicondensed_bold, FontWeight.Bold),
    )
}

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Raah.Display, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = Raah.Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontFamily = Raah.Display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Raah.Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Raah.Body, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Raah.Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Raah.Body, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Raah.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Raah.Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Raah.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Raah.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 1.2.sp),
)

@Composable
fun RaahTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Raah.Go, onPrimary = Raah.OnSignal,
            secondary = Raah.Link, onSecondary = Raah.OnSignal,
            error = Raah.Alert, onError = Raah.OnSignal,
            background = Raah.Bg, onBackground = Raah.Text,
            surface = Raah.Bg, onSurface = Raah.Text,
            surfaceVariant = Raah.Surface, onSurfaceVariant = Raah.Text2,
            surfaceContainer = Raah.Surface, surfaceContainerHigh = Raah.SurfaceHi,
            surfaceContainerHighest = Raah.SurfaceHi,
            outline = Raah.Line, outlineVariant = Raah.Line,
        ),
        typography = typography,
        content = content,
    )
}
