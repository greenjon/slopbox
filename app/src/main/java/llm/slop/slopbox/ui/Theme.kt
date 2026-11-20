package llm.slop.slopbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colours and shapes after Rockbox's cabbiev2 theme (wps/WPSLIST, wps/cabbiev2/,
 * backdrops/cabbiev2.*): grey-to-black backdrop, light grey text, gold
 * gradient line selector, glossy gold controls. Redrawn, not copied bitmaps,
 * so everything scales.
 */
object Cabbie {
    // "foreground color: CCCCCC", "background color: 000000"
    val Text = Color(0xFFCCCCCC)
    val Dim = Color(0xFF8C8C8C)
    // "line selector start/end color: fde89e / b58c00", "line selector text color: 000000"
    val SelTop = Color(0xFFFDE89E)
    val SelBottom = Color(0xFFB58C00)
    val SelText = Color(0xFF000000)
    // "list separator color: 808080"
    val Separator = Color(0xFF808080)
    // The WPS header text colour (%Vf(0F0B00)).
    val HeaderText = Color(0xFF0F0B00)

    val GoldLight = Color(0xFFFFEF8A)
    val Gold = Color(0xFFF0C400)
    val GoldDark = Color(0xFFB08600)
    val Track = Color(0xFF505050)
    val TrackDark = Color(0xFF2E2E2E)

    // Tango palette, for the list icons (cabbiev2 uses the Tango iconset).
    val TangoBlueLight = Color(0xFF9DBEE6)
    val TangoBlue = Color(0xFF3465A4)
    val TangoGreenLight = Color(0xFFB5EE6A)
    val TangoGreen = Color(0xFF4E9A06)

    val backdrop = Brush.verticalGradient(
        0f to Color(0xFF5C5C5C),
        0.5f to Color(0xFF242424),
        1f to Color(0xFF000000),
    )
    val selector = Brush.verticalGradient(listOf(SelTop, SelBottom))
    val gold = Brush.verticalGradient(listOf(GoldLight, Gold, GoldDark))
    val track = Brush.verticalGradient(listOf(Color(0xFF6A6A6A), Track, TrackDark))

    val font = FontFamily.SansSerif
    val body = TextStyle(fontFamily = font, fontSize = 18.sp, color = Text)
    val small = TextStyle(fontFamily = font, fontSize = 15.sp, color = Text)
    val header = TextStyle(fontFamily = font, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HeaderText)
}

/** Full-screen cabbiev2 backdrop. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Cabbie.backdrop), content = content)
}

/**
 * A glossy pill (the WPS header bar, the progress bar): gradient body, a darker
 * rim and a soft highlight across the top half.
 */
fun Modifier.glossyPill(body: Brush, rim: Color): Modifier = drawBehind {
    val r = CornerRadius(size.height / 2, size.height / 2)
    drawRoundRect(body, cornerRadius = r)
    drawRoundRect(rim, cornerRadius = r, style = Stroke(1.dp.toPx()))
    val inset = 2.dp.toPx()
    val hh = (size.height / 2 - inset).coerceAtLeast(0f)
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f)),
            startY = inset, endY = inset + hh,
        ),
        topLeft = Offset(inset * 2, inset),
        size = Size(size.width - inset * 4, hh),
        cornerRadius = CornerRadius(hh / 2, hh / 2),
    )
}
