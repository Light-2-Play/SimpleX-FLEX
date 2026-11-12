package chat.simplex.common.views.chat.item

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import dev.icerock.moko.resources.compose.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import dev.icerock.moko.resources.StringResource

@Composable
fun FramedItemView(
  chatsCtx: ChatModel.ChatsContext,
  chat: Chat,
  ci: ChatItem,
  uriHandler: UriHandler = LocalUriHandler.current,
  imageProvider: (() -> ImageGalleryProvider)? = null,
  linkMode: SimplexLinkMode,
  showViaProxy: Boolean = false,
  showMenu: MutableState<Boolean>,
  showTimestamp: Boolean = true,
  tailVisible: Boolean = false,
  receiveFile: (Long) -> Unit,
  onLinkLongClick: (String) -> Unit = { },
  scrollToItem: (Long) -> Unit = {},
  scrollToItemId: MutableState<Long?> = remember { mutableStateOf(null) },
  scrollToQuotedItemFromItem: (Long) -> Unit = {},
) {
  val mc = ci.content.msgContent
  val cInfo = chat.chatInfo
  
  if (mc is MsgContent.MCFile) {
    CIFileView(
      ci.file,
      ci.meta,
      cInfo.timedMessagesTTL,
      showViaProxy = showViaProxy,
      showTimestamp = showTimestamp,
      showMenu = showMenu,
      receiveFile = receiveFile
    )
  } else if (mc is MsgContent.MCImage) {
    CIImageView(
      mc.image,
      ci.file,
      imageProvider ?: { providerForGallery(emptyList(), ci.id) {} },
      showMenu,
      smallView = false,
      receiveFile
    )
  } else if (mc is MsgContent.MCVideo) {
    CIVideoView(
      mc.image,
      mc.duration,
      ci.file,
      imageProvider ?: { providerForGallery(emptyList(), ci.id) {} },
      showMenu,
      smallView = false,
      receiveFile
    )
  } else if (mc is MsgContent.MCText) {
    val showText = mc.text.isNotEmpty()
    val terminal = LocalTerminalLayout.current
    if (showText) {
      if (terminal) {
        Column(Modifier.padding(horizontal = 8.dp)) {
          SelectionContainer {
            Text(
              text = mc.text,
              style = chatTextStyle(16.sp, MaterialTheme.colors.onSurface),
              color = MaterialTheme.colors.onSurface
            )
          }
          if (ci.file != null && ci.file.fileStatus !is CIFileStatus.Invalid) {
            Spacer(Modifier.height(8.dp))
            CIFileView(ci.file, ci.meta, cInfo.timedMessagesTTL, showViaProxy, showTimestamp, showMenu, smallView = true, receiveFile = receiveFile)
          }
        }
      } else {
        ItemWithMeta(meta = { CIMetaView(ci, cInfo.timedMessagesTTL, showViaProxy = showViaProxy, showTimestamp = showTimestamp) }) { inlineMeta ->
          Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (ci.file != null && ci.file.fileStatus !is CIFileStatus.Invalid) {
              CIFileView(ci.file, ci.meta, cInfo.timedMessagesTTL, showViaProxy, showTimestamp, showMenu, smallView = true, receiveFile = receiveFile)
              Spacer(Modifier.height(6.dp))
            }
            SelectionContainer {
              Text(
                text = mc.text,
                style = chatTextStyle(16.sp, MaterialTheme.colors.onSurface),
                color = MaterialTheme.colors.onSurface
              )
            }
            if (inlineMeta != null) {
              Box(Modifier.align(Alignment.End).padding(top = 4.dp)) {
                inlineMeta()
              }
            }
          }
        }
      }
    } else {
      if (ci.file != null && ci.file.fileStatus !is CIFileStatus.Invalid) {
        CIFileView(ci.file, ci.meta, cInfo.timedMessagesTTL, showViaProxy, showTimestamp, showMenu, smallView = true, receiveFile = receiveFile)
      }
    }
  } else if (mc is MsgContent.MCReport) {
    val terminal = LocalTerminalLayout.current
    val padding = if (terminal) Modifier.padding(horizontal = 8.dp) else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    Column(padding) {
      Text(
        text = stringResource(MR.strings.report_message_prefix, mc.reason.text),
        style = chatTextStyle(16.sp, MaterialTheme.colors.error),
        color = MaterialTheme.colors.error
      )
      Spacer(Modifier.height(4.dp))
      SelectionContainer {
        Text(
          text = mc.text,
          style = chatTextStyle(16.sp, MaterialTheme.colors.onSurface),
          color = MaterialTheme.colors.onSurface
        )
      }
    }
  } else {
    // Unknown or other types
    val terminal = LocalTerminalLayout.current
    val padding = if (terminal) Modifier.padding(horizontal = 8.dp) else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    Column(padding) {
      Text(
        text = stringResource(MR.strings.unknown_message_type),
        style = chatTextStyle(16.sp, MaterialTheme.colors.secondary),
        color = MaterialTheme.colors.secondary
      )
    }
  }
}

@Composable
fun IntegrityErrorItemView(
  msgError: MsgError,
  ci: ChatItem,
  showTimestamp: Boolean,
  timedMessagesTTL: Int?
) {
  val title = stringResource(MR.strings.integrity_error)
  val message = when (msgError) {
    MsgError.FormatError -> stringResource(MR.strings.integrity_error_format)
    MsgError.DecryptionError -> stringResource(MR.strings.integrity_error_decryption)
    MsgError.InvalidSignature -> stringResource(MR.strings.integrity_error_signature)
    MsgError.InvalidKey -> stringResource(MR.strings.integrity_error_key)
    MsgError.ProtocolError -> stringResource(MR.strings.integrity_error_protocol)
    MsgError.SuspiciousEvent -> stringResource(MR.strings.integrity_error_suspicious)
  }
  
  ItemWithMeta(meta = { CIMetaView(ci, timedMessagesTTL, showViaProxy = false, showTimestamp = showTimestamp) }) { inlineMeta ->
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
      Text(
        text = title,
        style = chatTextStyle(16.sp, MaterialTheme.colors.error),
        color = MaterialTheme.colors.error,
        fontWeight = FontWeight.Bold
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = message,
        style = chatTextStyle(14.sp, MaterialTheme.colors.secondary),
        color = MaterialTheme.colors.secondary
      )
      if (inlineMeta != null) {
        Box(Modifier.align(Alignment.End).padding(top = 4.dp)) {
          inlineMeta()
        }
      }
    }
  }
}

@Composable
fun RcvMsgErrorItemView(
  rcvMsgError: MsgError,
  ci: ChatItem,
  showTimestamp: Boolean,
  timedMessagesTTL: Int?
) {
  val title = stringResource(MR.strings.message_error)
  val message = when (rcvMsgError) {
    MsgError.FormatError -> stringResource(MR.strings.message_error_format)
    MsgError.DecryptionError -> stringResource(MR.strings.message_error_decryption)
    MsgError.InvalidSignature -> stringResource(MR.strings.message_error_signature)
    MsgError.InvalidKey -> stringResource(MR.strings.message_error_key)
    MsgError.ProtocolError -> stringResource(MR.strings.message_error_protocol)
    MsgError.SuspiciousEvent -> stringResource(MR.strings.message_error_suspicious)
  }
  
  ItemWithMeta(meta = { CIMetaView(ci, timedMessagesTTL, showViaProxy = false, showTimestamp = showTimestamp) }) { inlineMeta ->
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
      Text(
        text = title,
        style = chatTextStyle(16.sp, MaterialTheme.colors.error),
        color = MaterialTheme.colors.error,
        fontWeight = FontWeight.Bold
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = message,
        style = chatTextStyle(14.sp, MaterialTheme.colors.secondary),
        color = MaterialTheme.colors.secondary
      )
      if (inlineMeta != null) {
        Box(Modifier.align(Alignment.End).padding(top = 4.dp)) {
          inlineMeta()
        }
      }
    }
  }
}

@Composable
fun MarkedDeletedItemView(
  chatsCtx: ChatModel.ChatsContext,
  ci: ChatItem,
  cInfo: ChatInfo,
  timedMessagesTTL: Int?,
  revealed: State<Boolean>,
  showViaProxy: Boolean,
  showTimestamp: Boolean
) {
  val sent = ci.chatDir.sent
  val itemDeleted = ci.meta.itemDeleted ?: return
  
  ItemWithMeta(meta = { CIMetaView(ci, timedMessagesTTL, showViaProxy = showViaProxy, showTimestamp = showTimestamp) }) { inlineMeta ->
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
      Text(
        text = if (itemDeleted.byContact) {
          if (sent) stringResource(MR.strings.deleted_by_contact_msg) else stringResource(MR.strings.deleted_by_contact_rcv)
        } else {
          stringResource(MR.strings.deleted_msg)
        },
        style = chatTextStyle(16.sp, MaterialTheme.colors.secondary),
        color = MaterialTheme.colors.secondary,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
      )
      if (inlineMeta != null) {
        Box(Modifier.align(Alignment.End).padding(top = 4.dp)) {
          inlineMeta()
        }
      }
    }
  }
}

@Composable
fun CIMemberCreatedContactView(
  cItem: ChatItem,
  openDirectChat: (Long) -> Unit
) {
  val event = (cItem.content as? CIContent.RcvGroupEventContent)?.rcvGroupEvent as? RcvGroupEvent.MemberCreatedContact ?: return
  
  Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
    Text(
      text = stringResource(MR.strings.group_event_member_created_contact, cItem.memberDisplayName ?: ""),
      style = chatTextStyle(14.sp, MaterialTheme.colors.primary),
      color = MaterialTheme.colors.primary
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { openDirectChat(event.contactId) }) {
      Text(stringResource(MR.strings.open_chat))
    }
  }
}

@Composable
fun CIInvalidJSONView(json: String) {
  Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
    Text(
      text = stringResource(MR.strings.invalid_json),
      style = chatTextStyle(16.sp, MaterialTheme.colors.error),
      color = MaterialTheme.colors.error,
      fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = json,
      style = chatTextStyle(14.sp, MaterialTheme.colors.secondary),
      color = MaterialTheme.colors.secondary,
      maxLines = 5,
      overflow = TextOverflow.Ellipsis
    )
  }
}

@Composable
fun CIFeaturePreferenceView(
  cItem: ChatItem,
  contact: Contact?,
  feature: ChatFeature,
  allowed: Boolean,
  acceptFeature: (Contact, ChatFeature, Int?) -> Unit
) {
  Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
    Text(
      text = stringResource(
        if (allowed) MR.strings.feature_preference_allowed else MR.strings.feature_preference_denied,
        stringResource(feature.nameId)
      ),
      style = chatTextStyle(16.sp, MaterialTheme.colors.onSurface),
      color = MaterialTheme.colors.onSurface
    )
    if (!allowed && contact != null) {
      Spacer(Modifier.height(8.dp))
      Button(onClick = { acceptFeature(contact, feature, null) }) {
        Text(stringResource(MR.strings.allow_verb))
      }
    }
  }
}

@Composable
fun CIChatFeatureView(
  chatsCtx: ChatModel.ChatsContext,
  cInfo: ChatInfo,
  ci: ChatItem,
  feature: ChatFeature,
  iconColor: Color,
  icon: ImageResource = feature.icon,
  revealed: State<Boolean>,
  showMenu: MutableState<Boolean>
) {
  ItemWithMeta(meta = { CIMetaView(ci, cInfo.timedMessagesTTL, showViaProxy = false, showTimestamp = true) }) { inlineMeta ->
    Row(
      Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        painterResource(icon),
        contentDescription = null,
        tint = iconColor,
        modifier = Modifier.size(24.dp)
      )
      Spacer(Modifier.width(8.dp))
      Text(
        text = stringResource(feature.nameId),
        style = chatTextStyle(16.sp, MaterialTheme.colors.onSurface),
        color = MaterialTheme.colors.onSurface
      )
      if (inlineMeta != null) {
        Spacer(Modifier.weight(1f))
        inlineMeta()
      }
    }
  }
}

@Preview
@Composable
fun PreviewFramedItemView() {
  SimpleXTheme {
    val showMenu = remember { mutableStateOf(false) }
    FramedItemView(
      chatsCtx = ChatModel.ChatsContext(secondaryContextFilter = null),
      chat = Chat.sampleData,
      ci = ChatItem.getSampleData(1, CIDirection.DirectRcv(), previewDate, "Hello world"),
      linkMode = SimplexLinkMode.DESCRIPTION,
      showMenu = showMenu,
      receiveFile = {}
    )
  }
}
