package dev.neko.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.semantics.*
import android.animation.ValueAnimator

/** Original native vector character inspired by the supplied lucky-cat reference. */
@Composable fun NekoCat(modifier:Modifier=Modifier,animate:Boolean=true) {
    val movement=if(animate&&ValueAnimator.areAnimatorsEnabled()) {
        val transition=rememberInfiniteTransition(label="Neko greeting")
        transition.animateFloat(-3f,3f,infiniteRepeatable(tween(1800,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="Paw wave")
    } else remember { mutableFloatStateOf(0f) }
    Canvas(modifier.semantics { contentDescription="Neko, a smiling lucky cat with round glasses and a golden bell" }) {
        val outline=Color(0xFF243138);val cream=Color(0xFFFFF5E5);val orange=Color(0xFFF7AD55);val pink=Color(0xFFF1959D);val mint=Color(0xFF76DCD3)
        scale(size.width/200f,size.height/220f,pivot=Offset.Zero) {
            fun outlinedOval(color:Color,x:Float,y:Float,w:Float,h:Float) { drawOval(color,Offset(x,y),Size(w,h));drawOval(outline,Offset(x,y),Size(w,h),style=Stroke(4f)) }
            fun outlined(path:Path,color:Color) {drawPath(path,color);drawPath(path,outline,style=Stroke(4f,cap=StrokeCap.Round,join=StrokeJoin.Round))}
            drawOval(mint.copy(alpha=0.17f),Offset(32f,199f),Size(142f,14f))
            outlinedOval(orange,135f,147f,43f,27f)
            outlinedOval(cream,49f,110f,105f,95f)
            outlinedOval(cream,43f,186f,42f,21f);outlinedOval(cream,116f,186f,43f,21f)
            outlined(Path().apply {moveTo(40f,72f);lineTo(43f,15f);quadraticBezierTo(45f,5f,58f,14f);lineTo(82f,34f);quadraticBezierTo(105f,26f,128f,34f);lineTo(153f,14f);quadraticBezierTo(163f,7f,165f,21f);lineTo(167f,73f);cubicTo(195f,130f,164f,141f,104f,141f);cubicTo(42f,141f,14f,118f,40f,72f);close()},cream)
            drawPath(Path().apply {moveTo(49f,26f);lineTo(52f,55f);lineTo(72f,39f);close()},pink)
            drawPath(Path().apply {moveTo(155f,25f);lineTo(155f,55f);lineTo(138f,40f);close()},pink)
            drawPath(Path().apply {moveTo(83f,34f);quadraticBezierTo(105f,25f,124f,34f);quadraticBezierTo(108f,60f,91f,45f);close()},orange)
            drawOval(pink.copy(alpha=0.65f),Offset(42f,91f),Size(22f,13f));drawOval(pink.copy(alpha=0.65f),Offset(143f,91f),Size(22f,13f))
            // The glasses remain legible at compact avatar sizes.
            drawCircle(outline,21f,Offset(78f,80f),style=Stroke(4f));drawCircle(outline,21f,Offset(131f,80f),style=Stroke(4f))
            drawLine(outline,Offset(99f,78f),Offset(110f,78f),4f,StrokeCap.Round)
            drawLine(outline,Offset(42f,76f),Offset(57f,78f),4f);drawLine(outline,Offset(152f,78f),Offset(169f,76f),4f)
            drawArc(outline,200f,140f,false,Offset(69f,77f),Size(17f,12f),style=Stroke(3f,cap=StrokeCap.Round))
            drawArc(outline,200f,140f,false,Offset(122f,77f),Size(17f,12f),style=Stroke(3f,cap=StrokeCap.Round))
            drawPath(Path().apply {moveTo(99f,97f);quadraticBezierTo(105f,92f,111f,97f);lineTo(105f,103f);close()},outline)
            drawArc(outline,0f,165f,false,Offset(89f,98f),Size(17f,16f),style=Stroke(3f,cap=StrokeCap.Round));drawArc(outline,15f,165f,false,Offset(105f,98f),Size(17f,16f),style=Stroke(3f,cap=StrokeCap.Round))
            drawLine(outline,Offset(49f,104f),Offset(33f,100f),2.5f);drawLine(outline,Offset(155f,105f),Offset(172f,101f),2.5f)
            drawArc(Color(0xFFE67B78),8f,165f,false,Offset(52f,115f),Size(102f,32f),style=Stroke(8f,cap=StrokeCap.Round))
            drawPath(Path().apply {moveTo(72f,145f);lineTo(138f,145f);quadraticBezierTo(127f,184f,104f,186f);quadraticBezierTo(78f,177f,72f,145f);close()},mint)
            outlinedOval(Color(0xFFFFCF58),92f,141f,25f,25f);drawLine(outline,Offset(94f,153f),Offset(115f,153f),2f);drawCircle(outline,2.5f,Offset(105f,159f))
            outlinedOval(orange,58f,155f,25f,30f)
            rotate(movement.value,pivot=Offset(155f,157f)) {
                outlined(Path().apply {moveTo(138f,162f);cubicTo(152f,151f,161f,136f,160f,112f);quadraticBezierTo(158f,97f,171f,97f);quadraticBezierTo(193f,100f,185f,137f);quadraticBezierTo(178f,166f,154f,181f);close()},cream)
                outlinedOval(cream,155f,87f,34f,30f)
                drawLine(outline,Offset(165f,101f),Offset(166f,111f),2f);drawLine(outline,Offset(175f,100f),Offset(176f,112f),2f)
            }
        }
    }
}
