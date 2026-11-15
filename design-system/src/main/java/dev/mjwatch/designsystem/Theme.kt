package dev.mjwatch.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    background=Ink,surface=DarkSurface,surfaceVariant=DarkHigh,surfaceTint=Lime,
    surfaceContainer=DarkSurface,surfaceContainerLow=DarkSurface,surfaceContainerHigh=DarkHigh,surfaceContainerHighest=DarkHigh,surfaceContainerLowest=Ink,
    onSurface=Color(0xFFF0F2EC),onBackground=Color(0xFFF0F2EC),onSurfaceVariant=Color(0xFFABB5C5),outline=Color(0xFF68778D))
private val Light=lightColorScheme(
    primary=Green,onPrimary=Color.White,primaryContainer=Color(0xFFDCE9CB),onPrimaryContainer=Green,
    secondary=Green,onSecondary=Color.White,secondaryContainer=LightHigh,onSecondaryContainer=Color(0xFF20251F),
    background=Paper,surface=LightSurface,surfaceVariant=LightHigh,surfaceTint=Green,
    surfaceContainer=LightSurface,surfaceContainerLow=LightSurface,surfaceContainerHigh=LightHigh,surfaceContainerHighest=LightHigh,surfaceContainerLowest=Paper,
    onSurface=Color(0xFF20251F),onBackground=Color(0xFF20251F),onSurfaceVariant=Color(0xFF4D584E),outline=Color(0xFF687267))
@Composable fun WatchTheme(theme:String,content:@Composable ()->Unit) { MaterialTheme(colorScheme=if(theme=="dark" || theme=="system"&&isSystemInDarkTheme()) Dark else Light,content=content) }
