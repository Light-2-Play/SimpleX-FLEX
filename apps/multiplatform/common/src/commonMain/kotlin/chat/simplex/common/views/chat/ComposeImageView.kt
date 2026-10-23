package chat.simplex.common.views.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.simplex.common.platform.base64ToBitmap
import chat.simplex.common.ui.theme.DEFAULT_PADDING_HALF
import chat.simplex.common.views.helpers.UploadContent
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource

@Composable
expect fun MediaQualityButton(
  isVideo: Boolean = false,
  modifier: Modifier = Modifier
)

@Composable
fun ComposeImageView(media: ComposePreview.MediaPreview, cancelImages: () -> Unit, cancelEnabled: Boolean) {
  val isVideo = remember(media) { media.content.any { it is UploadContent.Video } }

  Row(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LazyRow(
      Modifier
        .weight(1f)
        .padding(start = 12.dp, end = if (cancelEnabled) 0.dp else 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(DEFAULT_PADDING_HALF),
    ) {
      itemsIndexed(media.images) { index, item ->
        val content = media.content[index]
        val imageBitmap = base64ToBitmap(item)
        if (content is UploadContent.Video) {
          Box(contentAlignment = Alignment.Center) {
            Image(
              imageBitmap,
              contentDescription = "preview video",
              modifier = Modifier
                .widthIn(max = 80.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(12.dp))
            )
            Icon(
              painterResource(MR.images.ic_videocam_filled),
              contentDescription = "preview video",
              modifier = Modifier.size(20.dp),
              tint = Color.White
            )
          }
        } else {
          Image(
            imageBitmap,
            contentDescription = "preview image",
            modifier = Modifier
              .widthIn(max = 80.dp)
              .height(60.dp)
              .clip(RoundedCornerShape(12.dp))
          )
        }
      }
    }

    MediaQualityButton(
      isVideo = isVideo,
      modifier = Modifier.padding(horizontal = 6.dp)
    )

    if (cancelEnabled) {
      IconButton(cancelImages) {
        Icon(
          painterResource(MR.images.ic_close),
          contentDescription = stringResource(MR.strings.cancel_verb),
          tint = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
          modifier = Modifier.padding(10.dp)
        )
      }
    }
  }
}
