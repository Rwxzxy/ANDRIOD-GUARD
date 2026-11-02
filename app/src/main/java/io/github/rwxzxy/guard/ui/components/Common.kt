@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.rwxzxy.guard.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.rwxzxy.guard.core.risk.RiskLevel
import io.github.rwxzxy.guard.ui.theme.riskColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(300)

    suspend fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(sizePx, sizePx).asImageBitmap().also { cache.put(packageName, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val px = (size.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = IconCache.load(context, packageName, px)
    }
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(size * 0.7f))
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (filled) color else color.copy(alpha = 0.14f),
        contentColor = if (filled) Color.White else color,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun RiskBadge(level: RiskLevel, score: Int, trusted: Boolean) {
    if (trusted) {
        Pill("已信任", MaterialTheme.colorScheme.primary)
    } else {
        Pill(if (level == RiskLevel.NONE) level.label else "${level.label} · $score", riskColor(level), filled = level >= RiskLevel.HIGH)
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (containerColor != null) CardDefaults.cardColors(containerColor = containerColor) else CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val DATE_TIME_SEC: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatDateTime(millis: Long): String = if (millis <= 0) "未知" else DATE_TIME.format(Instant.ofEpochMilli(millis))

fun formatDateTimeSeconds(millis: Long): String = DATE_TIME_SEC.format(Instant.ofEpochMilli(millis))

/** “3 分钟前” 这样的相对时间。 */
fun formatRelative(millis: Long?, now: Long = System.currentTimeMillis()): String {
    if (millis == null) return "无记录"
    val diff = now - millis
    if (diff < 0) return "刚刚"
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        minutes < 30L * 24 * 60 -> "${minutes / (24 * 60)} 天前"
        else -> formatDateTime(millis)
    }
}
