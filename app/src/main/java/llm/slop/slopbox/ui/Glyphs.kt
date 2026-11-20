package llm.slop.slopbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * Vector redraws of the cabbiev2 / Tango pictures. Drawn in a unit square, so
 * they scale to any size. [lit] false draws the greyed "off" variant.
 */
enum class G { Play, Pause, Stop, Next, Prev, Shuffle, RepeatAll, RepeatOne, RepeatOff, Volume,
    Folder, ListQ, ListC, TangoFolder, TangoNote, Cursor, Back, Config }

@Composable
fun Glyph(g: G, modifier: Modifier, lit: Boolean = true, level: Float = 1f) {
    Canvas(modifier) {
        val body = if (lit) Cabbie.gold else Cabbie.track
        val rim = if (lit) Cabbie.GoldDark else Color(0xFF3A3A3A)
        when (g) {
            G.Play -> glossy(poly(0.2f to 0.1f, 0.9f to 0.5f, 0.2f to 0.9f), body, rim)
            G.Pause -> {
                glossy(round(0.18f, 0.12f, 0.28f, 0.76f, 0.08f), body, rim)
                glossy(round(0.54f, 0.12f, 0.28f, 0.76f, 0.08f), body, rim)
            }
            G.Stop -> glossy(round(0.15f, 0.15f, 0.7f, 0.7f, 0.12f), body, rim)
            G.Next -> {
                glossy(poly(0.05f to 0.2f, 0.5f to 0.5f, 0.05f to 0.8f), body, rim)
                glossy(poly(0.5f to 0.2f, 0.95f to 0.5f, 0.5f to 0.8f), body, rim)
            }
            G.Prev -> {
                glossy(poly(0.95f to 0.2f, 0.5f to 0.5f, 0.95f to 0.8f), body, rim)
                glossy(poly(0.5f to 0.2f, 0.05f to 0.5f, 0.5f to 0.8f), body, rim)
            }
            G.Shuffle -> shuffle(body)
            G.RepeatAll, G.RepeatOne, G.RepeatOff -> repeat(if (g == G.RepeatOff) Cabbie.track else body, g == G.RepeatOne)
            G.Volume -> volume(body, level)
            G.Folder -> folder(body, rim)
            G.ListQ, G.ListC -> listSheet(body, rim)
            G.TangoFolder -> folder(
                Brush.verticalGradient(listOf(Cabbie.TangoBlueLight, Cabbie.TangoBlue)), Color(0xFF204A87),
            )
            G.TangoNote -> note()
            G.Cursor -> glossy(
                poly(0.25f to 0.15f, 0.85f to 0.5f, 0.25f to 0.85f),
                Brush.verticalGradient(listOf(Cabbie.TangoGreenLight, Cabbie.TangoGreen)), Color(0xFF2E6005),
            )
            G.Config -> wrench()
            G.Back -> drawPath(
                lines(0.62f to 0.15f, 0.28f to 0.5f, 0.62f to 0.85f),
                Cabbie.Text,
                style = Stroke(size.width * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

// ---- helpers ---------------------------------------------------------------

private fun DrawScope.pt(x: Float, y: Float) = Offset(x * size.width, y * size.height)

private fun DrawScope.poly(vararg p: Pair<Float, Float>) = Path().apply {
    p.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
    close()
}

private fun DrawScope.lines(vararg p: Pair<Float, Float>) = Path().apply {
    p.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
}

private fun DrawScope.round(x: Float, y: Float, w: Float, h: Float, r: Float) = Path().apply {
    addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
            Rect(pt(x, y), Size(w * size.width, h * size.height)),
            CornerRadius(r * size.width, r * size.width),
        )
    )
}

/** Gradient fill, a darker rim, and a highlight over the top part. */
private fun DrawScope.glossy(path: Path, body: Brush, rim: Color) {
    drawPath(path, body)
    clipPath(path) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.05f)),
                startY = 0f, endY = size.height * 0.5f,
            ),
            size = Size(size.width, size.height * 0.48f),
        )
    }
    drawPath(path, rim, style = Stroke(size.width * 0.025f, join = StrokeJoin.Round))
}

private fun DrawScope.shuffle(body: Brush) {
    val w = size.width * 0.09f
    val s = Stroke(w, cap = StrokeCap.Round)
    val a = Path().apply {
        moveTo(0.08f * size.width, 0.28f * size.height)
        cubicTo(0.45f * size.width, 0.28f * size.height, 0.45f * size.width, 0.72f * size.height, 0.78f * size.width, 0.72f * size.height)
    }
    val b = Path().apply {
        moveTo(0.08f * size.width, 0.72f * size.height)
        cubicTo(0.45f * size.width, 0.72f * size.height, 0.45f * size.width, 0.28f * size.height, 0.78f * size.width, 0.28f * size.height)
    }
    drawPath(a, body, style = s)
    drawPath(b, body, style = s)
    drawPath(poly(0.72f to 0.12f, 0.96f to 0.28f, 0.72f to 0.44f), body)
    drawPath(poly(0.72f to 0.56f, 0.96f to 0.72f, 0.72f to 0.88f), body)
}

private fun DrawScope.repeat(body: Brush, one: Boolean) {
    val s = Stroke(size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // A loop with a gap at the top right, arrowhead closing it.
    val loop = Path().apply {
        moveTo(0.62f * size.width, 0.25f * size.height)
        lineTo(0.3f * size.width, 0.25f * size.height)
        quadraticTo(0.1f * size.width, 0.25f * size.height, 0.1f * size.width, 0.5f * size.height)
        quadraticTo(0.1f * size.width, 0.75f * size.height, 0.3f * size.width, 0.75f * size.height)
        lineTo(0.7f * size.width, 0.75f * size.height)
        quadraticTo(0.9f * size.width, 0.75f * size.height, 0.9f * size.width, 0.5f * size.height)
        quadraticTo(0.9f * size.width, 0.36f * size.height, 0.84f * size.width, 0.3f * size.height)
    }
    drawPath(loop, body, style = s)
    drawPath(poly(0.58f to 0.08f, 0.8f to 0.25f, 0.58f to 0.42f), body)
    if (one) {
        drawCircle(Color.Black, radius = size.width * 0.16f, center = pt(0.5f, 0.5f))
        drawPath(
            lines(0.44f to 0.44f, 0.52f to 0.38f, 0.52f to 0.62f),
            Cabbie.Gold,
            style = Stroke(size.width * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.volume(body: Brush, level: Float) {
    glossy(poly(0.08f to 0.38f, 0.26f to 0.38f, 0.48f to 0.16f, 0.48f to 0.84f, 0.26f to 0.62f, 0.08f to 0.62f), body, Cabbie.GoldDark)
    val s = Stroke(size.width * 0.07f, cap = StrokeCap.Round)
    val arcs = when {
        level <= 0f -> 0
        level < 0.34f -> 1
        level < 0.67f -> 2
        else -> 3
    }
    if (arcs == 0) {
        drawLine(Cabbie.Gold, pt(0.62f, 0.36f), pt(0.88f, 0.64f), s.width, StrokeCap.Round)
        drawLine(Cabbie.Gold, pt(0.88f, 0.36f), pt(0.62f, 0.64f), s.width, StrokeCap.Round)
        return
    }
    for (i in 0 until 3) {
        val r = 0.16f + i * 0.13f
        drawArc(
            if (i < arcs) Cabbie.gold else Cabbie.track,
            startAngle = -45f, sweepAngle = 90f, useCenter = false,
            topLeft = pt(0.48f - r, 0.5f - r), size = Size(2 * r * size.width, 2 * r * size.height),
            style = s,
        )
    }
}

private fun DrawScope.folder(body: Brush, rim: Color) {
    val back = Path().apply {
        moveTo(0.06f * size.width, 0.22f * size.height)
        lineTo(0.38f * size.width, 0.22f * size.height)
        lineTo(0.46f * size.width, 0.3f * size.height)
        lineTo(0.92f * size.width, 0.3f * size.height)
        lineTo(0.92f * size.width, 0.82f * size.height)
        lineTo(0.06f * size.width, 0.82f * size.height)
        close()
    }
    drawPath(back, body)
    drawPath(back, Color.Black.copy(alpha = 0.25f))
    drawPath(back, rim, style = Stroke(size.width * 0.025f, join = StrokeJoin.Round))
    glossy(poly(0.14f to 0.4f, 0.98f to 0.4f, 0.9f to 0.84f, 0.04f to 0.84f), body, rim)
}

private fun DrawScope.listSheet(body: Brush, rim: Color) {
    glossy(round(0.08f, 0.12f, 0.84f, 0.76f, 0.08f), body, rim)
    for (i in 0 until 4) {
        val y = 0.26f + i * 0.16f
        drawLine(rim, pt(0.16f, y), pt(0.84f, y), size.width * 0.03f)
    }
    drawLine(rim, pt(0.36f, 0.16f), pt(0.36f, 0.84f), size.width * 0.03f)
}

private fun DrawScope.note() {
    val green = Brush.verticalGradient(listOf(Cabbie.TangoGreenLight, Cabbie.TangoGreen))
    val rim = Color(0xFF2E6005)
    // Two heads joined by a beam.
    glossy(poly(0.36f to 0.2f, 0.88f to 0.08f, 0.88f to 0.24f, 0.44f to 0.36f), green, rim)
    drawLine(rim, pt(0.4f, 0.24f), pt(0.4f, 0.74f), size.width * 0.07f)
    drawLine(rim, pt(0.84f, 0.14f), pt(0.84f, 0.64f), size.width * 0.07f)
    for ((cx, cy) in listOf(0.28f to 0.76f, 0.72f to 0.66f)) {
        val head = Path().apply {
            addOval(Rect(pt(cx - 0.15f, cy - 0.1f), Size(0.3f * size.width, 0.2f * size.height)))
        }
        glossy(head, green, rim)
    }
}

private fun DrawScope.wrench() {
    val metal = Brush.linearGradient(listOf(Color(0xFFEEEEEC), Color(0xFF888A85)), pt(0.2f, 0.2f), pt(0.8f, 0.8f))
    val rim = Color(0xFF555753)
    drawLine(rim, pt(0.2f, 0.8f), pt(0.62f, 0.38f), size.width * 0.2f, StrokeCap.Round)
    drawLine(metal, pt(0.2f, 0.8f), pt(0.62f, 0.38f), size.width * 0.14f, StrokeCap.Round)
    drawCircle(rim, radius = size.width * 0.22f, center = pt(0.68f, 0.32f))
    drawCircle(metal, radius = size.width * 0.19f, center = pt(0.68f, 0.32f))
    // The jaw: a notch cut toward the top right.
    drawPath(poly(0.68f to 0.32f, 0.98f to 0.12f, 0.88f to 0.02f), Color(0xFF2A2A2A))
    drawCircle(Color(0xFF2A2A2A), radius = size.width * 0.07f, center = pt(0.68f, 0.32f))
}
