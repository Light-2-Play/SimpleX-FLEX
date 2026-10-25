package chat.simplex.common.views.newchat

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.stringResource
import boofcv.alg.drawing.FiducialImageEngine
import boofcv.alg.fiducial.qrcode.*
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import kotlinx.coroutines.launch

@Composable
fun SimpleXCreatedLinkQRCode(
  connLink: CreatedConnLink,
  short: Boolean,
  modifier: Modifier = Modifier,
  padding: PaddingValues = PaddingValues(horizontal = DEFAULT_PADDING * 2f, vertical = DEFAULT_PADDING_HALF),
  tintColor: Color = Color(0xff062d56),
  withLogo: Boolean = true,
  onShare: (() -> Unit)? = null,
) {
  QRCode(
    connLink.simplexChatUri(short),
    small = short && connLink.connShortLink != null,
    modifier,
    padding,
    tintColor,
    withLogo,
    onShare,
  )
}

@Composable
fun SimpleXLinkQRCode(
  connReq: String,
  modifier: Modifier = Modifier,
  padding: PaddingValues = PaddingValues(horizontal = DEFAULT_PADDING * 2f, vertical = DEFAULT_PADDING_HALF),
  tintColor: Color = Color(0xff062d56),
  withLogo: Boolean = true,
  onShare: (() -> Unit)? = null,
) {
  QRCode(
    simplexChatLink(connReq),
    small = connReq.count() < 200,
    modifier,
    padding,
    tintColor,
    withLogo,
    onShare,
  )
}

@Composable
fun QRCode(
  connReq: String,
  small: Boolean = false,
  modifier: Modifier = Modifier,
  padding: PaddingValues = PaddingValues(horizontal = DEFAULT_PADDING * 2f, vertical = DEFAULT_PADDING_HALF),
  tintColor: Color = Color(0xff062d56),
  withLogo: Boolean = true,
  onShare: (() -> Unit)? = null,
) {
  val scope = rememberCoroutineScope()
  val qrSize = if (small) 210.dp else 260.dp

  var qrStyle by rememberSaveable { mutableStateOf(QrDesignStyle.MODERN) }

  val errorLevel = if (small) QrCode.ErrorLevel.M else QrCode.ErrorLevel.L
  val logoSize = if (small) 0.21f else 0.16f

  val classicImage = remember(connReq, errorLevel, tintColor, withLogo) {
    qrCodeBitmap(connReq, 1024, errorLevel)
      .replaceColor(Color.Black.toArgb(), tintColor.toArgb())
      .let { if (withLogo) it.addLogo(logoSize) else it }
  }

  val shareAction: () -> Unit = {
    scope.launch {
      val file = saveTempImageUncompressed(classicImage, true)
      if (file != null) {
        shareFile("", CryptoFile.plain(file.absolutePath))
        onShare?.invoke()
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(padding),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // 1. Отображение QR-кода в выбранном стиле
    Box(
      modifier = Modifier.size(qrSize),
      contentAlignment = Alignment.Center
    ) {
      if (qrStyle == QrDesignStyle.MODERN) {
        Material3ExpressiveQRCode(
          content = connReq,
          size = qrSize,
          primaryColor = Color(0xFF191C1E),
          containerColor = Color.White,
          modifier = modifier.clickable { shareAction() }
        )
      } else {
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = Color.White,
          elevation = 2.dp,
          modifier = modifier
            .size(qrSize)
            .clip(RoundedCornerShape(24.dp))
            .clickable { shareAction() }
        ) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(12.dp),
            contentAlignment = Alignment.Center
          ) {
            Image(
              bitmap = classicImage,
              contentDescription = null,
              modifier = Modifier.fillMaxSize()
            )
          }
        }
      }
    }

    Spacer(Modifier.height(14.dp))

    // 2. Селектор стиля (Modern / Classic)
    Surface(
      shape = RoundedCornerShape(percent = 50),
      color = MaterialTheme.colors.primary.copy(alpha = 0.12f),
      border = BorderStroke(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.22f))
    ) {
      Row(
        modifier = Modifier.padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        QrStyleChip(
          text = "Modern",
          selected = qrStyle == QrDesignStyle.MODERN,
          onClick = {
            if (qrStyle != QrDesignStyle.MODERN) {
              RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
              qrStyle = QrDesignStyle.MODERN
            }
          }
        )

        QrStyleChip(
          text = "Classic",
          selected = qrStyle == QrDesignStyle.CLASSIC,
          onClick = {
            if (qrStyle != QrDesignStyle.CLASSIC) {
              RichHaptics.perform(HapticPattern.SELECTION_CHANGE)
              qrStyle = QrDesignStyle.CLASSIC
            }
          }
        )
      }
    }
  }
}

@Composable
private fun QrStyleChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit
) {
  val primaryColor = MaterialTheme.colors.primary
  val onSurfaceColor = MaterialTheme.colors.onSurface

  Surface(
    shape = CircleShape,
    color = if (selected) primaryColor else Color.Transparent,
    modifier = Modifier
      .clip(CircleShape)
      .clickable(onClick = onClick)
  ) {
    Text(
      text = text,
      fontSize = 12.5.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color = if (selected) MaterialTheme.colors.onPrimary else onSurfaceColor.copy(alpha = 0.7f),
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
  }
}

fun qrCodeBitmap(content: String, size: Int = 1024, errorLevel: QrCode.ErrorLevel): ImageBitmap {
  val qrCode = QrCodeEncoder().addAutomatic(content).setError(errorLevel).fixate()
  val numModules = QrCode.totalModules(qrCode.version)
  val borderModule = if (CurrentColors.value.colors.isLight) 0 else 1
  val pixelsPerModule = size / (numModules + 2 * borderModule)
  val renderer = QrCodeGeneratorImage(pixelsPerModule + 1)
  renderer.borderModule = borderModule
  renderer.render(qrCode)
  return renderer.gray.toImageBitmap().scale(size, size)
}

expect fun ImageBitmap.replaceColor(from: Int, to: Int): ImageBitmap

@Preview
@Composable
fun PreviewQRCode() {
  SimpleXTheme {
    QRCode(connReq = "https://simplex.chat/contact#/?v=1&smp=smp%3A%2F%2FPQUV2eL0t7OStZOoAsPEV2QYWt4-xilbakvGUGOItUo%3D%40smp6.simplex.im%2FK1rslx-m5bpXVIdMZg9NLUZ_8JBm8xTt%23MCowBQYDK2VuAyEALDeVe-sG8mRY22LsXlPgiwTNs9dbiLrNuA7f3ZMAJ2w%3D")
  }
}
