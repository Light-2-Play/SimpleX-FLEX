package chat.simplex.common.views.helpers

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import chat.simplex.common.platform.TemporaryContentManager
import chat.simplex.common.views.usersettings.TemporaryContentStoryViewer

@Composable
fun StoryAvatarContainer(
  contactId: Long?,
  avatarSize: Dp,
  modifier: Modifier = Modifier,
  avatarContent: @Composable () -> Unit
) {
  val allItems by TemporaryContentManager.items.collectAsState()
  var showViewer by remember { mutableStateOf(false) }

  val activeStories = remember(allItems, contactId) {
    TemporaryContentManager.getActiveItemsFor(contactId)
  }
  val hasActiveStories = activeStories.isNotEmpty()

  val primaryColor = MaterialTheme.colors.primary

  // Анимация пульсации света контура
  val infiniteTransition = rememberInfiniteTransition()
  val glowAlpha by infiniteTransition.animateFloat(
    initialValue = 0.65f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    )
  )

  val borderBrush = remember(primaryColor, glowAlpha) {
    val accentOrange = Color(0xFFFF6D00)
    Brush.sweepGradient(
      listOf(
        primaryColor.copy(alpha = glowAlpha),
        lerp(primaryColor, accentOrange, 0.55f).copy(alpha = glowAlpha),
        accentOrange.copy(alpha = glowAlpha),
        primaryColor.copy(alpha = glowAlpha)
      )
    )
  }

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.size(avatarSize + if (hasActiveStories) 10.dp else 0.dp)
  ) {
    if (hasActiveStories) {
      // 1. Внешний светящийся контур с зазором
      Box(
        modifier = Modifier
          .size(avatarSize + 8.dp)
          .clip(CircleShape)
          .border(2.5.dp, borderBrush, CircleShape)
          .padding(3.dp)
          .clickable {
            RichHaptics.perform(HapticPattern.CLICK)
            showViewer = true
          },
        contentAlignment = Alignment.Center
      ) {
        avatarContent()
      }
    } else {
      // 2. Обычная аватарка без контура
      avatarContent()
    }
  }

  // Полноэкранный плеер историй этого контакта
  if (showViewer && activeStories.isNotEmpty()) {
    TemporaryContentStoryViewer(
      items = activeStories,
      initialIndex = 0,
      onDismiss = { showViewer = false }
    )
  }
}
