package com.kuva.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---- Brand palette (extracted from the uploaded heraldic logo) ----
val ForestGreen = Color(0xFF123B2A)
val ForestGreenDeep = Color(0xFF0A261A)
val ForestGreenSoft = Color(0xFF1C5038)
val Gold = Color(0xFFD4AF37)
val GoldLight = Color(0xFFF0D278)
val GoldDark = Color(0xFF8C6919)
val Cream = Color(0xFFF2EAD3)
val Charcoal = Color(0xFF141414)
val CharcoalCard = Color(0xFF1E1E1E)
val StatusGreen = Color(0xFF39FF14)
val StatusOrange = Color(0xFFFFC800)  // connecting state: original matte yellow
val StatusRed = Color(0xFFFF1744)
val StatusGray = Color(0xFF9E9E9E)   // idle/disconnected state color

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = ForestGreenDeep,
    primaryContainer = GoldDark,
    onPrimaryContainer = Cream,
    secondary = ForestGreenSoft,
    onSecondary = Cream,
    background = Charcoal,
    onBackground = Color(0xFFEDEDED),
    surface = CharcoalCard,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFB8B8B8),
    error = StatusRed,
)

private val KuvaTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun KUVAVpnTheme(content: @Composable () -> Unit) {
    // dark-only brand: light palette removed by design
    MaterialTheme(
        colorScheme = DarkColors,
        typography = KuvaTypography,
        content = content,
    )
}
