package com.clashassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра: тёмно-фиолетовый, чёрный, тёмно-синий, светло-фиолетовые акценты.
val Black = Color(0xFF07050D)
val DeepPurple = Color(0xFF140C2A)
val Purple = Color(0xFF231546)
val NightBlue = Color(0xFF101A3D)
val LightViolet = Color(0xFFC4A8FF)
val Violet = Color(0xFF8E6CFF)
val SoftBlue = Color(0xFF5C7CFF)
val Warning = Color(0xFFFFB86B)
val Cyan = Color(0xFF4FD1FF)
val ErrorRed = Color(0xFFFF6B8A)

private val Scheme = darkColorScheme(
    primary = LightViolet,
    onPrimary = Color(0xFF21104A),
    primaryContainer = Purple,
    onPrimaryContainer = Color(0xFFEDE4FF),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E1F5E),
    onSecondaryContainer = Color(0xFFE7DEFF),
    tertiary = SoftBlue,
    onTertiary = Color.White,
    tertiaryContainer = NightBlue,
    onTertiaryContainer = Color(0xFFDCE3FF),
    background = Black,
    onBackground = Color(0xFFE9E5F5),
    surface = DeepPurple,
    onSurface = Color(0xFFE9E5F5),
    surfaceVariant = Color(0xFF1C1538),
    onSurfaceVariant = Color(0xFFBDB4D9),
    surfaceContainer = Color(0xFF120B24),
    surfaceContainerHigh = Color(0xFF1A1233),
    outline = Color(0xFF4A3F75),
    error = ErrorRed,
)

@Composable
fun ClashAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
