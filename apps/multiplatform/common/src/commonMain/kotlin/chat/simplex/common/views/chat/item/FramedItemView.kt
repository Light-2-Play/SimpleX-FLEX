package chat.simplex.common.views.chat.item

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.chat.*
import chat.simplex.common.views.helpers.*

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
  onLinkLongClick: (String) -> Unit = {},
  scrollToItem: (Long) -> Unit = {},
  scrollToItemId: MutableState<Long?> = remember { mutableStateOf(null) },
  scrollToQuotedItemFromItem: (Long) -> Unit = {},
) {
  val mc = ci.content.msgContent
  val cInfo = chat.chatInfo
  val terminal = LocalTerminalLayout.current

  when (mc) {
    is MsgContent.MCFile -> {
      CIFileView(
        ci.file,
        ci.meta,
        cInfo.timedMessagesTTL,
        showViaProxy = showViaProxy,
        showTimestamp = showTimestamp,
        showMenu = showMenu,
        receiveFile = receiveFile
      )
    }
    is MsgContent.MCImage -> {
      CIImageView(
        mc.image,
        ci.file,
        imageProvider ?: { providerForGallery(emptyList(), ci.id) {} },
        showMenu,
        smallView = false,
        receiveFile = receiveFile
      )
    }
    is MsgContent.MCVideo -> {
      CIVideoView(
        mc.image,
        mc.duration,
        ci.file,
        imageProvider ?: { providerForGallery(emptyList(), ci.id) {} },
        showMenu,
        smallView = false,
        receiveFile = receiveFile
      )
    }
    is MsgContent.MCVoice -> {
      ItemWithMeta(meta = { CIMetaView(ci, cInfo.timedMessagesTTL, showViaProxy = showViaProxy, showTimestamp = showTimestamp) }) { inlineMeta ->
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
          CIVoiceView(
            mc.duration,
            ci.file,
            ci.meta.itemEdited,
            ci.chatDir.sent,
            hasText = mc.text.isNotEmpty(),
            ci,
            cInfo.timedMessagesTTL,
            showViaProxy = showViaProxy,
            showTimestamp = showTimestamp,
            longClick = { onLinkLongClick("") },
            receiveFile = receiveFile
          )
          if (mc.text.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
              Text(
                text = mc.text,
                style = chatTextStyle(16.sp, MaterialTheme.colors.onSurface),
                color = MaterialTheme.colors.onSurface
              )
            }
          }
          if (inlineMeta != null) {
            Box(Modifier.align(Alignment.End).padding(top = 4.dp)) {
              inlineMeta()
            }
          }
        }
      }
    }
    is MsgContent.MCLink -> {
      ItemWithMeta(meta = { CIMetaView(ci, cInfo.timedMessagesTTL, showViaProxy = showViaProxy, showTimestamp = showTimestamp) }) { inlineMeta ->
        Column(Modifier.padding(bottom = 6.dp)) {
          ChatItemLinkView(mc.preview, showMenu, onLongClick = { onLinkLongClick("") })
          if (mc.text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.padding(horizontal = 12.dp)) {
              SelectionContainer {
                Text(
                  text = mc.text,
                  style = chatTextStyle(16.sp, MaterialTheme.colors.onSurface),
                  color = MaterialTheme.colors.onSurface
                )
              }
            }
          }
          if (inlineMeta != null) {
            Box(Modifier.align(Alignment.End).padding(top = 4.dp, end = 12.dp)) {
              inlineMeta()
            }
          }
        }
      }
    }
    is MsgContent.MCText -> {
      if (mc.text.isNotEmpty()) {
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
      } else if (ci.file != null && ci.file.fileStatus !is CIFileStatus.Invalid) {
        CIFileView(ci.file, ci.meta, cInfo.timedMessagesTTL, showViaProxy, showTimestamp, showMenu, smallView = true, receiveFile = receiveFile)
      }
    }
    is MsgContent.MCReport -> {
      val padding = if (terminal) Modifier.padding(horizontal = 8.dp) else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
      Column(padding) {
        Text(
          text = "Report: ${mc.reason.text}",
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
    }
    else -> {
      val padding = if (terminal) Modifier.padding(horizontal = 8.dp) else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
      Column(padding) {
        Text(
          text = ci.content.text.ifEmpty { "Unsupported message" },
          style = chatTextStyle(16.sp, MaterialTheme.colors.secondary),
          color = MaterialTheme.colors.secondary
        )
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
