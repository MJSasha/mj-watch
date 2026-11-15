package dev.mjwatch.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Lime=Color(0xFFC7FF68)
private val Dark=darkColorScheme(primary=Lime,onPrimary=Color(0xFF0C0E15),background=Color(0xFF0C0E15),surface=Color(0xFF191E29),surfaceVariant=Color(0xFF242B39),onSurface=Color(0xFFF0F2EC),onBackground=Color(0xFFF0F2EC),onSurfaceVariant=Color(0xFFABB5C5),outline=Color(0xFF68778D))
private val Light=lightColorScheme(primary=Color(0xFF345A05),onPrimary=Color.White,background=Color(0xFFFAFAF7),surface=Color(0xFFEAEDE6),surfaceVariant=Color(0xFFE1E5DC),onSurface=Color(0xFF20251F),onBackground=Color(0xFF20251F),onSurfaceVariant=Color(0xFF4D584E),outline=Color(0xFF687267))
@Composable fun WatchTheme(theme:String,content:@Composable ()->Unit) { MaterialTheme(colorScheme=if(theme=="dark" || theme=="system"&&isSystemInDarkTheme()) Dark else Light,content=content) }
