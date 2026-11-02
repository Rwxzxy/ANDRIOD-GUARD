package io.github.rwxzxy.guard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.rwxzxy.guard.core.risk.RiskLevel

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E3A5F),
    secondary = Color(0xFF3D6A8A),
    tertiary = Color(0xFF6B5778),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    secondary = Color(0xFFB6CAE0),
    tertiary = Color(0xFFD7BEE4),
)

@Composable
fun GuardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 风险等级的固定配色（不随动态取色变化，保证语义一致）。 */
fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.NONE -> Color(0xFF607D8B)
    RiskLevel.LOW -> Color(0xFF2E7D32)
    RiskLevel.MEDIUM -> Color(0xFFF9A825)
    RiskLevel.HIGH -> Color(0xFFEF6C00)
    RiskLevel.CRITICAL -> Color(0xFFC62828)
}
