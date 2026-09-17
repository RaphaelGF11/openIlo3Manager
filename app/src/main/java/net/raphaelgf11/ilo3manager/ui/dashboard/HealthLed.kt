package net.raphaelgf11.ilo3manager.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.raphaelgf11.ilo3manager.ilo.HealthLevel

@Composable
fun HealthLed(health: HealthLevel, modifier: Modifier = Modifier) {
    val color = when (health) {
        HealthLevel.OK -> Color(0xFF3DDC84)
        HealthLevel.DEGRADED -> Color(0xFFFFA000)
        HealthLevel.CRITICAL -> Color(0xFFE53935)
        HealthLevel.UNKNOWN -> Color(0xFF9E9E9E)
    }

    val alphaValue: Float = if (health == HealthLevel.CRITICAL) {
        val transition = rememberInfiniteTransition(label = "critical-led-blink")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "critical-led-alpha",
        )
        animated
    } else {
        1f
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(18.dp)
            .alpha(alphaValue)
            .background(color = color, shape = CircleShape),
    )
}
