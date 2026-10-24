package chat.simplex.common.views.usersettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.painterResource
import chat.simplex.common.platform.HapticPattern
import chat.simplex.common.platform.RichHaptics
import chat.simplex.common.platform.UsbPanicDetector
import chat.simplex.res.MR

// =====================================================================
// 1. КЛИКАБЕЛЬНАЯ ПЛАШКА ДЛЯ ВХОДА (ДЛЯ SettingsView.kt)
// =====================================================================
@Composable
fun FlexSettingsEntryTile(onClick: () -> Unit) {
  Card(
    shape = RoundedCornerShape(16.dp),
    backgroundColor = MaterialTheme.colors.surface,
    elevation = 0.dp,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp)
      .clickable {
        RichHaptics.perform(HapticPattern.CLICK)
        onClick()
      }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          painter = painterResource(MR.images.ic_security),
          contentDescription = null,
          tint = MaterialTheme.colors.primary,
          modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
          Text(
            text = "FLEX Settings",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface
          )
          Text(
            text = "Custom security & modifications",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
          )
        }
      }
      Icon(
        painter = painterResource(MR.images.ic_arrow_forward),
        contentDescription = null,
        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
        modifier = Modifier.size(18.dp)
      )
    }
  }
}

// =====================================================================
// 2. ЭКРАН НАСТРОЕК FLEX (СЮДА ДОБАВЛЯЕМ НОВЫЕ ПУНКТЫ)
// =====================================================================
@Composable
fun FlexSettingsView(onBack: () -> Unit) {
  // --- Состояния существующих настроек ---
  var usbPanicEnabled by remember { mutableStateOf(UsbPanicDetector.isEnabled()) }
  var advancedHapticsEnabled by remember { mutableStateOf(RichHaptics.isEnabled()) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "FLEX Settings",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              painter = painterResource(MR.images.ic_arrow_back),
              contentDescription = "Back",
              tint = MaterialTheme.colors.onSurface
            )
          }
        },
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 0.dp
      )
    },
    backgroundColor = MaterialTheme.colors.background
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
    ) {

      // -------------------------------------------------------------
      // СЕКЦИЯ 1: БЕЗОПАСНОСТЬ (SECURITY)
      // -------------------------------------------------------------
      FlexCategoryHeader("SECURITY")

      FlexSettingCard {
        // USB Panic
        FlexToggleItem(
          title = "USB Panic",
          subtitle = "Purge cache, clipboard and kill app on USB plug",
          icon = painterResource(MR.images.ic_security),
          iconTint = if (usbPanicEnabled) Color.Red else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
          checked = usbPanicEnabled,
          activeTrackColor = Color.Red,
          onCheckedChange = { enabled ->
            usbPanicEnabled = enabled
            UsbPanicDetector.setEnabled(enabled)
            if (advancedHapticsEnabled) {
              RichHaptics.perform(if (enabled) HapticPattern.WARNING else HapticPattern.TOGGLE_OFF)
            }
          }
        )

        // <<--- В БУДУЩЕМ НОВУЮ НАСТРОЙКУ БЕЗОПАСНОСТИ МОЖНО ВСТАВИТЬ СЮДА ---
      }

      Spacer(modifier = Modifier.height(20.dp))

      // -------------------------------------------------------------
      // СЕКЦИЯ 2: ИНТЕРФЕЙС И ОТКЛИК (INTERFACE & FEEDBACK)
      // -------------------------------------------------------------
      FlexCategoryHeader("INTERFACE & FEEDBACK")

      FlexSettingCard {
        // Advanced Haptics
        FlexToggleItem(
          title = "Advanced Haptics",
          subtitle = "Tactile vibration and feedback across UI",
          icon = painterResource(MR.images.ic_notifications),
          iconTint = if (advancedHapticsEnabled) Color(0xFFFFA000) else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
          checked = advancedHapticsEnabled,
          onCheckedChange = { enabled ->
            advancedHapticsEnabled = enabled
            RichHaptics.setEnabled(enabled)
            if (enabled) {
              RichHaptics.perform(HapticPattern.HEAVY_CLICK)
            }
          }
        )

        // <<--- В БУДУЩЕМ НОВУЮ НАСТРОЙКУ ИНТЕРФЕЙСА МОЖНО ВСТАВИТЬ СЮДА ---
      }

      // <<--- СЮДА МОЖНО ДОБАВИТЬ ЕЩЁ ОДНУ СЕКЦИЮ, НАПРИМЕР "NETWORK & PROXY" ---
    }
  }
}

// =====================================================================
// 3. ПЕРЕИСПОЛЬЗУЕМЫЕ КОМПОНЕНТЫ ДЛЯ БЫСТРОГО ДОБАВЛЕНИЯ НАСТРОЕК
// =====================================================================

@Composable
fun FlexCategoryHeader(title: String) {
  Text(
    text = title,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colors.primary,
    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
  )
}

@Composable
fun FlexSettingCard(content: @Composable ColumnScope.() -> Unit) {
  Card(
    shape = RoundedCornerShape(16.dp),
    backgroundColor = MaterialTheme.colors.surface,
    elevation = 0.dp,
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(vertical = 4.dp),
      content = content
    )
  }
}

/**
 * Универсальный элемент с переключателем (Switch)
 */
@Composable
fun FlexToggleItem(
  title: String,
  subtitle: String? = null,
  icon: Painter? = null,
  iconTint: Color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
  checked: Boolean,
  activeTrackColor: Color = MaterialTheme.colors.primary,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      if (icon != null) {
        Icon(
          painter = icon,
          contentDescription = null,
          tint = iconTint,
          modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
      }
      Column {
        Text(
          text = title,
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colors.onSurface
        )
        if (subtitle != null) {
          Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
          )
        }
      }
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = activeTrackColor,
        checkedTrackColor = activeTrackColor.copy(alpha = 0.5f)
      )
    )
  }
}

/**
 * Универсальный кликабельный элемент (для диалогов или перехода дальше)
 */
@Composable
fun FlexActionItem(
  title: String,
  subtitle: String? = null,
  icon: Painter? = null,
  iconTint: Color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      if (icon != null) {
        Icon(
          painter = icon,
          contentDescription = null,
          tint = iconTint,
          modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
      }
      Column {
        Text(
          text = title,
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colors.onSurface
        )
        if (subtitle != null) {
          Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
          )
        }
      }
    }
    Icon(
      painter = painterResource(MR.images.ic_arrow_forward),
      contentDescription = null,
      tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
      modifier = Modifier.size(18.dp)
    )
  }
}
