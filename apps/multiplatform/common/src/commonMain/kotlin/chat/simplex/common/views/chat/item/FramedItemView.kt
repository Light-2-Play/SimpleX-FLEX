package chat.simplex.common.views.chat.item

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.UriHandler
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.chat.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.chatlist.openChat
import chat.simplex.common.views.newchat.planAndConnect
import chat.simplex.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

@Composable
fun FramedItemView(
  chatsCtx: ChatModel.ChatsContext,
  chat: Chat,
  ci: ChatItem,
  uriHandler: UriHandler? = null,
  imageProvider: (() -> ImageGalleryProvider)? = null,
  linkMode: SimplexLinkMode,
  showViaProxy: Boolean,
  showMenu: MutableState<Boolean>,
  showTimestamp: Boolean,
  tailVisible: Boolean = false,
  receiveFile: (Long) -> Unit,
  onLinkLongClick: (link: String) -> Unit = {},
  scrollToItem: (Long) -> Unit = {},
  scrollToItemId: MutableState<Long?>,
  scrollToQuotedItemFromItem: (Long) -> Unit = {},
  endButton: (@Composable () -> Unit)? = null,
) {
  val chatInfo = chat.chatInfo
  val sent = ci.chatDir.sent
  val chatTTL = chatInfo.timedMessagesTTL
  val terminal = LocalTerminalLayout.current
  val cardTop = terminalCardTop(ci).toDp()
  val mediaModifier = if (terminal) Modifier.padding(start = CHAT_ITEM_TEXT_PADDING, top = cardTop, end = CHAT_ITEM_TEXT_PADDING).messageCard().clipChatItem(ci, revealed = true) else Modifier
  val quoteColor = if (sent) MaterialTheme.appColors.sentQuote else MaterialTheme.appColors.receivedQuote
  val cardModifier = mediaModifier.background(quoteColor)
  val thumbnailInset = if (terminal) Modifier.padding(end = CHAT_ITEM_TEXT_PADDING) else Modifier
  val thumbnailClip = if (terminal) Modifier.clipChatItem(ci, revealed = true) else Modifier
  val quoteBaseStyle = MaterialTheme.typography.body1

  fun membership(): GroupMember? {
    return if (chatInfo is ChatInfo.Group) chatInfo.groupInfo.membership else null
  }

  @Composable
  fun ciQuotedMsgTextView(qi: CIQuote, lines: Int, showTimestamp: Boolean, stripLink: String? = null, prefix: AnnotatedString? = null) {
    val color = if (terminal) MaterialTheme.colors.onSurface.copy(alpha = 0.7f) else MaterialTheme.colors.onSurface
    MarkdownText(
      qi.text,
      qi.formattedText,
      qi.markdownBlocks,
      toggleSecrets = true,
      maxLines = lines,
      overflow = TextOverflow.Ellipsis,
      style = quoteBaseStyle.copy(fontSize = 15.sp, color = color).merge(chatTextStyle(15.sp, color)),
      linkMode = linkMode,
      uriHandler = if (appPlatform.isDesktop) uriHandler else null,
      showTimestamp = showTimestamp,
      prefix = prefix,
      stripLink = stripLink,
    )
  }

  @Composable
  fun ciQuotedMsgView(qi: CIQuote, stripLink: String? = null, prefix: AnnotatedString? = null) {
    val sender = qi.sender(membership())
    Box(
      Modifier
        // this width limitation prevents crash on calculating constraints that may happen if you post veeeery long message and then quote it.
        // Top level layout wants `IntrinsicWidth.Max` and very long layout makes the crash in this case
        .widthIn(max = 50000.dp)
        .then(if (terminal) Modifier.padding(end = 12.dp) else Modifier.bubbleTextPadding(if (sender != null) 13.5.sp else 15.sp)),
      contentAlignment = Alignment.TopStart
    ) {
      if (sender != null) {
        Column(
          horizontalAlignment = Alignment.Start
        ) {
          Text(
            sender,
            style = quoteBaseStyle.copy(fontSize = 13.5.sp, color = if (qi.chatDir is CIDirection.GroupSnd) CurrentColors.value.colors.primary else CurrentColors.value.colors.secondary).merge(chatTextStyle(13.5.sp)),
            maxLines = 1
          )
          ciQuotedMsgTextView(qi, lines = 2, showTimestamp = showTimestamp, stripLink = stripLink, prefix = prefix)
        }
      } else {
        ciQuotedMsgTextView(qi, lines = 3, showTimestamp = showTimestamp, stripLink = stripLink, prefix = prefix)
      }
    }
  }

  @Composable
  fun RowScope.QuotedMsgOrGap(qi: CIQuote) {
    if (terminal && qi.text.isEmpty() && qi.sender(membership()) == null) {
      Spacer(Modifier.width(CHAT_ITEM_TEXT_PADDING - QUOTE_BAR_GAP))
    } else {
      Box(if (terminal) Modifier.weight(1f, fill = false) else Modifier.fillMaxWidth().weight(1f)) {
        ciQuotedMsgView(qi)
      }
    }
  }

  @Composable
  fun HeaderText(caption: String, italic: Boolean, fontSize: TextUnit = 12.sp, modifier: Modifier = Modifier) {
    Text(
      modifier = modifier,
      text = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = fontSize, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, color = MaterialTheme.colors.secondary)) {
          append(caption)
        }
      },
      style = MaterialTheme.typography.body1.copy(lineHeight = 22.sp).merge(chatTextStyle(fontSize, MaterialTheme.colors.secondary)),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }

  @Composable
  fun headerModifier(pad: Boolean, onClick: (() -> Unit)? = null): Modifier {
    return Modifier
      .then(if (terminal) Modifier else Modifier.background(quoteColor))
      .fillMaxWidth()
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .then(
        if (terminal) Modifier.padding(start = CHAT_ITEM_TEXT_PADDING, top = TERMINAL_HEADER_TOP_GAP.toDp(), end = 12.dp, bottom = TERMINAL_HEADER_BOTTOM_GAP.toDp())
        else Modifier.bubbleTextPadding(12.sp, bottom = if (pad || (ci.quotedItem == null && ci.meta.itemForwarded == null)) BUBBLE_TEXT_INSET else 0.dp)
      )
  }

  @Composable
  fun HeaderRow(modifier: Modifier, caption: String, italic: Boolean, icon: Painter?, iconColor: Color?) {
    Row(
      modifier,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (icon != null) {
        Icon(
          icon,
          caption,
          Modifier.inlineIcon().size(18.dp),
          tint = iconColor ?: if (isInDarkTheme()) FileDark else FileLight
        )
      }
      HeaderText(caption, italic)
    }
  }

  @Composable
  fun FramedItemHeader(caption: String, italic: Boolean, icon: Painter? = null, pad: Boolean = false, iconColor: Color? = null) {
    HeaderRow(headerModifier(pad), caption, italic, icon, iconColor)
  }

  @Composable
  fun ciQuoteView(qi: CIQuote) {
    val blurred = remember { mutableStateOf(appPreferences.privacyMediaBlurRadius.get() > 0) }
    Row(
      Modifier
        .then(if (terminal) Modifier.padding(start = CHAT_ITEM_TEXT_PADDING, top = terminalQuoteTop(ci).toDp()).messageCard().quoteBar(MaterialTheme.colors.onSurface) else Modifier.background(quoteColor))
        .fillMaxWidth()
    ) {
      when (qi.content) {
        is MsgContent.MCImage -> {
          QuotedMsgOrGap(qi)
          val imageBitmap = remember(qi.content.image) { base64ToBitmap(qi.content.image) }
          Image(
            imageBitmap,
            contentDescription = stringResource(MR.strings.image_descr),
            contentScale = ContentScale.Crop,
            modifier = thumbnailInset.size(68.dp).then(thumbnailClip).clipToBounds().desktopModifyBlurredState(blurred, showMenu).privacyBlur(fullSize = false, imageBitmap, blurred, chatViewScrollState.collectAsState(), onLongClick = { showMenu.value = true })
          )
        }
        is MsgContent.MCVideo -> {
          QuotedMsgOrGap(qi)
          val imageBitmap = remember(qi.content.image) { base64ToBitmap(qi.content.image) }
          Image(
            imageBitmap,
            contentDescription = stringResource(MR.strings.video_descr),
            contentScale = ContentScale.Crop,
            modifier = thumbnailInset.size(68.dp).then(thumbnailClip).clipToBounds().desktopModifyBlurredState(blurred, showMenu).privacyBlur(fullSize = false, imageBitmap, blurred, chatViewScrollState.collectAsState(), onLongClick = { showMenu.value = true })
          )
        }
        is MsgContent.MCFile, is MsgContent.MCVoice -> {
          Box(Modifier.fillMaxWidth().weight(1f)) {
            ciQuotedMsgView(qi)
          }
          Icon(
            if (qi.content is MsgContent.MCFile) painterResource(MR.images.ic_draft_filled) else painterResource(MR.images.ic_mic_filled),
            if (qi.content is MsgContent.MCFile) stringResource(MR.strings.icon_descr_file) else stringResource(MR.strings.voice_message),
            Modifier
              .padding(top = if (terminal) 0.dp else 6.dp, end = 4.dp)
              .size(22.dp),
            tint = if (isInDarkTheme()) FileDark else FileLight
          )
        }
        is MsgContent.MCChat -> {
          val prefix = buildAnnotatedString {
            append(qi.content.chatLink.displayName + if (qi.content.text != qi.content.chatLink.connLinkStr) " - " else "")
          }
          Box(Modifier.fillMaxWidth().weight(1f)) {
            ciQuotedMsgView(qi, stripLink = qi.content.chatLink.connLinkStr, prefix = prefix)
          }
          Icon(
            painterResource(qi.content.chatLink.smallIconRes),
            null,
            Modifier.padding(top = if (terminal) 0.dp else 6.dp, end = 4.dp).size(22.dp),
            tint = if (isInDarkTheme()) FileDark else FileLight
          )
        }
        else -> ciQuotedMsgView(qi)
      }
    }
  }

  @Composable
  fun ciFileView(ci: ChatItem, text: String) {
    CIFileView(ci.file, ci.meta, chatTTL, showViaProxy, showTimestamp, showMenu, false, cardTop, receiveFile)
    if (text != "" || ci.meta.isLive) {
      CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode = linkMode, uriHandler, showViaProxy = showViaProxy, showTimestamp = showTimestamp)
    }
  }

  val captionlessMedia = (ci.content.msgContent is MsgContent.MCImage || ci.content.msgContent is MsgContent.MCVideo) &&
      !ci.meta.isLive && ci.content.text.isEmpty()
  val transparentBackground = captionlessMedia && ci.quotedItem == null && ci.meta.itemForwarded == null

  // M3 Expressive цвета пузырей сообщений
  val isLight = MaterialTheme.colors.isLight
  val sentColor = lerp(MaterialTheme.appColors.sentMessage, MaterialTheme.colors.primary, if (isLight) 0.08f else 0.14f)
  val receivedColor = lerp(MaterialTheme.appColors.receivedMessage, MaterialTheme.colors.primary, if (isLight) 0.04f else 0.07f)

  val metaReserved = when (val mc = ci.content.msgContent) {
    is MsgContent.MCVoice -> mc.text != ""
    is MsgContent.MCChat -> mc.text != mc.chatLink.connLinkStr
    else -> !captionlessMedia
  }
  Box(Modifier
    .then(
      when {
        !terminal -> Modifier.clipChatItem(ci, tailVisible, revealed = true)
        else -> Modifier
      }
    )
    .background(
      when {
        transparentBackground || terminal -> Color.Transparent
        sent -> sentColor
        else -> receivedColor
      }
    )) {
    var metaColor = MaterialTheme.colors.secondary
    ItemWithMeta(
      meta = { CIMetaView(ci, chatTTL, metaColor, showViaProxy = showViaProxy, showTimestamp = showTimestamp) },
      overlayMeta = if (captionlessMedia) { { Box(Modifier.padding(end = CHAT_ITEM_TEXT_PADDING)) { CIMetaView(ci, chatTTL, Color.White, showViaProxy = showViaProxy, showTimestamp = showTimestamp, lineHeightColor = messageTextColor()) } } } else null,
      metaReserved = metaReserved,
      gapBelowCard = terminalReactionsGap(ci).toDp(),
      endButton = endButton
    ) { inlineMeta ->
      Box(contentAlignment = Alignment.BottomEnd) {
        val tailRendered = chatItemTailRendered(ci, tailVisible, revealed = true)
        Column(
          Modifier
            .then(if (terminal && captionlessMedia) Modifier else Modifier.width(IntrinsicSize.Max))
            .padding(start = if (!sent && tailRendered) msgTailWidthDp else 0.dp, end = if (sent && tailRendered) msgTailWidthDp else 0.dp)
        ) {
          PriorityLayout(Modifier, CHAT_IMAGE_LAYOUT_ID, limitToPriorityWidth = !terminal || captionlessMedia) {
            @Composable
            fun Header() {
              if (ci.isReport) {
                if (ci.meta.itemDeleted == null) {
                  FramedItemHeader(
                    stringResource(if (ci.chatDir.sent) MR.strings.report_item_visibility_submitter else MR.strings.report_item_visibility_moderators),
                    true,
                    painterResource(MR.images.ic_flag),
                    iconColor = Color.Red
                  )
                } else {
                  val text = if (ci.meta.itemDeleted is CIDeleted.Moderated && ci.meta.itemDeleted.byGroupMember.groupMemberId != (chatInfo as ChatInfo.Group?)?.groupInfo?.membership?.groupMemberId) {
                    stringResource(MR.strings.report_item_archived_by).format(ci.meta.itemDeleted.byGroupMember.displayName)
                  } else {
                    stringResource(MR.strings.report_item_archived)
                  }
                  FramedItemHeader(text, true, painterResource(MR.images.ic_flag))
                }
              } else if (ci.meta.itemDeleted != null) {
                when (ci.meta.itemDeleted) {
                  is CIDeleted.Moderated -> {
                    FramedItemHeader(String.format(stringResource(MR.strings.moderated_item_description), ci.meta.itemDeleted.byGroupMember.chatViewName), true, painterResource(MR.images.ic_flag))
                  }
                  is CIDeleted.Blocked -> {
                    FramedItemHeader(stringResource(MR.strings.blocked_item_description), true, painterResource(MR.images.ic_back_hand))
                  }
                  is CIDeleted.BlockedByAdmin -> {
                    FramedItemHeader(stringResource(MR.strings.blocked_by_admin_item_description), true, painterResource(MR.images.ic_back_hand))
                  }
                  is CIDeleted.Deleted -> {
                    FramedItemHeader(stringResource(MR.strings.marked_deleted_description), true, painterResource(MR.images.ic_delete))
                  }
                }
              } else if (ci.meta.isLive) {
                FramedItemHeader(stringResource(MR.strings.live), false)
              }
            }
            if (ci.quotedItem != null) {
              Column(
                Modifier
                  .combinedClickable(
                    onLongClick = { showMenu.value = true },
                    onClick = {
                      if (ci.quotedItem.itemId != null) {
                        if (ci.isReport && chatsCtx.secondaryContextFilter != null) {
                          scrollToItemId.value = ci.quotedItem.itemId
                        } else {
                          scrollToItem(ci.quotedItem.itemId)
                        }
                      } else {
                        scrollToQuotedItemFromItem(ci.id)
                      }
                    }
                  )
                  .onRightClick { showMenu.value = true }
              ) {
                Header()
                ciQuoteView(ci.quotedItem)
              }
            } else {
              Header()
              val forwarded = ci.meta.itemForwarded
              if (forwarded != null) {
                val twoRowHeader = if (chatInfo.chatType == ChatType.Local) {
                  forwarded.chatTypeApiIdMsgId != null || forwarded.sourceGroupLink != null
                } else {
                  when (forwarded) {
                    is CIForwardedFrom.Group -> forwarded.groupType != null
                    is CIForwardedFrom.GroupLink -> true
                    else -> false
                  }
                }
                if (twoRowHeader) {
                  val caption = stringResource(if (chatInfo.chatType == ChatType.Local) MR.strings.saved_from else MR.strings.forwarded_from)
                  Column(
                    headerModifier(pad = true, onClick = {
                      val target = forwarded.chatTypeApiIdMsgId
                      val link = forwarded.sourceGroupLink
                      if (target != null) {
                        val (chatType, apiId, itemId) = target
                        withBGApi { openChat(secondaryChatsCtx = null, chat.remoteHostId, chatType, apiId, itemId) }
                      } else if (link != null) {
                        withBGApi { planAndConnect(chat.remoteHostId, link, close = null) }
                      }
                    }),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    HeaderRow(Modifier, caption, true, painterResource(MR.images.ic_forward), null)
                    HeaderText(forwarded.chatName, italic = false, fontSize = 15.sp, modifier = Modifier.offset(y = (-2).dp))
                  }
                } else {
                  FramedItemHeader(forwarded.text(chatInfo.chatType), true, painterResource(MR.images.ic_forward), pad = true)
                }
              }
            }
            if (ci.file == null && ci.formattedText == null && !ci.meta.isLive && isShortEmoji(ci.content.text)) {
              Box(if (terminal) Modifier.padding(start = CHAT_ITEM_TEXT_PADDING, top = cardTop, end = CHAT_ITEM_TEXT_PADDING).messageCard() else Modifier.padding(bubbleTextBoxPadding())) {
                Column(
                  if (terminal) Modifier.messageLastLine() else Modifier.fillMaxWidth(),
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  EmojiText(ci.content.text)
                  if (!terminal) {
                    Text(
                      metaReserveCopy(ci.meta, chatTTL, encrypted = null, signedFileVerified = null, secondaryColor = MaterialTheme.colors.secondary, showViaProxy = showViaProxy, showTimestamp = showTimestamp),
                      color = Color.Transparent,
                      style = MaterialTheme.typography.body1.merge(chatTextStyle(MaterialTheme.typography.body1.fontSize))
                    )
                  }
                }
              }
            } else {
              when (val mc = ci.content.msgContent) {
                is MsgContent.MCImage -> {
                  val provider = imageProvider ?: return@PriorityLayout
                  Box(Modifier.layoutId(CHAT_IMAGE_LAYOUT_ID).then(mediaModifier)) {
                    CIImageView(image = mc.image, file = ci.file, provider, showMenu, false, receiveFile)
                  }
                  if (mc.text == "" && !ci.meta.isLive) {
                    if (!terminal) metaColor = Color.White
                  } else {
                    CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, showViaProxy = showViaProxy, showTimestamp = showTimestamp)
                  }
                }
                is MsgContent.MCVideo -> {
                  val provider = imageProvider ?: return@PriorityLayout
                  Box(Modifier.layoutId(CHAT_IMAGE_LAYOUT_ID).then(mediaModifier)) {
                    CIVideoView(image = mc.image, mc.duration, file = ci.file, provider, showMenu, smallView = false, receiveFile = receiveFile)
                  }
                  if (mc.text == "" && !ci.meta.isLive) {
                    if (!terminal) metaColor = Color.White
                  } else {
                    CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, showViaProxy = showViaProxy, showTimestamp = showTimestamp)
                  }
                }
                is MsgContent.MCVoice -> {
                  CIVoiceView(mc.duration, ci.file, ci.meta.itemEdited, ci.chatDir.sent, hasText = true, ci, timedMessagesTTL = chatTTL, showViaProxy = showViaProxy, showTimestamp = showTimestamp, longClick = { onLinkLongClick("") }, receiveFile = receiveFile)
                  if (mc.text != "") {
                    CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, showViaProxy = showViaProxy, showTimestamp = showTimestamp)
                  }
                }
                is MsgContent.MCFile -> ciFileView(ci, mc.text)
                is MsgContent.MCUnknown ->
                  if (ci.file == null) {
                    CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, onLinkLongClick, showViaProxy = showViaProxy, showTimestamp = showTimestamp)
                  } else {
                    ciFileView(ci, mc.text)
                  }
                is MsgContent.MCLink -> {
                  Box(Modifier.layoutId(CHAT_IMAGE_LAYOUT_ID).then(if (terminal) cardModifier.padding(bottom = 8.dp) else Modifier)) {
                    ChatItemLinkView(mc.preview, showMenu, onLongClick = { showMenu.value = true })
                  }
                  Box(if (terminal) Modifier else Modifier.widthIn(max = DEFAULT_MAX_IMAGE_WIDTH)) {
                    CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, onLinkLongClick, showViaProxy = showViaProxy, showTimestamp = showTimestamp)
                  }
                }
                is MsgContent.MCChat -> {
                  val hasText = mc.text != mc.chatLink.connLinkStr
                  Box(
                    Modifier.then(if (terminal) cardModifier.width(IntrinsicSize.Max) else Modifier).combinedClickable(
                      onClick = {
                        withBGApi { planAndConnect(chat.remoteHostId, mc.chatLink.connLinkStr, linkOwnerSig = mc.ownerSig, close = null) }
                      },
                      onLongClick = { showMenu.value = true }
                    )
                  ) {
                    CIChatLinkHeader(chatLink = mc.chatLink, ownerSig = mc.ownerSig, hasText = hasText)
                  }
                  if (hasText) {
                    CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, showViaProxy = showViaProxy, showTimestamp = showTimestamp, stripLink = mc.chatLink.connLinkStr)
                  }
                }
                is MsgContent.MCReport -> {
                  val prefix = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.Red, fontStyle = FontStyle.Italic)) {
                      append(itemPrefixText(ci))
                    }
                  }
                  CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, onLinkLongClick, showViaProxy = showViaProxy, showTimestamp = showTimestamp, prefix = prefix)
                }
                else -> CIMarkdownText(chatsCtx, ci, chat, chatTTL, linkMode, uriHandler, onLinkLongClick, showViaProxy = showViaProxy, showTimestamp = showTimestamp)
              }
            }
          }
        }
        if (inlineMeta != null) {
          Box(
            Modifier
              .padding(
                end = BUBBLE_SIDE_INSET + if (tailRendered && sent) msgTailWidthDp else 0.dp,
              )
              .bubbleMeta()
          ) {
            inlineMeta()
          }
        }
      }
    }
  }
}

@Composable
fun CIMarkdownText(
  chatsCtx: ChatModel.ChatsContext,
  ci: ChatItem,
  chat: Chat,
  chatTTL: Int?,
  linkMode: SimplexLinkMode,
  uriHandler: UriHandler?,
  onLinkLongClick: (link: String) -> Unit = {},
  showViaProxy: Boolean,
  showTimestamp: Boolean,
  prefix: AnnotatedString? = null,
  stripLink: String? = null
) {
  val chatInfo = chat.chatInfo
  val text = if (ci.meta.isLive) ci.content.msgContent?.text ?: ci.text else ci.text
  val bases = remember(ci, linkMode) { itemDisplay(ci, linkMode)?.bases ?: listOf(0) }
  val selection = setupItemSelection(LocalSelectionManager.current, LocalItemContext.current.selectionIndex, ci.meta.isLive == true, bases)
  val terminal = LocalTerminalLayout.current
  val msgFontSize = 17.6.sp

  Box(
    Modifier
      .padding(
        if (terminal) PaddingValues(start = CHAT_ITEM_TEXT_PADDING, top = terminalTextTop(ci).toDp(), end = CHAT_ITEM_TEXT_PADDING)
        else bubbleTextBoxPadding()
      )
  ) {
    MarkdownText(
      text, if (text.isEmpty()) emptyList() else ci.formattedText, if (text.isEmpty()) null else ci.markdownBlocks, toggleSecrets = true,
      sendCommandMsg = if (chatInfo.useCommands && chat.chatInfo.sndReady) { { msg -> sendCommandMsg(chatsCtx, chat, msg) } } else null,
      meta = ci.meta, chatTTL = chatTTL, encrypted = ci.encryptedFile, signedFileVerified = ci.file?.loaded, linkMode = linkMode,
      style = MaterialTheme.typography.body1.copy(fontSize = msgFontSize, color = messageTextColor(), lineHeight = 24.sp).merge(chatTextStyle(msgFontSize)),
      mentions = ci.mentions, userMemberId = when {
        chatInfo is ChatInfo.Group -> chatInfo.groupInfo.membership.memberId
        else -> null
      },
      uriHandler = uriHandler, senderBold = true, onLinkLongClick = onLinkLongClick, showViaProxy = showViaProxy, showTimestamp = showTimestamp, prefix = prefix,
      stripLink = stripLink,
      selection = selection,
      blockLayout = true,
      cardColor = if (ci.chatDir.sent) MaterialTheme.appColors.sentQuote else MaterialTheme.appColors.receivedQuote
    )
  }
}

fun sendCommandMsg(chatsCtx: ChatModel.ChatsContext, chat: Chat, msg: String) {
  if (chat.chatInfo.sndReady) {
    withLongRunningApi(slow = 60_000) {
      val cInfo = chat.chatInfo
      val chatItems =
        chatModel.controller.apiSendMessages(
          rh = chat.remoteHostId,
          type = cInfo.chatType,
          id = cInfo.apiId,
          scope = cInfo.groupChatScope(),
          composedMessages = listOf(ComposedMessage(fileSource = null, quotedItemId = null, msgContent = MsgContent.MCText(msg), mentions = emptyMap()))
        )
      if (!chatItems.isNullOrEmpty()) {
        chatItems.forEach { aChatItem ->
          withContext(Dispatchers.Main) {
            chatModel.addSentChatItem(chatsCtx, chat.remoteHostId, aChatItem.chatInfo, aChatItem.chatItem)
          }
        }
      }
    }
  } else {
    AlertManager.shared.showAlertMsg(MR.strings.cant_send_message_alert_title, MR.strings.cant_send_commands_alert_text)
  }
}

const val CHAT_IMAGE_LAYOUT_ID = "chatImage"
const val CHAT_BUBBLE_LAYOUT_ID = "chatBubble"
const val CHAT_COMPOSE_LAYOUT_ID = "chatCompose"
const val CONSOLE_COMPOSE_LAYOUT_ID = "consoleCompose"
val CHAT_ITEM_TEXT_PADDING = 12.dp
val BUBBLE_SIDE_INSET = 14.dp
val BUBBLE_TEXT_INSET = 11.dp
val BUBBLE_LINE_ABOVE_CAPS = 6.sp
val BUBBLE_LINE_BELOW_BASELINE = 4.375.sp
val BUBBLE_CLOSE_GAP = 2.sp
val BUBBLE_FAR_GAP = 8.sp

@Composable
fun Modifier.bubbleTextPadding(firstLineFontSize: TextUnit, bottom: Dp = BUBBLE_TEXT_INSET): Modifier =
  padding(horizontal = BUBBLE_SIDE_INSET).paddingFromBaseline(top = BUBBLE_TEXT_INSET + (firstLineFontSize * INTER_CAP_HEIGHT).toDp(), bottom = bottom)

@Composable
fun bubbleTextBoxPadding(): PaddingValues =
  PaddingValues(
    start = BUBBLE_SIDE_INSET,
    top = (BUBBLE_TEXT_INSET - BUBBLE_LINE_ABOVE_CAPS.toDp()).coerceAtLeast(0.dp),
    end = BUBBLE_SIDE_INSET,
    bottom = (BUBBLE_TEXT_INSET - BUBBLE_LINE_BELOW_BASELINE.toDp()).coerceAtLeast(0.dp)
  )

fun Modifier.bubbleMeta(): Modifier =
  layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, metaBaseline(placeable) + BUBBLE_TEXT_INSET.roundToPx()) { placeable.placeRelative(0, 0) }
  }

@Composable
fun RowScope.bubbleMetaOnBaseline(): Modifier {
  val density = LocalDensity.current
  return Modifier.alignBy { with(density) { metaBaseline(it) } }
}

@Composable
fun MetaOnLastBaseline(meta: @Composable () -> Unit, content: @Composable () -> Unit) {
  Layout({ content(); meta() }) { measurables, constraints ->
    val contentPlaceable = measurables[0].measure(constraints)
    val metaPlaceable = measurables[1].measure(constraints)
    val lastBaseline = contentPlaceable[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: contentPlaceable.height
    val metaTop = lastBaseline - metaBaseline(metaPlaceable)
    layout(contentPlaceable.width, maxOf(contentPlaceable.height, metaTop + metaPlaceable.height)) {
      contentPlaceable.placeRelative(0, 0)
      metaPlaceable.placeRelative(contentPlaceable.width - metaPlaceable.width, metaTop)
    }
  }
}

private fun horizontalPaddingAroundCustomLayouts(density: Float): Int =
  36 * ceil(density).toInt()

@Composable
fun PriorityLayout(
  modifier: Modifier = Modifier,
  priorityLayoutId: String,
  limitToPriorityWidth: Boolean = true,
  content: @Composable () -> Unit
) {
  Layout(
    content = content,
    modifier = modifier
  ) { measureable, constraints ->
    val maxImageHeight = (constraints.maxWidth * 2.33f).toInt().coerceAtMost(constraints.maxHeight)
    val imageConstraints = constraints.copy(maxHeight = maxImageHeight)
    val imagePlaceable = measureable.firstOrNull { it.layoutId == priorityLayoutId }?.measure(imageConstraints)
    val placeables: List<Placeable> = measureable.map {
      if (it.layoutId == priorityLayoutId)
        imagePlaceable!!
      else
        it.measure(constraints.copy(maxWidth = if (limitToPriorityWidth) imagePlaceable?.width ?: constraints.maxWidth else constraints.maxWidth))
    }
    val width = if (limitToPriorityWidth) imagePlaceable?.measuredWidth ?: placeables.maxOf { it.width } else placeables.maxOf { it.width }
    val height = placeables.sumOf { it.height }
    val adjustedConstraints = Constraints.fitPrioritizingHeight(constraints.minWidth, width, constraints.minHeight, height)
    layout(
      if (width > adjustedConstraints.maxWidth) adjustedConstraints.maxWidth - horizontalPaddingAroundCustomLayouts(density) else adjustedConstraints.maxWidth,
      adjustedConstraints.maxHeight
    ) {
      var y = 0
      placeables.forEach {
        if (limitToPriorityWidth) it.place(0, y) else it.placeRelative(0, y)
        y += it.measuredHeight
      }
    }
  }
}

@Composable
fun DependentLayout(
  modifier: Modifier = Modifier,
  mainLayoutId: String,
  content: @Composable () -> Unit
) {
  Layout(
    content = content,
    modifier = modifier
  ) { measureable, constraints ->
    val mainPlaceable = measureable.firstOrNull { it.layoutId == mainLayoutId }?.measure(constraints)
    val placeables: List<Placeable> = measureable.map {
      if (it.layoutId == mainLayoutId)
        mainPlaceable!!
      else
        it.measure(constraints.copy(minWidth = mainPlaceable?.width ?: 0, maxWidth = constraints.maxWidth))
    }
    val width = mainPlaceable?.measuredWidth ?: placeables.maxOf { it.width }
    val height = placeables.sumOf { it.height }
    val adjustedConstraints = Constraints.fitPrioritizingHeight(constraints.minWidth, width, constraints.minHeight, height)
    layout(
      if (width > adjustedConstraints.maxWidth) adjustedConstraints.maxWidth - horizontalPaddingAroundCustomLayouts(density) else adjustedConstraints.maxWidth,
      adjustedConstraints.maxHeight
    ) {
      var y = 0
      placeables.forEach {
        it.place(0, y)
        y += it.measuredHeight
      }
    }
  }
}

@Composable
fun AdaptingBottomPaddingLayout(
  modifier: Modifier = Modifier,
  mainLayoutId: String,
  expectedHeight: MutableState<Dp>,
  content: @Composable () -> Unit
) {
  val expected = with(LocalDensity.current) { expectedHeight.value.roundToPx() }
  Layout(
    content = content,
    modifier = modifier
  ) { measureable, constraints ->
    require(measureable.size <= 2) { "Should be exactly one or two elements in this layout, you have ${measureable.size}" }
    val mainPlaceable = measureable.firstOrNull { it.layoutId == mainLayoutId }!!.measure(constraints)
    val placeables: List<Placeable> = measureable.map {
      if (it.layoutId == mainLayoutId)
        mainPlaceable
      else
        it.measure(constraints.copy(maxHeight = if (expected != mainPlaceable.measuredHeight) constraints.maxHeight - mainPlaceable.measuredHeight + expected else constraints.maxHeight))
    }
    expectedHeight.value = mainPlaceable.measuredHeight.toDp()
    layout(constraints.maxWidth, constraints.maxHeight) {
      var y = 0
      placeables.forEach {
        if (it !== mainPlaceable) {
          it.place(0, y)
          y += it.measuredHeight
        } else {
          it.place(0, constraints.maxHeight - mainPlaceable.measuredHeight)
          y += it.measuredHeight
        }
      }
    }
  }
}

@Composable
fun CenteredRowLayout(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  Layout(
    content = content,
    modifier = modifier
  ) { measureable, constraints ->
    require(measureable.size == 3) { "Should be exactly three elements in this layout, you have ${measureable.size}" }
    val first = measureable[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
    val third = measureable[2].measure(constraints.copy(minWidth = first.measuredWidth, minHeight = 0))
    val second = measureable[1].measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - first.measuredWidth - third.measuredWidth).coerceAtLeast(0)))
    layout(constraints.maxWidth, constraints.maxHeight) {
      first.place(0, ((constraints.maxHeight - first.measuredHeight) / 2).coerceAtLeast(0))
      second.place((constraints.maxWidth - second.measuredWidth) / 2, ((constraints.maxHeight - second.measuredHeight) / 2).coerceAtLeast(0))
      third.place(constraints.maxWidth - third.measuredWidth, ((constraints.maxHeight - third.measuredHeight) / 2).coerceAtLeast(0))
    }
  }
}

fun showQuotedItemDoesNotExistAlert() {
  AlertManager.shared.showAlertMsg(
    title = generalGetString(MR.strings.message_deleted_or_not_received_error_title),
    text = generalGetString(MR.strings.message_deleted_or_not_received_error_desc)
  )
}
