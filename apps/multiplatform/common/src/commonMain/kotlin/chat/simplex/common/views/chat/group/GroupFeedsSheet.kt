package chat.simplex.common.views.chat.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.model.*
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import chat.simplex.res.MR
import dev.icerock.moko.resources.compose.painterResource

@Composable
fun GroupFeedsSheet(
  chatModel: ChatModel,
  groupInfo: GroupInfo,
  close: () -> Unit
) {
  var feeds by remember { mutableStateOf<List<FeedSubscription>>(emptyList()) }
  var showAddDialog by remember { mutableStateOf(false) }

  val isLight = MaterialTheme.colors.isLight
  val sheetBg = lerp(MaterialTheme.colors.surface, MaterialTheme.colors.primary, if (isLight) 0.04f else 0.07f)
  val cardBg = lerp(MaterialTheme.colors.surface, MaterialTheme.colors.primary, if (isLight) 0.08f else 0.12f)

  // Полноэкранная подложка с привязкой содержимого к низу
  Box(
    modifier = Modifier
      .fillMaxSize()
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = close // закрытие по тапу вне шторки
      ),
    contentAlignment = Alignment.BottomCenter
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
        .background(sheetBg)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = {} // предотвращаем клик сквозь саму шторку
        )
        .navigationBarsPadding() // отступ от нижней полосы жестов/кнопок
        .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
      // Drag Handle (полоска вверху шторки)
      Box(
        modifier = Modifier
          .align(Alignment.CenterHorizontally)
          .size(width = 36.dp, height = 4.dp)
          .clip(RoundedCornerShape(percent = 50))
          .background(MaterialTheme.colors.onSurface.copy(alpha = 0.2f))
      )

      Spacer(Modifier.height(14.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            "Zero-Relay Feeds",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface
          )
          Text(
            "RSS / Mastodon bridge directly to this chat",
            fontSize = 12.5.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
          )
        }

        Surface(
          shape = RoundedCornerShape(percent = 50),
          color = MaterialTheme.colors.primary,
          modifier = Modifier.size(40.dp)
        ) {
          IconButton(onClick = {
            RichHaptics.perform(HapticPattern.CLICK)
            showAddDialog = true
          }) {
            Icon(
              painterResource(MR.images.ic_add),
              contentDescription = "Add feed",
              tint = MaterialTheme.colors.onPrimary,
              modifier = Modifier.size(20.dp)
            )
          }
        }
      }

      Spacer(Modifier.height(16.dp))

      if (feeds.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            "No active feeds yet. Tap + to connect an RSS or Fediverse account.",
            color = MaterialTheme.colors.secondary,
            fontSize = 14.sp
          )
        }
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          items(feeds) { sub ->
            FeedItemCard(
              sub = sub,
              cardBg = cardBg,
              onToggle = { enabled ->
                feeds = feeds.map { if (it.id == sub.id) it.copy(enabled = enabled) else it }
              },
              onSyncNow = {
                RichHaptics.perform(HapticPattern.TICK)
                ZeroRelayFeedEngine.syncFeed(chatModel, sub) { updated ->
                  feeds = feeds.map { if (it.id == updated.id) updated else it }
                }
              }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FeedItemCard(
  sub: FeedSubscription,
  cardBg: Color,
  onToggle: (Boolean) -> Unit,
  onSyncNow: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = cardBg,
    elevation = 0.dp,
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        painterResource(if (sub.type == FeedType.RSS_ATOM) MR.images.ic_wifi_tethering else MR.images.ic_tag),
        contentDescription = null,
        tint = MaterialTheme.colors.primary,
        modifier = Modifier.size(24.dp)
      )

      Spacer(Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = sub.title,
          fontWeight = FontWeight.SemiBold,
          fontSize = 15.sp,
          color = MaterialTheme.colors.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = sub.url,
          fontSize = 12.sp,
          color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      IconButton(onClick = onSyncNow) {
        Icon(
          painterResource(MR.images.ic_refresh),
          contentDescription = "Sync",
          tint = MaterialTheme.colors.primary,
          modifier = Modifier.size(20.dp)
        )
      }

      Switch(
        checked = sub.enabled,
        onCheckedChange = {
          RichHaptics.perform(if (it) HapticPattern.TOGGLE_ON else HapticPattern.TOGGLE_OFF)
          onToggle(it)
        },
        colors = SwitchDefaults.colors(
          checkedThumbColor = MaterialTheme.colors.primary
        )
      )
    }
  }
}
