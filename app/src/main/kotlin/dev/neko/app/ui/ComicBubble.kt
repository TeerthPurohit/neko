package dev.neko.app.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val BubbleInk = Color(0xFF2B2B33)
private val BubblePaper = Color(0xFFFFFDF6)

/** A rounded speech box with a tail along the bottom edge; [tailX] is the tail's position from 0 (left) to 1 (right). */
class BubbleShape(private val corner: Dp = 22.dp, private val tail: Dp = 16.dp, private val tailX: Float = 0.62f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { corner.toPx() }
        val tailPx = with(density) { tail.toPx() }
        val bodyHeight = (size.height - tailPx).coerceAtLeast(radius * 2)
        val box = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, bodyHeight, CornerRadius(radius))) }
        val cx = (size.width * tailX).coerceIn(radius + tailPx, size.width - radius - tailPx)
        val spike = Path().apply {
            moveTo(cx - tailPx * 0.9f, bodyHeight - 2f); lineTo(cx + tailPx * 0.25f, bodyHeight + tailPx); lineTo(cx + tailPx * 0.9f, bodyHeight - 2f); close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, box, spike))
    }
}

/**
 * Neko's comic-book dialogue box. Text types in unless motion is reduced; tapping finishes it instantly.
 * [tailX] points the tail at the cat below.
 */
@Composable fun ComicBubble(text: String, modifier: Modifier = Modifier, tailX: Float = 0.62f, animate: Boolean = true, content: @Composable ColumnScope.() -> Unit = {}) {
    val typing = animate && ValueAnimator.areAnimatorsEnabled()
    var shown by remember(text) { mutableIntStateOf(if (typing) 0 else text.length) }
    LaunchedEffect(text, typing) { while (typing && shown < text.length) { delay(18); shown = minOf(text.length, shown + 2) } }
    val shape = BubbleShape(tailX = tailX)
    Box(modifier.semantics { contentDescription = "Neko says: $text" }) {
        Box(Modifier.matchParentSize().padding(start = 4.dp, top = 5.dp).drawBehind {
            drawOutline(shape.createOutline(size, layoutDirection, this), BubbleInk.copy(alpha = 0.28f))
        })
        Column(
            Modifier.fillMaxWidth().background(BubblePaper, shape).border(3.dp, BubbleInk, shape)
                .clickable(enabled = shown < text.length) { shown = text.length }
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text.take(shown), color = BubbleInk, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (shown >= text.length) content()
        }
    }
}
