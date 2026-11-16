package dev.mjwatch.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Lime=Color(0xFFC7FF68)
private val Ink=Color(0xFF0C0E15)
private val DarkSurface=Color(0xFF191E29)
private val DarkHigh=Color(0xFF242B39)
private val Paper=Color(0xFFFAFAF7)
private val LightSurface=Color(0xFFEAEDE6)
private val LightHigh=Color(0xFFE1E5DC)
private val Green=Color(0xFF345A05)
private val Dark=darkColorScheme(
    primary=Lime,onPrimary=Ink,primaryContainer=Color(0xFF303D28),onPrimaryContainer=Lime,
    secondary=Lime,onSecondary=Ink,secondaryContainer=DarkHigh,onSecondaryContainer=Color(0xFFF0F2EC),
    tertiary=Color(0xFF8BD9FF),onTertiary=Ink,tertiaryContainer=Color(0xFF1A3545),onTertiaryContainer=Color(0xFF8BD9FF),
    background=Ink,surface=DarkSurface,surfaceVariant=DarkHigh,surfaceTint=Lime,
    surfaceContainer=DarkSurface,surfaceContainerLow=DarkSurface,surfaceContainerHigh=DarkHigh,surfaceContainerHighest=DarkHigh,surfaceContainerLowest=Ink,
    onSurface=Color(0xFFF0F2EC),onBackground=Color(0xFFF0F2EC),onSurfaceVariant=Color(0xFFABB5C5),outline=Color(0xFF68778D),
    error=Color(0xFFFFB4AB),onError=Color(0xFF690005),errorContainer=Color(0xFF93000A),onErrorContainer=Color(0xFFFFDAD6))
private val Light=lightColorScheme(
    primary=Green,onPrimary=Color.White,primaryContainer=Color(0xFFDCE9CB),onPrimaryContainer=Green,
    secondary=Green,onSecondary=Color.White,secondaryContainer=LightHigh,onSecondaryContainer=Color(0xFF20251F),
    tertiary=Color(0xFF176084),onTertiary=Color.White,tertiaryContainer=Color(0xFFD7EEFA),onTertiaryContainer=Color(0xFF176084),
    background=Paper,surface=LightSurface,surfaceVariant=LightHigh,surfaceTint=Green,
    surfaceContainer=LightSurface,surfaceContainerLow=LightSurface,surfaceContainerHigh=LightHigh,surfaceContainerHighest=LightHigh,surfaceContainerLowest=Paper,
    onSurface=Color(0xFF20251F),onBackground=Color(0xFF20251F),onSurfaceVariant=Color(0xFF4D584E),outline=Color(0xFF687267),
    error=Color(0xFFA32323),onError=Color.White,errorContainer=Color(0xFFFFDAD6),onErrorContainer=Color(0xFF410002))
private val WatchShapes=Shapes(extraSmall=RoundedCornerShape(12.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(28.dp))
private val WatchTypography=Typography(
    headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=34.sp,lineHeight=40.sp),
    headlineMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=28.sp,lineHeight=36.sp),
    titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=22.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=18.sp,lineHeight=24.sp),
    bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=24.sp),
    bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=20.sp),
    bodySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=16.sp),
    labelLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=20.sp))
@Composable internal fun featureSurfaceGradient():Brush=Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh,MaterialTheme.colorScheme.surface))
@Composable fun WatchTheme(theme:String,content:@Composable ()->Unit) { MaterialTheme(colorScheme=if(theme=="dark" || theme=="system"&&isSystemInDarkTheme()) Dark else Light,typography=WatchTypography,shapes=WatchShapes,content=content) }
