package dev.mjwatch.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val WatchMint=Color(0xFF31F5C8)
private val Ink=Color(0xFF161622)
private val DarkSurface=Color(0xFF222232)
private val DarkHigh=Color(0xFF2B2C40)
private val Paper=Color(0xFFF7F9F8)
private val LightSurface=Color(0xFFECF0EF)
private val LightHigh=Color(0xFFE0E8E5)
private val Green=Color(0xFF00674F)
private val Dark=darkColorScheme(
    primary=WatchMint,onPrimary=Ink,primaryContainer=Color(0xFF06543F),onPrimaryContainer=WatchMint,
    secondary=WatchMint,onSecondary=Ink,secondaryContainer=DarkHigh,onSecondaryContainer=Color(0xFFF4F4FA),
    tertiary=Color(0xFF73E6F2),onTertiary=Ink,tertiaryContainer=Color(0xFF19404A),onTertiaryContainer=Color(0xFF73E6F2),
    background=Ink,surface=DarkSurface,surfaceVariant=DarkHigh,surfaceTint=WatchMint,
    surfaceContainer=DarkSurface,surfaceContainerLow=DarkSurface,surfaceContainerHigh=DarkHigh,surfaceContainerHighest=DarkHigh,surfaceContainerLowest=Ink,
    onSurface=Color(0xFFF4F4FA),onBackground=Color(0xFFF4F4FA),onSurfaceVariant=Color(0xFFB6BACD),outline=Color(0xFF777A92),
    error=Color(0xFFFFB4AB),onError=Color(0xFF690005),errorContainer=Color(0xFF93000A),onErrorContainer=Color(0xFFFFDAD6))
private val Light=lightColorScheme(
    primary=Green,onPrimary=Color.White,primaryContainer=Color(0xFFC8F5E5),onPrimaryContainer=Green,
    secondary=Green,onSecondary=Color.White,secondaryContainer=LightHigh,onSecondaryContainer=Color(0xFF172420),
    tertiary=Color(0xFF006273),onTertiary=Color.White,tertiaryContainer=Color(0xFFD1F2F6),onTertiaryContainer=Color(0xFF006273),
    background=Paper,surface=LightSurface,surfaceVariant=LightHigh,surfaceTint=Green,
    surfaceContainer=LightSurface,surfaceContainerLow=LightSurface,surfaceContainerHigh=LightHigh,surfaceContainerHighest=LightHigh,surfaceContainerLowest=Paper,
    onSurface=Color(0xFF172420),onBackground=Color(0xFF172420),onSurfaceVariant=Color(0xFF455650),outline=Color(0xFF64776F),
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
@Composable internal fun watchSelectionGradient():Brush {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return Brush.horizontalGradient(if(dark) listOf(Color(0xFF075E45),Color(0xFF125762))
        else listOf(Color(0xFFC3F2DE),Color(0xFFBEEDF0)))
}

@Composable internal fun watchProgressGradient():Brush {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return Brush.horizontalGradient(if(dark) listOf(Color(0xFF3CF5A6),Color(0xFF21DED9))
        else listOf(Color(0xFF00805E),Color(0xFF00788A)))
}

@Composable fun WatchTheme(theme:String,content:@Composable ()->Unit) { MaterialTheme(colorScheme=if(theme=="dark" || theme=="system"&&isSystemInDarkTheme()) Dark else Light,typography=WatchTypography,shapes=WatchShapes,content=content) }
