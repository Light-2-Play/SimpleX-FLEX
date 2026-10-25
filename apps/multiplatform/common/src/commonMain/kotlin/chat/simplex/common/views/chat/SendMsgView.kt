package chat.simplex.common.views.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import chat.simplex.common.model.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.chat.item.ItemAction
import chat.simplex.common.views.helpers.*
import chat.simplex.common.model.ChatItem
import chat.simplex.common.platform.*
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.stringResource
import dev.icerock.moko.resources.compose.painterResource
import kotlinx.coroutines.*
import java.net.URI

enum class RecordMediaMode {
  VOICE, VIDEO
}

/**
 * Изолированный мост CameraX для квадратного видео (1:1).
 * Полностью независим от встроенного голосового RecorderNative.
 */
object SquareVideoBridge {
  var startRecording: (() -> Unit)? = null
  var stopRecording: (((URI) -> Unit) -> Unit)? = null
  var cancelRecording: (() -> Unit)? = null
}

val LocalSquareCameraPreview = compositionLocalOf<(@Composable (isRecording: Boolean, modifier: Modifier) -> Unit)?> { null }

// Spec: spec/client/compose.md#SendMsgView
@Composable
fun SendMsgView(
  composeState: MutableState<ComposeState>,
  showVoiceRecordIcon: Boolean,
  recState: MutableState<RecordingState>,
  isDirectChat: Boolean,
  liveMessageAlertShown: SharedPreference<Boolean>,
  sendMsgEnabled: Boolean,
  userCantSendReason: Pair<String, String?>?,
  sendButtonEnabled: Boolean,
  sendToConnect: (() -> Unit)? = null,
  hideSendButton: Boolean = false,
  nextConnect: Boolean,
  needToAllowVoiceToContact: Boolean,
  allowedVoiceByPrefs: Boolean,
  sendButtonColor: Color = MaterialTheme.colors.primary,
  allowVoiceToContact: () -> Unit,
  timedMessageAllowed: Boolean = false,
  showSign: Boolean = false,
  signMessageAlertShown: SharedPreference<Boolean> = SharedPreference(get = { false }, set = {}),
  customDisappearingMessageTimePref: SharedPreference<Int>? = null,
  placeholder: String,
  sendMessage: (Int?) -> Unit,
  sendSignedMessage: () -> Unit = {},
  sendLiveMessage: (suspend () -> Unit)? = null,
  updateLiveMessage: (suspend () -> Unit)? = null,
  cancelLiveMessage: (() -> Unit)? = null,
  editPrevMessage: () -> Unit,
  onFilesPasted: (List<URI>) -> Unit,
  onMessageChange: (ComposeMessage) -> Unit,
  textStyle: MutableState<TextStyle>,
  focusRequester: FocusRequester? = null,
) {
  val showCustomDisappearingMessageDialog = remember { mutableStateOf(false) }
  val padding = if (appPlatform.isAndroid) PaddingValues(vertical = 8.dp) else PaddingValues(top = 3.dp, bottom = 4.dp)

  // Общие состояния записи для синхронизации панели таймера и кнопки
  var isRecordingActive by remember { mutableStateOf(false) }
  var recordingSeconds by remember { mutableStateOf(0) }
  var isLocked by remember { mutableStateOf(false) }
  var dragOffsetX by remember { mutableStateOf(0f) }
  var onCancelAction by remember { mutableStateOf<(() -> Unit)?>(null) }

  Box(Modifier.padding(padding).fillMaxWidth()) {
    val cs = composeState.value
    val showVoiceButton = !nextConnect && cs.message.text.isEmpty() && showVoiceRecordIcon && !composeState.value.editing &&
        !composeState.value.forwarding && cs.liveMessage == null && (cs.preview is ComposePreview.NoPreview || recState.value is RecordingState.Started) && (cs.contextItem !is ComposeContextItem.ReportedItem)
    val showDeleteTextButton = rememberSaveable { mutableStateOf(false) }
    val sendMsgButtonDisabled = !sendMsgEnabled || !cs.sendEnabled() ||
      (!allowedVoiceByPrefs && cs.preview is ComposePreview.VoicePreview) ||
        cs.endLiveDisabled ||
        !sendButtonEnabled
    val clicksOnTextFieldDisabled = !sendMsgEnabled || cs.preview is ComposePreview.VoicePreview || cs.inProgress

    // 1. Обычное текстовое поле ввода
    PlatformTextField(
      composeState,
      sendMsgEnabled,
      disabledText = userCantSendReason?.first,
      sendMsgButtonDisabled,
      textStyle,
      showDeleteTextButton,
      if (clicksOnTextFieldDisabled || isRecordingActive) "" else placeholder,
      showVoiceButton,
      onMessageChange,
      editPrevMessage,
      onFilesPasted,
      focusRequester
    ) {
      if (!cs.inProgress) {
        if (sendToConnect != null) sendToConnect() else sendMessage(null)
      }
    }

    // 2. Панель записи (таймер + Slide to cancel): перекрывает поле ввода аккуратно поверх
    if (isRecordingActive) {
      val mins = (recordingSeconds / 60).toString().padStart(2, '0')
      val secs = (recordingSeconds % 60).toString().padStart(2, '0')
      val timerText = "$mins:$secs"

      val infiniteTransition = rememberInfiniteTransition()
      val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(animation = tween(650, easing = LinearEasing), repeatMode = RepeatMode.Reverse)
      )

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 12.dp, end = 52.dp)
          .align(Alignment.CenterStart)
          .height(44.dp)
          .background(MaterialTheme.colors.surface),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (isLocked) {
            IconButton(
              onClick = {
                RichHaptics.perform(HapticPattern.TOGGLE_OFF)
                onCancelAction?.invoke()
              },
              modifier = Modifier.size(28.dp)
            ) {
              Icon(
                painter = painterResource(MR.images.ic_close),
                contentDescription = "Cancel",
                tint = Color(0xFFE53935),
                modifier = Modifier.size(20.dp)
              )
            }
            Spacer(Modifier.width(8.dp))
          }

          Box(
            modifier = Modifier
              .size(9.dp)
              .clip(CircleShape)
              .background(Color(0xFFE53935).copy(alpha = dotAlpha))
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = timerText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onSurface
          )
        }

        if (!isLocked) {
          val cancelThresholdPx = with(LocalDensity.current) { 70.dp.toPx() }
          val cancelProgress = (-dragOffsetX / cancelThresholdPx).coerceIn(0f, 1f)
          val slideAlpha = (1f - cancelProgress * 1.3f).coerceIn(0f, 1f)

          Row(
            modifier = Modifier
              .offset { IntOffset((dragOffsetX * 0.35f).toInt(), 0) }
              .alpha(slideAlpha),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "‹   Slide to cancel",
              fontSize = 13.5.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f)
            )
          }
        }
      }
    }

    if (showDeleteTextButton.value && !isRecordingActive) {
      DeleteTextButton(composeState)
    }

    // 3. Правый угол (кнопка микрофона/камеры)
    Box(Modifier.align(Alignment.BottomEnd).padding(bottom = if (appPlatform.isAndroid) 0.dp else 5.sp.toDp() * fontSizeSqrtMultiplier)) {
      val sendButtonSize = remember { Animatable(36f) }
      val sendButtonAlpha = remember { Animatable(1f) }
      val scope = rememberCoroutineScope()

      when {
        cs.progressByTimeout -> ComposeProgressIndicator()
        cs.contextItem is ComposeContextItem.ReportedItem -> {
          SendMsgButton(painterResource(MR.images.ic_check_filled), sendButtonSize, sendButtonAlpha, sendButtonColor, !sendMsgButtonDisabled, sendToConnect, sendMessage)
        }
        showVoiceButton && sendMsgEnabled -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            val stopRecOnNextClick = remember { mutableStateOf(false) }
            RecordVoiceView(
              recState = recState,
              stopRecOnNextClick = stopRecOnNextClick,
              onRecordingStateChange = { active, secs, locked, dragX, cancelBlock ->
                isRecordingActive = active
                recordingSeconds = secs
                isLocked = locked
                dragOffsetX = dragX
                onCancelAction = cancelBlock
              },
              onVideoRecorded = { uri ->
                // Прямое прикрепление видео к сообщению
                composeState.value = composeState.value.copy(
                  preview = ComposePreview.FilePreview(uri.path.substringAfterLast('/'), uri)
                )
              }
            )
          }
        }
        else -> {
          val icon = if (cs.editing || cs.liveMessage != null) painterResource(MR.images.ic_check_filled) else painterResource(MR.images.ic_arrow_upward)
          if (!hideSendButton) {
            SendMsgButton(icon, sendButtonSize, sendButtonAlpha, sendButtonColor, !sendMsgButtonDisabled, sendToConnect, sendMessage)
          }
        }
      }
    }
  }
}

@Composable
expect fun allowedToRecordVoiceByPlatform(): Boolean

@Composable
expect fun VoiceButtonWithoutPermissionByPlatform()

@Composable
private fun CustomDisappearingMessageDialog(
  showMenu: MutableState<Boolean>,
  sendMessage: (Int?) -> Unit,
  customDisappearingMessageTimePref: SharedPreference<Int>?
) {
  DefaultDropdownMenu(showMenu) {
    Text(
      generalGetString(MR.strings.send_disappearing_message),
      Modifier.padding(vertical = DEFAULT_PADDING_HALF, horizontal = DEFAULT_PADDING * 1.5f),
      fontSize = 16.sp,
      color = MaterialTheme.colors.secondary
    )

    ItemAction(generalGetString(MR.strings.send_disappearing_message_30_seconds)) {
      sendMessage(30)
      showMenu.value = false
    }
    ItemAction(generalGetString(MR.strings.send_disappearing_message_1_minute)) {
      sendMessage(60)
      showMenu.value = false
    }
    ItemAction(generalGetString(MR.strings.send_disappearing_message_5_minutes)) {
      sendMessage(300)
      showMenu.value = false
    }
    ItemAction(generalGetString(MR.strings.send_disappearing_message_custom_time)) {
      showMenu.value = false
      val selectedDisappearingMessageTime = mutableStateOf(customDisappearingMessageTimePref?.get?.invoke() ?: 300)
      showCustomTimePickerDialog(
        selectedDisappearingMessageTime,
        title = generalGetString(MR.strings.delete_after),
        confirmButtonText = generalGetString(MR.strings.send_disappearing_message_send),
        confirmButtonAction = { ttl ->
          sendMessage(ttl)
          customDisappearingMessageTimePref?.set?.invoke(ttl)
        },
        cancel = { showMenu.value = false }
      )
    }
  }
}

@Composable
private fun BoxScope.DeleteTextButton(composeState: MutableState<ComposeState>) {
  IconButton(
    { composeState.value = composeState.value.copy(message = ComposeMessage()) },
    Modifier.align(Alignment.TopEnd).size(36.dp)
  ) {
    Icon(painterResource(MR.images.ic_close), null, Modifier.padding(7.dp).size(36.dp), tint = MaterialTheme.colors.secondary)
  }
}

@Composable
private fun RecordVoiceView(
  recState: MutableState<RecordingState>,
  stopRecOnNextClick: MutableState<Boolean>,
  onRecordingStateChange: (Boolean, Int, Boolean, Float, () -> Unit) -> Unit,
  onVideoRecorded: (URI) -> Unit
) {
  val rec: RecorderInterface = remember { RecorderNative() }
  DisposableEffect(Unit) { onDispose { rec.stop() } }

  var recordMode by rememberSaveable { mutableStateOf(RecordMediaMode.VOICE) }
  var isHoldingToRecord by remember { mutableStateOf(false) }
  var isVideoRecordingActive by remember { mutableStateOf(false) }
  var dragOffsetY by remember { mutableStateOf(0f) }
  var dragOffsetX by remember { mutableStateOf(0f) }
  var recordingSeconds by remember { mutableStateOf(0) }

  val density = LocalDensity.current
  val lockThresholdPx = with(density) { 56.dp.toPx() }
  val cancelThresholdPx = with(density) { 70.dp.toPx() }
  val lockProgress = (-dragOffsetY / lockThresholdPx).coerceIn(0f, 1f)

  val isLocked = stopRecOnNextClick.value
  val isAudioRecording = recState.value is RecordingState.Started
  val isAnyRecording = isAudioRecording || isVideoRecordingActive

  val stopVoiceAndSend: () -> Unit = {
    recState.value.filePathNullable?.let {
      recState.value = RecordingState.Finished(it, rec.stop())
    }
    isHoldingToRecord = false
    stopRecOnNextClick.value = false
  }

  val stopVideoAndSend: () -> Unit = {
    SquareVideoBridge.stopRecording?.invoke { uri ->
      onVideoRecorded(uri)
    }
    isVideoRecordingActive = false
    isHoldingToRecord = false
    stopRecOnNextClick.value = false
  }

  val cancelAll: () -> Unit = {
    if (recordMode == RecordMediaMode.VOICE) {
      rec.stop()
      recState.value = RecordingState.NotStarted
    } else {
      SquareVideoBridge.cancelRecording?.invoke()
      isVideoRecordingActive = false
    }
    stopRecOnNextClick.value = false
    isHoldingToRecord = false
    dragOffsetY = 0f
    dragOffsetX = 0f
  }

  // Обновление верхнего бара
  LaunchedEffect(isAnyRecording, isHoldingToRecord, recordingSeconds, isLocked, dragOffsetX) {
    onRecordingStateChange(
      isAnyRecording || isHoldingToRecord,
      recordingSeconds,
      isLocked,
      dragOffsetX,
      cancelAll
    )
  }

  // Таймер до 02:00
  LaunchedEffect(isAnyRecording) {
    if (isAnyRecording) {
      recordingSeconds = 0
      while (recordingSeconds < 120 && (recState.value is RecordingState.Started || isVideoRecordingActive)) {
        delay(1000L)
        recordingSeconds++
      }
      if (recordingSeconds >= 120) {
        RichHaptics.perform(HapticPattern.RECORD_STOP)
        if (isVideoRecordingActive) stopVideoAndSend() else stopVoiceAndSend()
      }
    } else {
      recordingSeconds = 0
    }
  }

  // Кнопка записи и замочек
  Box(contentAlignment = Alignment.Center) {
    AnimatedVisibility(
      visible = isHoldingToRecord && !isLocked && isAnyRecording,
      enter = fadeIn(tween(140)) + slideInVertically(initialOffsetY = { it / 2 }),
      exit = fadeOut(tween(140)) + scaleOut()
    ) {
      val lockOffsetPx = with(density) { (-64).dp.toPx() } + (dragOffsetY * 0.25f)
      Surface(
        modifier = Modifier
          .offset { IntOffset(0, lockOffsetPx.toInt()) }
          .width(36.dp)
          .height(54.dp)
          .border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.25f + lockProgress * 0.5f), RoundedCornerShape(percent = 50)),
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
            painter = painterResource(if (lockProgress >= 0.85f) MR.images.ic_lock_filled else MR.images.ic_lock),
            contentDescription = null,
            tint = if (lockProgress >= 0.85f) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.size(16.dp)
          )
          Icon(
            painter = painterResource(MR.images.ic_arrow_upward),
            contentDescription = null,
            tint = MaterialTheme.colors.primary.copy(alpha = (1f - lockProgress).coerceIn(0.25f, 0.85f)),
            modifier = Modifier.size(12.dp)
          )
        }
      }
    }

    val buttonScale by animateFloatAsState(
      targetValue = if (isHoldingToRecord && !isLocked) 1.25f else 1f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    Box(
      modifier = Modifier
        .size(36.dp)
        .scale(buttonScale)
        .clip(CircleShape)
        .background(
          if (isLocked) MaterialTheme.colors.primary
          else if (isHoldingToRecord) MaterialTheme.colors.primary.copy(alpha = 0.15f)
          else Color.Transparent
        )
        .pointerInput(recordMode, isLocked) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)

            if (isLocked) {
              do {
                val event = awaitPointerEvent()
              } while (event.changes.any { it.pressed })
              RichHaptics.perform(HapticPattern.RECORD_STOP)
              if (recordMode == RecordMediaMode.VIDEO) stopVideoAndSend() else stopVoiceAndSend()
              return@awaitEachGesture
            }

            dragOffsetY = 0f
            dragOffsetX = 0f

            val upChange = withTimeoutOrNull(220L) {
              do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) return@withTimeoutOrNull change
              } while (true)
              null
            }

            if (upChange != null) {
              // Короткий клик: смена режима
              RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
              recordMode = if (recordMode == RecordMediaMode.VOICE) RecordMediaMode.VIDEO else RecordMediaMode.VOICE
            } else {
              // Удержание: раздельный старт
              isHoldingToRecord = true
              RichHaptics.perform(HapticPattern.RECORD_START)

              if (recordMode == RecordMediaMode.VIDEO) {
                isVideoRecordingActive = true
                SquareVideoBridge.startRecording?.invoke()
              } else {
                val filePath = rec.start { progress: Int?, finished: Boolean ->
                  val state = recState.value
                  if (state is RecordingState.Started && progress != null) {
                    recState.value = if (!finished) RecordingState.Started(state.filePath, progress) else RecordingState.Finished(state.filePath, progress)
                  }
                }
                if (filePath.isNotEmpty()) recState.value = RecordingState.Started(filePath = filePath)
              }

              var lockedByDrag = false
              var cancelledByDrag = false

              do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (change.pressed) {
                  val delta = change.positionChange()
                  dragOffsetY = (dragOffsetY + delta.y).coerceAtMost(0f)
                  dragOffsetX = (dragOffsetX + delta.x).coerceAtMost(0f)
                  change.consume()

                  if (dragOffsetY <= -lockThresholdPx && !lockedByDrag) {
                    lockedByDrag = true
                    stopRecOnNextClick.value = true
                    RichHaptics.perform(HapticPattern.TOGGLE_ON)
                    break
                  }

                  if (dragOffsetX <= -cancelThresholdPx && !cancelledByDrag) {
                    cancelledByDrag = true
                    RichHaptics.perform(HapticPattern.TOGGLE_OFF)
                    cancelAll()
                    break
                  }
                }
              } while (event.changes.any { it.pressed && it.id == down.id })

              isHoldingToRecord = false
              dragOffsetY = 0f
              dragOffsetX = 0f

              if (!lockedByDrag && !cancelledByDrag) {
                RichHaptics.perform(HapticPattern.RECORD_STOP)
                if (recordMode == RecordMediaMode.VIDEO) stopVideoAndSend() else stopVoiceAndSend()
              }
            }
          }
        },
      contentAlignment = Alignment.Center
    ) {
      if (isLocked) {
        Icon(
          painter = painterResource(MR.images.ic_stop_filled),
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(36.dp).padding(6.dp)
        )
      } else {
        Crossfade(targetState = recordMode, animationSpec = tween(150)) { mode ->
          Icon(
            painter = painterResource(if (mode == RecordMediaMode.VOICE) MR.images.ic_keyboard_voice_filled else MR.images.ic_videocam),
            contentDescription = null,
            tint = MaterialTheme.colors.primary,
            modifier = Modifier.size(34.dp).padding(4.dp)
          )
        }
      }
    }
  }

  // Квадратное превью 1:1 по центру экрана
  val cameraPreviewProvider = LocalSquareCameraPreview.current
  SquareVideoRecordOverlay(
    isRecording = isVideoRecordingActive,
    recordingSeconds = recordingSeconds,
    cameraPreview = {
      if (cameraPreviewProvider != null) {
        cameraPreviewProvider(isVideoRecordingActive, Modifier.fillMaxSize())
      }
    }
  )
}

    val buttonScale by animateFloatAsState(
      targetValue = if (isHoldingToRecord && !isLocked) 1.25f else 1f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    Box(
      modifier = Modifier
        .size(36.dp)
        .scale(buttonScale)
        .clip(CircleShape)
        .background(
          if (isLocked) MaterialTheme.colors.primary
          else if (isHoldingToRecord) MaterialTheme.colors.primary.copy(alpha = 0.15f)
          else Color.Transparent
        )
        .pointerInput(recordMode, isLocked) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)

            if (isLocked) {
              do {
                val event = awaitPointerEvent()
              } while (event.changes.any { it.pressed })
              RichHaptics.perform(HapticPattern.RECORD_STOP)
              if (recordMode == RecordMediaMode.VIDEO) stopVideoRecordingAndSend() else stopVoiceRecordingAndAddAudio()
              return@awaitEachGesture
            }

            dragOffsetY = 0f
            dragOffsetX = 0f

            val upChange = withTimeoutOrNull(220L) {
              do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) return@withTimeoutOrNull change
              } while (true)
              null
            }

            if (upChange != null) {
              // Короткий тап: переключение Микрофон <-> Камера
              RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
              recordMode = if (recordMode == RecordMediaMode.VOICE) RecordMediaMode.VIDEO else RecordMediaMode.VOICE
            } else {
              // Зажатие (>= 220 мс): старт соответствующего движка
              isHoldingToRecord = true
              RichHaptics.perform(HapticPattern.RECORD_START)

              if (recordMode == RecordMediaMode.VIDEO) {
                startVideoRecording()
              } else {
                startVoiceRecording()
              }

              var lockedByDrag = false
              var cancelledByDrag = false

              do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (change.pressed) {
                  val delta = change.positionChange()
                  dragOffsetY = (dragOffsetY + delta.y).coerceAtMost(0f)
                  dragOffsetX = (dragOffsetX + delta.x).coerceAtMost(0f)
                  change.consume()

                  // Свайп вверх к замочку (>= 56.dp)
                  if (dragOffsetY <= -lockThresholdPx && !lockedByDrag) {
                    lockedByDrag = true
                    stopRecOnNextClick.value = true
                    RichHaptics.perform(HapticPattern.TOGGLE_ON)
                    break
                  }

                  // Свайп влево (>= 70.dp)
                  if (dragOffsetX <= -cancelThresholdPx && !cancelledByDrag) {
                    cancelledByDrag = true
                    RichHaptics.perform(HapticPattern.TOGGLE_OFF)
                    if (recordMode == RecordMediaMode.VIDEO) cancelVideoRecording() else cancelVoiceRecording()
                    break
                  }
                }
              } while (event.changes.any { it.pressed && it.id == down.id })

              isHoldingToRecord = false
              dragOffsetY = 0f
              dragOffsetX = 0f

              // Отпустили палец без фиксации замочка и без отмены -> завершить и отправить
              if (!lockedByDrag && !cancelledByDrag) {
                RichHaptics.perform(HapticPattern.RECORD_STOP)
                if (recordMode == RecordMediaMode.VIDEO) stopVideoRecordingAndSend() else stopVoiceRecordingAndAddAudio()
              }
            }
          }
        },
      contentAlignment = Alignment.Center
    ) {
      if (isLocked) {
        Icon(
          painter = painterResource(MR.images.ic_stop_filled),
          contentDescription = stringResource(MR.strings.icon_descr_record_voice_message),
          tint = Color.White,
          modifier = Modifier.size(36.dp).padding(6.dp)
        )
      } else {
        Crossfade(targetState = recordMode, animationSpec = tween(150)) { mode ->
          val iconPainter = if (mode == RecordMediaMode.VOICE) {
            painterResource(MR.images.ic_keyboard_voice_filled)
          } else {
            painterResource(MR.images.ic_videocam)
          }
          Icon(
            painter = iconPainter,
            contentDescription = stringResource(MR.strings.icon_descr_record_voice_message),
            tint = MaterialTheme.colors.primary,
            modifier = Modifier.size(34.dp).padding(4.dp)
          )
        }
      }
    }
  }

  // =====================================================================
  // 3. КВАДРАТНЫЙ ВИДОИСКАТЕЛЬ CameraX 1:1 (ПО ЦЕНТРУ ЭКРАНА)
  // =====================================================================
  val cameraPreviewProvider = LocalSquareCameraPreview.current

  SquareVideoRecordOverlay(
    isRecording = isVideoRecordingActive,
    recordingSeconds = recordingSeconds,
    cameraPreview = {
      if (cameraPreviewProvider != null) {
        cameraPreviewProvider(isVideoRecordingActive, Modifier.fillMaxSize())
      } else {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1E)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            painter = painterResource(MR.images.ic_videocam),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(54.dp)
          )
        }
      }
    }
  )
}

// =====================================================================
// КВАДРАТНЫЙ ОВЕРЛЕЙ ПРЕВЬЮ 1:1 С ТАЙМЕРОМ 02:00
// =====================================================================
@Composable
private fun SquareVideoRecordOverlay(
  isRecording: Boolean,
  recordingSeconds: Int,
  cameraPreview: @Composable () -> Unit
) {
  val infiniteTransition = rememberInfiniteTransition()
  val dotAlpha by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0.25f,
    animationSpec = infiniteRepeatable(animation = tween(650, easing = LinearEasing), repeatMode = RepeatMode.Reverse)
  )

  AnimatedVisibility(
    visible = isRecording,
    enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.88f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
    exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.88f)
  ) {
    Popup(
      alignment = Alignment.Center,
      properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        Surface(
          modifier = Modifier
            .size(280.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .border(2.dp, MaterialTheme.colors.primary.copy(alpha = 0.65f), RoundedCornerShape(28.dp)),
          shape = RoundedCornerShape(28.dp),
          color = Color.Black,
          elevation = 12.dp
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            cameraPreview()

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
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(Color(0xFFE53935).copy(alpha = dotAlpha))
              )

              val mins = (recordingSeconds / 60).toString().padStart(2, '0')
              val secs = (recordingSeconds % 60).toString().padStart(2, '0')
              Text(
                text = "$mins:$secs / 02:00",
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

@Composable
private fun DisallowedVoiceButton(onClick: () -> Unit) {
  IconButton(onClick, Modifier.size(36.dp)) {
    Icon(
      painterResource(MR.images.ic_keyboard_voice),
      stringResource(MR.strings.icon_descr_record_voice_message),
      tint = MaterialTheme.colors.secondary,
      modifier = Modifier
        .size(36.dp)
        .padding(4.dp)
    )
  }
}

@Composable
fun VoiceButtonWithoutPermission(onClick: () -> Unit) {
  IconButton(onClick, Modifier.size(36.dp)) {
    Icon(
      painterResource(MR.images.ic_keyboard_voice_filled),
      stringResource(MR.strings.icon_descr_record_voice_message),
      tint = MaterialTheme.colors.primary,
      modifier = Modifier
        .size(34.dp)
        .padding(4.dp)
    )
  }
}

@Composable
private fun StopRecordButton(onClick: () -> Unit) {
  IconButton(onClick, Modifier.size(36.dp)) {
    Icon(
      painterResource(MR.images.ic_stop_filled),
      stringResource(MR.strings.icon_descr_record_voice_message),
      tint = MaterialTheme.colors.primary,
      modifier = Modifier
        .size(36.dp)
        .padding(4.dp)
    )
  }
}

@Composable
private fun RecordVoiceButton(interactionSource: MutableInteractionSource) {
  IconButton({}, Modifier.size(36.dp), interactionSource = interactionSource) {
    Icon(
      painterResource(MR.images.ic_keyboard_voice_filled),
      stringResource(MR.strings.icon_descr_record_voice_message),
      tint = MaterialTheme.colors.primary,
      modifier = Modifier
        .size(34.dp)
        .padding(4.dp)
    )
  }
}

@Composable
fun ComposeProgressIndicator() {
  CircularProgressIndicator(Modifier.size(36.dp).padding(4.dp), color = MaterialTheme.colors.secondary, strokeWidth = 2.5.dp)
}

@Composable
private fun CancelLiveMessageButton(
  onClick: () -> Unit
) {
  IconButton(onClick, Modifier.size(36.dp)) {
    Icon(
      painterResource(MR.images.ic_close),
      stringResource(MR.strings.icon_descr_cancel_live_message),
      tint = MaterialTheme.colors.primary,
      modifier = Modifier
        .size(36.dp)
        .padding(4.dp)
    )
  }
}

@Composable
private fun SendMsgButton(
  icon: Painter,
  sizeDp: Animatable<Float, AnimationVector1D>,
  alpha: Animatable<Float, AnimationVector1D>,
  sendButtonColor: Color,
  enabled: Boolean,
  sendToConnect: (() -> Unit)?,
  sendMessage: (Int?) -> Unit,
  onLongClick: (() -> Unit)? = null
) {
  val interactionSource = remember { MutableInteractionSource() }
  val ripple = remember { ripple(bounded = false, radius = 24.dp) }
  Box(
    modifier = Modifier.requiredSize(36.dp)
      .combinedClickable(
        onClick = {
          if (sendToConnect != null) {
            sendToConnect()
          } else {
            sendMessage(null)
          }
        },
        onLongClick = onLongClick,
        enabled = enabled,
        role = Role.Button,
        interactionSource = interactionSource,
        indication = ripple
      )
      .onRightClick { onLongClick?.invoke() },
    contentAlignment = Alignment.Center
  ) {
    Icon(
      icon,
      stringResource(MR.strings.icon_descr_send_message),
      tint = if (enabled) Color.White else MaterialTheme.colors.onSurface.copy(alpha = 0.38f),
      modifier = Modifier
        .size(sizeDp.value.dp)
        .alpha(alpha.value)
        .clip(CircleShape)
        .background(
          if (enabled) sendButtonColor 
          else MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
        )
        .padding(7.dp)
    )
  }
}

@Composable
private fun StartLiveMessageButton(onClick: () -> Unit) {
  val interactionSource = remember { MutableInteractionSource() }
  val ripple = remember { ripple(bounded = false, radius = 24.dp) }
  Box(
    modifier = Modifier.requiredSize(36.dp)
      .clickable(
        onClick = onClick,
        role = Role.Button,
        interactionSource = interactionSource,
        indication = ripple
      ),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      BoltFilled,
      stringResource(MR.strings.icon_descr_send_message),
      tint = MaterialTheme.colors.primary,
      modifier = Modifier
        .size(36.dp)
        .padding(4.dp)
    )
  }
}

private fun startLiveMessage(
  scope: CoroutineScope,
  send: suspend () -> Unit,
  update: suspend () -> Unit,
  sendButtonSize: Animatable<Float, AnimationVector1D>,
  sendButtonAlpha: Animatable<Float, AnimationVector1D>,
  composeState: MutableState<ComposeState>,
  liveMessageAlertShown: SharedPreference<Boolean>
) {
  fun run() {
    scope.launch {
      while (composeState.value.liveMessage != null) {
        sendButtonSize.animateTo(if (sendButtonSize.value == 36f) 32f else 36f, tween(700, 50))
      }
      sendButtonSize.snapTo(36f)
    }
    scope.launch {
      while (composeState.value.liveMessage != null) {
        sendButtonAlpha.animateTo(if (sendButtonAlpha.value == 1f) 0.75f else 1f, tween(700, 50))
      }
      sendButtonAlpha.snapTo(1f)
    }
    scope.launch {
      delay(3000)
      while (composeState.value.liveMessage != null) {
        update()
        delay(3000)
      }
    }
  }

  fun start() = withBGApi {
    if (composeState.value.liveMessage == null) {
      send()
    }
    run()
  }

  if (liveMessageAlertShown.state.value) {
    start()
  } else {
    AlertManager.shared.showAlertDialog(
      title = generalGetString(MR.strings.live_message),
      text = generalGetString(MR.strings.send_live_message_desc),
      confirmText = generalGetString(MR.strings.send_verb),
      onConfirm = {
        liveMessageAlertShown.set(true)
        start()
      })
  }
}

private fun showNeedToAllowVoiceAlert(onConfirm: () -> Unit) {
  AlertManager.shared.showAlertDialog(
    title = generalGetString(MR.strings.allow_voice_messages_question),
    text = generalGetString(MR.strings.you_need_to_allow_to_send_voice),
    confirmText = generalGetString(MR.strings.allow_verb),
    dismissText = generalGetString(MR.strings.cancel_verb),
    onConfirm = onConfirm,
  )
}

private fun showDisabledVoiceAlert(isDirectChat: Boolean) {
  AlertManager.shared.showAlertMsg(
    title = generalGetString(MR.strings.voice_messages_prohibited),
    text = generalGetString(
      if (isDirectChat)
        MR.strings.ask_your_contact_to_enable_voice
      else
        MR.strings.only_group_owners_can_enable_voice
    )
  )
}

@Preview
@Composable
fun PreviewSendMsgView() {
  val smallFont = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onBackground)
  val textStyle = remember { mutableStateOf(smallFont) }
  SimpleXTheme {
    SendMsgView(
      composeState = remember { mutableStateOf(ComposeState(useLinkPreviews = true)) },
      showVoiceRecordIcon = false,
      recState = remember { mutableStateOf(RecordingState.NotStarted) },
      isDirectChat = true,
      liveMessageAlertShown = SharedPreference(get = { true }, set = { }),
      sendMsgEnabled = true,
      userCantSendReason = null,
      sendButtonEnabled = true,
      nextConnect = false,
      needToAllowVoiceToContact = false,
      allowedVoiceByPrefs = true,
      allowVoiceToContact = {},
      timedMessageAllowed = false,
      placeholder = "",
      sendMessage = {},
      editPrevMessage = {},
      onMessageChange = { _ -> },
      onFilesPasted = {},
      textStyle = textStyle,
    )
  }
}

@Preview
@Composable
fun PreviewSendMsgViewEditing() {
  val smallFont = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onBackground)
  val textStyle = remember { mutableStateOf(smallFont) }
  val composeStateEditing = ComposeState(editingItem = ChatItem.getSampleData(), useLinkPreviews = true)
  SimpleXTheme {
    SendMsgView(
      composeState = remember { mutableStateOf(composeStateEditing) },
      showVoiceRecordIcon = false,
      recState = remember { mutableStateOf(RecordingState.NotStarted) },
      isDirectChat = true,
      liveMessageAlertShown = SharedPreference(get = { true }, set = { }),
      sendMsgEnabled = true,
      userCantSendReason = null,
      sendButtonEnabled = true,
      nextConnect = false,
      needToAllowVoiceToContact = false,
      allowedVoiceByPrefs = true,
      allowVoiceToContact = {},
      timedMessageAllowed = false,
      placeholder = "",
      sendMessage = {},
      editPrevMessage = {},
      onMessageChange = { _ -> },
      onFilesPasted = {},
      textStyle = textStyle,
    )
  }
}

@Preview
@Composable
fun PreviewSendMsgViewInProgress() {
  val smallFont = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onBackground)
  val textStyle = remember { mutableStateOf(smallFont) }
  val composeStateInProgress = ComposeState(preview = ComposePreview.FilePreview("test.txt", getAppFileUri("test.txt")), inProgress = true, useLinkPreviews = true)
  SimpleXTheme {
    SendMsgView(
      composeState = remember { mutableStateOf(composeStateInProgress) },
      showVoiceRecordIcon = false,
      recState = remember { mutableStateOf(RecordingState.NotStarted) },
      isDirectChat = true,
      liveMessageAlertShown = SharedPreference(get = { true }, set = { }),
      sendMsgEnabled = true,
      userCantSendReason = null,
      sendButtonEnabled = true,
      nextConnect = false,
      needToAllowVoiceToContact = false,
      allowedVoiceByPrefs = true,
      allowVoiceToContact = {},
      timedMessageAllowed = false,
      placeholder = "",
      sendMessage = {},
      editPrevMessage = {},
      onMessageChange = { _ -> },
      onFilesPasted = {},
      textStyle = textStyle,
    )
  }
}
