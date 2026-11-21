package chat.simplex.common.views.chat.item

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VoiceSpeedChip(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val speeds = listOf(1.0f, 1.5f, 2.0f)
    val isBoosted = currentSpeed > 1.0f
    val isLight = MaterialTheme.colors.isLight

    // Непрозрачный тональный контейнер M3 Expressive для 1x и яркий акцент для 1.5x / 2x
    val chipBg = if (isBoosted) {
        MaterialTheme.colors.primary
    } else {
        lerp(
            MaterialTheme.colors.surface,
            MaterialTheme.colors.primary,
            if (isLight) 0.12f else 0.18f
        )
    }

    val chipBorderColor = if (isBoosted) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = if (isLight) 0.16f else 0.22f)
    }

    val chipTextColor = if (isBoosted) {
        MaterialTheme.colors.onPrimary
    } else {
        MaterialTheme.colors.onSurface
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(chipBg, CircleShape)
            .border(1.dp, chipBorderColor, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                val nextIndex = (speeds.indexOf(currentSpeed) + 1) % speeds.size
                onSpeedChange(speeds[nextIndex])
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = currentSpeed,
            transitionSpec = {
                (slideInVertically(spring()) { it } + fadeIn())
                    .togetherWith(slideOutVertically(spring()) { -it } + fadeOut())
            },
            label = "VoiceSpeedAnimation"
        ) { speed ->
            val label = if (speed % 1.0f == 0.0f) "${speed.toInt()}x" else "${speed}x"
            BasicText(
                text = label,
                style = TextStyle(
                    color = chipTextColor,
                    fontSize = 11.5.sp,
                    fontWeight = if (isBoosted) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
