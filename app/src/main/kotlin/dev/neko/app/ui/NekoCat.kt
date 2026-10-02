package dev.neko.app.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/** How Neko looks right now: HAPPY smiles with closed eyes, THINKING looks at you, TALKING also moves its mouth. */
enum class CatMood { HAPPY, THINKING, TALKING }

private class ParsedShape(val path: Path, val fill: Long, val stroke: Long, val width: Float)

private val parsedLayers: Map<String, List<ParsedShape>> by lazy {
    val parser = PathParser()
    NekoArt.layers.mapValues { (_, shapes) -> shapes.map { ParsedShape(parser.parsePathString(it.d).toPath(), it.fill, it.stroke, it.width) } }
}

private fun DrawScope.paint(layer: String) {
    for (shape in parsedLayers.getValue(layer)) {
        if (shape.fill != 0L) drawPath(shape.path, Color(shape.fill))
        if (shape.stroke != 0L) drawPath(shape.path, Color(shape.stroke), style = Stroke(shape.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Neko the lucky cat, drawn from tools/art/neko_cat.svg (see NekoArt.kt). Motion is skipped when [animate] is false. */
@Composable fun NekoCat(modifier: Modifier = Modifier, animate: Boolean = true, mood: CatMood = CatMood.HAPPY) {
    val moving = animate && ValueAnimator.areAnimatorsEnabled()
    var wave = 0f
    var bob = 0f
    if (moving) {
        val transition = rememberInfiniteTransition(label = "Neko idle")
        wave = transition.animateFloat(-6f, 9f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Paw wave").value
        bob = transition.animateFloat(-2f, 3f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Bob").value
    }
    var blink by remember { mutableStateOf(false) }
    var mouthOpen by remember { mutableStateOf(true) }
    LaunchedEffect(moving, mood) {
        if (!moving) return@LaunchedEffect
        if (mood == CatMood.TALKING) while (true) { mouthOpen = !mouthOpen; delay(190) }
        else if (mood == CatMood.HAPPY) mouthOpen = true
        else mouthOpen = false
    }
    LaunchedEffect(moving, mood) {
        if (!moving || mood == CatMood.HAPPY) return@LaunchedEffect
        while (true) { delay(3200); blink = true; delay(130); blink = false }
    }
    val description = when (mood) {
        CatMood.HAPPY -> "Neko, a smiling white lucky cat with a red collar, a gold bell and a rupee coin, waving"
        CatMood.THINKING -> "Neko, the lucky cat, is thinking"
        CatMood.TALKING -> "Neko, the lucky cat, is talking"
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val factor = minOf(size.width / NekoArt.WIDTH, size.height / NekoArt.HEIGHT)
        translate((size.width - NekoArt.WIDTH * factor) / 2f, (size.height - NekoArt.HEIGHT * factor) / 2f + bob * factor) {
            scale(factor, factor, pivot = Offset.Zero) {
                paint("shadow"); paint("ears"); paint("body"); paint("bib"); paint("collar"); paint("bell"); paint("coin"); paint("arm_left"); paint("head")
                if (mood == CatMood.HAPPY) paint("eyes_happy")
                else if (blink) scale(1f, 0.12f, pivot = Offset(200f, 198f)) { paint("eyes_open") }
                else paint("eyes_open")
                paint(if (if (moving) mouthOpen else mood != CatMood.THINKING) "mouth_open" else "mouth_closed")
                rotate(wave, pivot = Offset(270f, 338f)) { paint("paw_wave") }
            }
        }
    }
}
