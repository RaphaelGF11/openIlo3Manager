package net.raphaelgf11.ilo3manager.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.raphaelgf11.ilo3manager.ilo.HostIndicator

private val COLOURS = mapOf(
    HostIndicator.UNKNOWN to Color(0xFF9E9E9E),
    HostIndicator.CONNECTING to Color(0xFFFFEB3B),
    HostIndicator.POWERED_OFF to Color(0xFFFF9800),
    HostIndicator.FAULT to Color(0xFFE53935),
    HostIndicator.CRITICAL to Color(0xFFE53935),
    HostIndicator.POWERED_ON to Color(0xFF3DDC84),
    HostIndicator.UID to Color(0xFF2196F3),
)

/**
 * The status dot. A critical fault carries a cross on top of the red, so the two red states stay
 * distinguishable without relying on colour alone.
 */
@Composable
fun StatusDot(indicator: HostIndicator, size: Dp = 12.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color = COLOURS[indicator] ?: Color(0xFF9E9E9E), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (indicator == HostIndicator.CRITICAL) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.8f),
            )
        }
    }
}

fun labelFor(indicator: HostIndicator): String = when (indicator) {
    HostIndicator.UNKNOWN -> "État inconnu"
    HostIndicator.CONNECTING -> "Connexion en cours"
    HostIndicator.POWERED_OFF -> "Éteint"
    HostIndicator.FAULT -> "Défaut non critique"
    HostIndicator.CRITICAL -> "Défaut critique"
    HostIndicator.POWERED_ON -> "Allumé"
    HostIndicator.UID -> "LED UID allumée"
}
