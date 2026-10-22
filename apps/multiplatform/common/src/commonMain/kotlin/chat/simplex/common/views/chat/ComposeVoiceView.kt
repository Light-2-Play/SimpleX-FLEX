package chat.simplex.common.views.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.CryptoFile
import chat.simplex.common.model.durationText
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.platform.AudioPlayer
import chat.simplex.res.MR
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ComposeVoiceView(
  filePath: String,
  recordedDurationMs: Int,
  finishedRecording: Boolean,
  cancelEnabled: Boolean,
  cancelVoice: () -> Unit
) {
  val progress = rememberSaveable { mutableStateOf(0) }
  val duration = rememberSaveable(recordedDurationMs) { mutableStateOf(recordedDurationMs) }
  val tonalColor = MaterialTheme.colors.surface.mixWith(MaterialTheme.colors.primary, 0.12f)
  val primary = MaterialTheme.colors.primary

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(tonalColor)
  ) {
    Box(
      Modifier
        .fillMaxWidth()
        .padding(top = 16.dp)
    ) {
      val audioPlaying = rememberSaveable { mutableStateOf(false) }
      Row(
        Modifier
          .height(52.dp)
          .fillMaxWidth()
          .background(Color.Transparent)
          .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = {
            if (!audioPlaying.value) {
              AudioPlayer.play(CryptoFile.plain(filePath), audioPlaying, progress, duration, resetOnEnd = false, smallView = false)
            } else {
              AudioPlayer.pause(audioPlaying, progress)
            }
          },
          enabled = finishedRecording
        ) {
          Icon(
            if (audioPlaying.value) painterResource(MR.images.ic_pause_filled) else painterResource(MR.images.ic_play_arrow_filled),
            stringResource(MR.strings.icon_descr_file),
            Modifier
              .padding(start = 4.dp, end = 2.dp)
              .size(32.dp),
            tint = if (finishedRecording) primary else MaterialTheme.colors.secondary
          )
        }
        val numberInText = remember(recordedDurationMs, progress.value) {
          derivedStateOf {
            when {
              finishedRecording && progress.value == 0 && !audioPlaying.value -> duration.value / 1000
              finishedRecording -> progress.value / 1000
              else -> recordedDurationMs / 1000
            }
          }
        }
        Text(
          durationText(numberInText.value),
          fontSize = 17.sp,
          color = MaterialTheme.colors.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (cancelEnabled) {
          IconButton(
            onClick = {
              AudioPlayer.stop(filePath)
              cancelVoice()
            },
            modifier = Modifier.padding(end = 4.dp)
          ) {
            Icon(
              painterResource(MR.images.ic_close),
              contentDescription = stringResource(MR.strings.icon_descr_cancel_file_preview),
              tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
              modifier = Modifier.size(20.dp)
            )
          }
        }
      }
    }

    if (finishedRecording) {
      FinishedRecordingSlider(tonalColor, progress, duration, filePath)
    } else {
      RecordingInProgressSlider(recordedDurationMs)
    }
  }
}

@Composable
fun FinishedRecordingSlider(backgroundColor: Color, progress: MutableState<Int>, duration: MutableState<Int>, filePath: String) {
  val primary = MaterialTheme.colors.primary
  val inactiveTrackColor = primary.copy(alpha = 0.20f)
  Slider(
    progress.value.toFloat(),
    onValueChange = { AudioPlayer.seekTo(it.toInt(), progress, filePath) },
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp),
    colors = SliderDefaults.colors(
      activeTrackColor = primary,
      inactiveTrackColor = inactiveTrackColor,
      thumbColor = primary
    ),
    valueRange = 0f..duration.value.toFloat()
  )
}

@Composable
fun RecordingInProgressSlider(recordedDurationMs: Int) {
  val thumbPosition = remember { Animatable(0f) }
  val recDuration = rememberUpdatedState(recordedDurationMs)
  LaunchedEffect(Unit) {
    snapshotFlow { recDuration.value }
      .distinctUntilChanged()
      .collect {
        thumbPosition.animateTo(it.toFloat(), audioProgressBarAnimationSpec())
      }
  }
  val primary = MaterialTheme.colors.primary
  val inactiveTrackColor = primary.copy(alpha = 0.20f)
  Slider(
    thumbPosition.value,
    onValueChange = {},
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp),
    colors = SliderDefaults.colors(
      disabledInactiveTrackColor = inactiveTrackColor,
      disabledActiveTrackColor = primary,
      thumbColor = Color.Transparent,
      disabledThumbColor = Color.Transparent
    ),
    enabled = false,
    valueRange = 0f..MAX_VOICE_MILLIS_FOR_SENDING.toFloat()
  )
}

@Preview
@Composable
fun PreviewComposeAudioView() {
  SimpleXTheme {
    ComposeFileView(
      "test.txt",
      cancelFile = {},
      cancelEnabled = true
    )
  }
}
