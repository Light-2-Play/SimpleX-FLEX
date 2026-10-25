package chat.simplex.common.views.chat

import SectionBottomSpacer
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
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
      displayName,
      connectionCode,
      connectionVerified,
      verifyDescription,
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

    // User status header in English
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = DEFAULT_PADDING_HALF),
      horizontalAlignment = Alignment.CenterHorizontally,
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

    // Material 3 Expressive Verification Card
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

    // Explanatory note
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

private fun splitToParts(s: String, length: Int): String {
  if (length >= s.length) return s
  return (0..(s.length - 1) / length)
    .map { s.drop(it * length).take(length) }
    .joinToString(separator = "\n")
}
