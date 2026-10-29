package chat.simplex.common.views.chat

import SectionBottomSpacer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import boofcv.alg.fiducial.qrcode.QrCode
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.newchat.*
import chat.simplex.res.MR

@Composable
fun VerifyCodeView(
  displayName: String,
  connectionCode: String?,
  connectionVerified: Boolean,
  verify: suspend (String?) -> Pair<Boolean, String>?,
  close: () -> Unit,
  verifyDescription: StringResource = MR.strings.to_verify_compare,
) {
  if (connectionCode != null) {
    VerifyCodeLayout(
      displayName = displayName,
      connectionCode = connectionCode,
      connectionVerified = connectionVerified,
      verifyDescription = verifyDescription,
      verifyCode = { newCode ->
        val res = verify(newCode)
        if (res != null) {
          val (verified) = res
          if (verified) close()
          verified
        } else {
          false
        }
      }
    )
  }
}

@Composable
private fun VerifyCodeLayout(
  displayName: String,
  connectionCode: String,
  connectionVerified: Boolean,
  verifyDescription: StringResource,
  verifyCode: suspend (String?) -> Boolean,
) {
  ColumnWithScrollBar(Modifier.padding(horizontal = DEFAULT_PADDING)) {
    AppBarTitle(stringResource(MR.strings.security_code), withPadding = false)

    // Заголовок статуса верификации
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = DEFAULT_PADDING_HALF),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (connectionVerified) {
        Icon(
          painter = painterResource(MR.images.ic_verified_user),
          contentDescription = null,
          modifier = Modifier.padding(end = 6.dp).size(20.dp),
          tint = MaterialTheme.colors.secondary
        )
      }
      Text(
        text = if (connectionVerified) "$displayName is verified" else "$displayName is not verified",
        style = MaterialTheme.typography.subtitle1,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.onSurface
      )
    }

    // Material 3 Expressive Verification Card со встроенным переключателем стилей
    ExpressiveVerificationQRCodeCard(
      verificationUri = connectionCode,
      safetyNumberShort = splitToParts(connectionCode, 18),
      isVerified = connectionVerified,
      onScanOtherClick = if (appPlatform.isAndroid) {
        {
          ModalManager.end.showModal {
            ScanCodeView(verifyCode) { }
          }
        }
      } else null,
      onConfirmVerification = {
        withApi {
          val verified = verifyCode(connectionCode)
          if (!verified) {
            AlertManager.shared.showAlertMsg(
              title = generalGetString(MR.strings.incorrect_code)
            )
          }
        }
      },
      onClearVerification = {
        withApi { verifyCode(null) }
      }
    )

    // Пояснительный текст
    Text(
      text = generalGetString(verifyDescription),
      style = MaterialTheme.typography.body2,
      color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
      textAlign = TextAlign.Center,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = DEFAULT_PADDING)
    )

    SectionBottomSpacer()
  }
}

@Composable
private fun ExpressiveVerificationQRCodeCard(
  verificationUri: String,
  safetyNumberShort: String,
  isVerified: Boolean,
  onScanOtherClick: (() -> Unit)?,
  onConfirmVerification: () -> Unit,
  onClearVerification: () -> Unit
) {
  val clipboardManager = LocalClipboardManager.current

  val isLight = MaterialTheme.colors.isLight
  val primary = MaterialTheme.colors.primary
  val cardBg = lerp(
    MaterialTheme.colors.surface,
    primary,
    if (isLight) 0.06f else 0.09f
  )
  val actionBtnBg = lerp(
    MaterialTheme.colors.surface,
    primary,
    if (isLight) 0.14f else 0.20f
  )

  // 1. Создаем сырой объект QR-кода из SimpleX / BoofCV
  val qrCodeObject = remember(verificationUri) {
    boofcv.alg.fiducial.qrcode.QrCodeGenerator().apply {
      setVersion(0)
      setError(boofcv.alg.fiducial.qrcode.QrCode.ErrorLevel.M)
      setMask(boofcv.alg.fiducial.qrcode.QrCodeMaskPattern.M011)
      alphanumeric(verificationUri)
    }.qr
  }

  // 2. Генерируем классический Bitmap
  val classicQrBitmap = remember(verificationUri) {
    qrCodeBitmap(verificationUri, 1024, QrCode.ErrorLevel.M)
      .replaceColor(Color.Black.toArgb(), Color(0xFF191C1E).toArgb())
  }

  // 3. Конвертируем QR-объект в BooleanArray матрицу для Modern (M3) рендера
  val qrMatrix = remember(qrCodeObject) {
    if (qrCodeObject != null) {
      val size = qrCodeObject.modules.width
      Array(size) { y ->
        BooleanArray(size) { x ->
          qrCodeObject.modules.get(x, y) == 1
        }
      }
    } else null
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 6.dp),
    shape = RoundedCornerShape(32.dp),
    color = cardBg,
    elevation = 0.dp
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // 1. Бейдж безопасности
      Surface(
        shape = RoundedCornerShape(percent = 50),
        color = if (isVerified) Color(0xFF4CAF50).copy(alpha = 0.16f) else primary.copy(alpha = 0.12f)
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            painter = painterResource(if (isVerified) MR.images.ic_verified_user else MR.images.ic_lock_filled),
            contentDescription = null,
            tint = if (isVerified) Color(0xFF2E7D32) else primary,
            modifier = Modifier.size(16.dp)
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = if (isVerified) "KEYS VERIFIED" else "END-TO-END ENCRYPTION",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = if (isVerified) Color(0xFF2E7D32) else primary
          )
        }
      }

      // 2. Универсальный переключатель и рендерер QR
      // Передаем нашу матрицу и классический битмап внутрь компонента
      ToggleableQRCodeView(
        matrix = qrMatrix,
        classicBitmap = classicQrBitmap,
        modifier = Modifier.padding(top = 8.dp)
      )

      // 3. Текстовый отпечаток с копированием по тапу
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = primary.copy(alpha = 0.08f),
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .clickable {
            RichHaptics.perform(HapticPattern.CLICK)
            clipboardManager.setText(AnnotatedString(verificationUri))
          }
      ) {
        Column(
          modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = safetyNumberShort,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
            textAlign = TextAlign.Center
          )
          Spacer(Modifier.height(4.dp))
          Text(
            text = "Tap to copy safety number",
            fontSize = 11.sp,
            color = primary.copy(alpha = 0.8f)
          )
        }
      }

      // 4. Кнопки действий
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (onScanOtherClick != null) {
          Button(
            onClick = {
              RichHaptics.perform(HapticPattern.CLICK)
              onScanOtherClick()
            },
            modifier = Modifier
              .fillMaxWidth()
              .height(46.dp),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
              backgroundColor = primary,
              contentColor = MaterialTheme.colors.onPrimary
            ),
            elevation = null
          ) {
            Icon(
              painter = painterResource(MR.images.ic_qr_code),
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = "Scan Contact's QR Code",
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        if (!isVerified) {
          Button(
            onClick = {
              RichHaptics.perform(HapticPattern.TOGGLE_ON)
              onConfirmVerification()
            },
            modifier = Modifier
              .fillMaxWidth()
              .height(44.dp),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
              backgroundColor = actionBtnBg,
              contentColor = primary
            ),
            elevation = null
          ) {
            Icon(
              painter = painterResource(MR.images.ic_check_filled),
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = "Mark as Verified",
              fontWeight = FontWeight.SemiBold
            )
          }
        } else {
          OutlinedButton(
            onClick = {
              RichHaptics.perform(HapticPattern.TOGGLE_OFF)
              onClearVerification()
            },
            modifier = Modifier
              .fillMaxWidth()
              .height(44.dp),
            shape = RoundedCornerShape(percent = 50),
            border = BorderStroke(1.dp, MaterialTheme.colors.error.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = MaterialTheme.colors.error
            )
          ) {
            Icon(
              painter = painterResource(MR.images.ic_close),
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = "Clear Verification",
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }
}

private fun splitToParts(s: String, length: Int): String {
  if (length >= s.length) return s
  return (0..(s.length - 1) / length)
    .map { s.drop(it * length).take(length) }
    .joinToString(separator = "\n")
}
