package chat.simplex.common.views.chat.item

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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

    // Берем цвета темы Material (в вашем форке они автоматически берут системные цвета Monet)
    val chipBg = if (isBoosted) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
    }

    val chipTextColor = if (isBoosted) {
        MaterialTheme.colors.onPrimary
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = 0.75f)
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(chipBg, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                val nextIndex = (speeds.indexOf(currentSpeed) + 1) % speeds.size
                onSpeedChange(speeds[nextIndex])
            }
            .padding(horizontal = 7.dp, vertical = 2.dp),
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
                    fontSize = 11.sp,
                    fontWeight = if (isBoosted) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
