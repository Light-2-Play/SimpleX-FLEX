package chat.simplex.common.views.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.painterResource
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import chat.simplex.res.MR
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

enum class RecordMediaMode {
  VOICE, VIDEO
}

@Composable
fun ExpressiveRecordMediaButton(
  mode: RecordMediaMode,
  onModeToggle: (RecordMediaMode) -> Unit,
  isRecording: Boolean,
  isLocked: Boolean,
  onStartRecording: (RecordMediaMode) -> Unit,
  onLockRecording: () -> Unit,
  onStopAndSend: () -> Unit,
  onCancelRecording: () -> Unit,
  modifier: Modifier = Modifier
) {
  val density = LocalDensity.current
  val lockThresholdPx = with(density) { 68.dp.toPx() }
  val cancelThresholdPx = with(density) { 80.dp.toPx() }

  var dragOffsetY by remember { mutableStateOf(0f) }
  var dragOffsetX by remember { mutableStateOf(0f) }

  // Прогресс подтягивания к замочку (от 0f до 1f)
  val lockProgress = (-dragOffsetY / lockThresholdPx).coerceIn(0f, 1f)

  // Плавная пульсация кнопки во время активной записи
  val buttonScale by animateFloatAsState(
    targetValue = if (isRecording && !isLocked) 1.22f else 1f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
  )

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center
  ) {
    // =================================================================
    // 1. ВСПЛЫВАЮЩИЙ ЗАМОЧЕК СВЕРХУ (ПРИ УДЕРЖАНИИ)
    // =================================================================
    AnimatedVisibility(
      visible = isRecording && !isLocked,
      enter = fadeIn(tween(150)) + slideInVertically(initialOffsetY = { it / 2 }),
      exit = fadeOut(tween(150)) + scaleOut()
    ) {
      val lockOffsetPx = with(density) { (-76).dp.toPx() } + (dragOffsetY * 0.35f)
      val lockScale = 1f + (lockProgress * 0.25f)

      Surface(
        modifier = Modifier
          .offset { IntOffset(0, lockOffsetPx.roundToInt()) }
          .scale(lockScale)
          .width(38.dp)
          .height(54.dp)
          .border(
            width = 1.dp,
            color = MaterialTheme.colors.primary.copy(alpha = 0.25f + lockProgress * 0.5f),
            shape = RoundedCornerShape(percent = 50)
          ),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colors.surface,
        elevation = 4.dp
      ) {
        Column(
          modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Icon(
            painter = painterResource(
              if (lockProgress > 0.85f) MR.images.ic_lock_filled else MR.images.ic_lock
            ),
            contentDescription = "Lock recording",
            tint = if (lockProgress > 0.85f) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.size(18.dp)
          )
          Icon(
            painter = painterResource(MR.images.ic_arrow_upward),
            contentDescription = null,
            tint = MaterialTheme.colors.primary.copy(alpha = (1f - lockProgress).coerceIn(0.2f, 0.8f)),
            modifier = Modifier.size(14.dp)
          )
        }
      }
    }

    // =================================================================
    // 2. ОСНОВНАЯ КНОПКА (МИКРОФОН / КАМЕРА / ОТПРАВКА ПРИ LOCK)
    // =================================================================
    val activeBgColor = when {
      isLocked -> MaterialTheme.colors.primary
      isRecording -> MaterialTheme.colors.primary.copy(alpha = 0.18f)
      else -> Color.Transparent
    }

    val activeIconTint = when {
      isLocked -> MaterialTheme.colors.onPrimary
      else -> MaterialTheme.colors.primary
    }

    Box(
      modifier = Modifier
        .size(44.dp)
        .scale(buttonScale)
        .clip(CircleShape)
        .background(activeBgColor)
        .pointerInput(mode, isLocked) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)

            // Если запись уже заблокирована замочком — одиночный тап отправляет запись
            if (isLocked) {
              do {
                val event = awaitPointerEvent()
              } while (event.changes.any { it.pressed })
              RichHaptics.perform(HapticPattern.SEND_MESSAGE)
              onStopAndSend()
              return@awaitEachGesture
            }

            var longPressTriggered = false
            dragOffsetY = 0f
            dragOffsetX = 0f

            // Ждем 220 мс: если отпустили раньше — это переключение Микрофон <-> Камера
            try {
              withTimeout(220L) {
                do {
                  val event = awaitPointerEvent()
                  val change = event.changes.firstOrNull()
                  if (change == null || !change.pressed) {
                    // Короткий тап: переключаем режим
                    RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
                    val nextMode = if (mode == RecordMediaMode.VOICE) RecordMediaMode.VIDEO else RecordMediaMode.VOICE
                    onModeToggle(nextMode)
                    return@withTimeout
                  }
                } while (true)
              }
            } catch (_: TimeoutCancellationException) {
              // Палец удерживается дольше 220 мс — запускаем запись!
              longPressTriggered = true
              RichHaptics.perform(HapticPattern.RECORD_START)
              onStartRecording(mode)
            }

            // Отслеживаем движение пальца во время удержания
            if (longPressTriggered) {
              var lockedDuringDrag = false
              var cancelledDuringDrag = false

              do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break

                if (change.pressed) {
                  val delta = change.positionChange()
                  dragOffsetY = (dragOffsetY + delta.y).coerceAtMost(0f)
                  dragOffsetX = (dragOffsetX + delta.x).coerceAtMost(0f)
                  change.consume()

                  // 1. Проверка свайпа вверх к замочку
                  if (dragOffsetY <= -lockThresholdPx && !lockedDuringDrag) {
                    lockedDuringDrag = true
                    RichHaptics.perform(HapticPattern.TOGGLE_ON)
                    onLockRecording()
                    break
                  }

                  // 2. Проверка свайпа влево для отмены
                  if (dragOffsetX <= -cancelThresholdPx && !cancelledDuringDrag) {
                    cancelledDuringDrag = true
                    RichHaptics.perform(HapticPattern.TOGGLE_OFF)
                    onCancelRecording()
                    break
                  }
                }
              } while (event.changes.any { it.pressed })

              // Если палец отпустили без фиксации замочка и без отмены — завершаем и отправляем
              if (!lockedDuringDrag && !cancelledDuringDrag) {
                RichHaptics.perform(HapticPattern.RECORD_STOP)
                onStopAndSend()
              }

              dragOffsetY = 0f
              dragOffsetX = 0f
            }
          }
        },
      contentAlignment = Alignment.Center
    ) {
      // Плавная смена иконки Микрофон <-> Видеокамера <-> Отправить
      val iconRes = when {
        isLocked -> MR.images.ic_send_filled
        mode == RecordMediaMode.VOICE -> MR.images.ic_voice_msg
        else -> MR.images.ic_videocam
      }

      Crossfade(targetState = iconRes, animationSpec = tween(160)) { targetIcon ->
        Icon(
          painter = painterResource(targetIcon),
          contentDescription = null,
          tint = activeIconTint,
          modifier = Modifier.size(24.dp)
        )
      }
    }
  }
}
