package dev.neko.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object NekoTokens {
    val Page=24.dp;val Card=20.dp;val Gap=12.dp;val Touch=48.dp
    val CardShape=RoundedCornerShape(28.dp);val ControlShape=RoundedCornerShape(18.dp)
    val Mint=Color(0xFF74E1DD);val Ink=Color(0xFF102B30)
    val Dark=darkColorScheme(primary=Mint,onPrimary=Ink,primaryContainer=Color(0xFF173B3E),onPrimaryContainer=Color(0xFFA7EFEC),background=Color(0xFF101416),surface=Color(0xFF1B2225),onBackground=Color(0xFFF2F5F4),onSurface=Color(0xFFF2F5F4),onSurfaceVariant=Color(0xFFA7B5B9),outline=Color(0xFF455458),error=Color(0xFFFFB4AB))
    val Light=lightColorScheme(primary=Color(0xFF086B70),onPrimary=Color.White,primaryContainer=Color(0xFFC4F2ED),onPrimaryContainer=Ink,background=Color(0xFFF6F9F7),surface=Color.White,onBackground=Color(0xFF14262B),onSurface=Color(0xFF14262B),onSurfaceVariant=Color(0xFF52676B),outline=Color(0xFFB1C3C5),error=Color(0xFFA52E2E))
}
@Composable fun NekoTheme(mode:String="system",content:@Composable ()->Unit) {
    val dark=mode=="dark"||(mode=="system"&&isSystemInDarkTheme())
    MaterialTheme(colorScheme=if(dark)NekoTokens.Dark else NekoTokens.Light,typography=Typography(
        displaySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=38.sp,letterSpacing=(-1).sp),
        headlineLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=30.sp,letterSpacing=(-0.7).sp),
        headlineSmall=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=24.sp,letterSpacing=(-0.4).sp),
        titleLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=20.sp),
        titleMedium=TextStyle(fontWeight=FontWeight.Medium,fontSize=16.sp),
        bodyLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp),bodyMedium=TextStyle(fontSize=14.sp,lineHeight=21.sp),
        labelLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=14.sp),labelSmall=TextStyle(fontSize=11.sp,letterSpacing=0.6.sp),
    ),shapes=Shapes(medium=NekoTokens.ControlShape,large=NekoTokens.CardShape),content=content)
}
