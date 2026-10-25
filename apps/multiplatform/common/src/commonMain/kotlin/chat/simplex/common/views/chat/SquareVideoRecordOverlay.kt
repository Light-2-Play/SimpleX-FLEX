package chat.simplex.common.views.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import kotlinx.coroutines.delay

private const val MAX_VIDEO_DURATION_SECONDS = 120 // Ограничение ровно 02:00

@Composable
fun SquareVideoRecordOverlay(
  isRecording: Boolean,
  onMaxDurationReached: () -> Unit,
  cameraPreview: @Composable () -> Unit
) {
  var secondsElapsed by remember { mutableStateOf(0) }

  // Таймер записи от 00:00 до 02:00
  LaunchedEffect(isRecording) {
    if (isRecording) {
      secondsElapsed = 0
      while (secondsElapsed < MAX_VIDEO_DURATION_SECONDS) {
        delay(1000L)
        secondsElapsed++
      }
      // Достигнут лимит 02:00 — автоматический стоп и отправка
      RichHaptics.perform(HapticPattern.RECORD_STOP)
      onMaxDurationReached()
    } else {
      secondsElapsed = 0
    }
  }

  // Анимация пульсации красной точки записи
  val infiniteTransition = rememberInfiniteTransition()
  val dotAlpha by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0.25f,
    animationSpec = infiniteRepeatable(
      animation = tween(650, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    )
  )

  AnimatedVisibility(
    visible = isRecording,
    enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.88f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
    exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.88f)
  ) {
    // Non-focusable Popup не перехватывает жесты пальца у кнопки внизу
    Popup(
      alignment = Alignment.Center,
      properties = PopupProperties(
        focusable = false,
        dismissOnBackPress = false,
        dismissOnClickOutside = false
      )
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        // Квадратный контейнер 1:1 в стиле Material 3 Expressive
        Surface(
          modifier = Modifier
            .size(280.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .border(
              width = 2.dp,
              color = MaterialTheme.colors.primary.copy(alpha = 0.65f),
              shape = RoundedCornerShape(28.dp)
            ),
          shape = RoundedCornerShape(28.dp),
          color = Color.Black,
          elevation = 12.dp
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            // 1. Область превью CameraX
            cameraPreview()

            // 2. Таймер в верхнем левом углу внутри видоискателя
            Row(
              modifier = Modifier
                .padding(14.dp)
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              // Пульсирующий красный индикатор
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(Color(0xFFE53935).copy(alpha = dotAlpha))
              )

              // Таймер с лимитом 02:00
              val minutes = secondsElapsed / 60
              val seconds = secondsElapsed % 60
              val formattedTime = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

              Text(
                text = "$formattedTime / 02:00",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Color.White
              )
            }
          }
        }
      }
    }
  }
}
