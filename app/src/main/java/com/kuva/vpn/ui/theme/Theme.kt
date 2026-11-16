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
// light neon purple accent — the app-wide accent color (user request:
// dialogs and interactive elements use this instead of the old gold)
val NeonPurple = Color(0xFFB026FF)
val NeonPurpleLight = Color(0xFFE0B0FF)
val NeonPurpleDeep = Color(0xFF3B1E63)

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

// ---- Shared accents (theme tokens: screens must import these instead of
// hard-coding hex values, so adding new themes later only touches Theme.kt) ----
val AccentBlue = Color(0xFF00E5FF)       // engine switch + usage bar blue (was 0xFF33CCFF on dashboard)
val PurpleGradientEnd = Color(0xFF7C4DFF) // second stop of every purple gradient
val VlessBlue = Color(0xFF2979FF)        // VLESS protocol badge
val TrojanOrange = Color(0xFFFF6D3B)     // TROJAN protocol badge
val TrojanOrangeLight = Color(0xFFFFAB40)
val SsGreen = Color(0xFF2ECC71)          // SHADOWSOCKS protocol badge
val WarnYellow = Color(0xFFFFEB3B)       // update-failed / warning gradient start
val WarnAmber = Color(0xFFFFC107)        // update-failed / warning gradient end
val DangerLight = Color(0xFFFF5252)      // destructive gradient end / hover red
val OveruseYellow = Color(0xFFFFEA00)    // usage bar >90% start
val OveruseAmber = Color(0xFFFFC400)     // usage bar >90% end
val SuccessGreen = Color(0xFF00E676)     // snackbar "ok" gradient start
val SuccessGreenDeep = Color(0xFF00C853) // snackbar "ok" gradient end
val NeonPurpleSoft = Color(0xFFD08FFF)   // card titles / M3 primary

/**
 * One ping color scale for EVERY screen (vault rows, search rows, MyConfigs
 * rows via the shared ConfigRow): untested gray, dead red,
 * green <=200 ms, yellow 200-450 ms, red >450 ms.
 */
fun pingColor(pingMs: Int?): Color = when {
    pingMs == null -> StatusGray          // never tested
    pingMs < 0 -> StatusRed               // dead / timed out
    pingMs <= 200 -> StatusGreen          // fast
    pingMs <= 450 -> StatusOrange         // usable
    else -> StatusRed                     // slow
}

private val DarkColors = darkColorScheme(
    primary = NeonPurpleSoft,
    onPrimary = Color(0xFF1A0526),   // dark text on the light-purple fill
    primaryContainer = NeonPurpleDeep,
    onPrimaryContainer = NeonPurpleLight,
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
