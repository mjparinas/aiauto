package dev.aiauto.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A warm, paper-and-clay palette: calm enough to glance at, with one accent for actions.
val Clay = Color(0xFFC96F4A)
private val ClayDark = Color(0xFFDB8B66)

private val Light = lightColorScheme(
    primary = Clay,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3DED3),
    onPrimaryContainer = Color(0xFF5A2A16),
    background = Color(0xFFF6F2EA),
    onBackground = Color(0xFF2A2825),
    surface = Color(0xFFFCFAF6),
    onSurface = Color(0xFF2A2825),
    surfaceVariant = Color(0xFFEDE7DC),
    onSurfaceVariant = Color(0xFF6E6960),
    outline = Color(0xFFD9D2C5),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = ClayDark,
    onPrimary = Color(0xFF2A1308),
    primaryContainer = Color(0xFF4A2A1C),
    onPrimaryContainer = Color(0xFFF6DCCF),
    background = Color(0xFF1E1D1B),
    onBackground = Color(0xFFECE8E0),
    surface = Color(0xFF282724),
    onSurface = Color(0xFFECE8E0),
    surfaceVariant = Color(0xFF34322E),
    onSurfaceVariant = Color(0xFFADA79C),
    outline = Color(0xFF45423C),
    error = Color(0xFFF2B8B5),
)

private val Serif = FontFamily.Serif

private val AppType = Typography(
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AiAutoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppType,
        shapes = AppShapes,
        content = content,
    )
}
