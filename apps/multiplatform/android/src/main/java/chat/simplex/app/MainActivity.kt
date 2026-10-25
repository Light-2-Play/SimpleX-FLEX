package chat.simplex.app

import chat.simplex.common.ui.theme.MonetPalette
import chat.simplex.common.ui.theme.getMonetPalette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import chat.simplex.app.model.NtfManager
import chat.simplex.app.model.NtfManager.getUserIdFromIntent
import chat.simplex.common.*
import chat.simplex.common.helpers.*
import chat.simplex.common.model.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.chatlist.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.onboarding.*
import chat.simplex.common.platform.*
import chat.simplex.res.MR
import java.lang.ref.WeakReference
import chat.simplex.app.SingBoxService
import chat.simplex.common.views.chat.item.LocalVoiceTranscriptionWidget
import chat.simplex.app.ai.VoiceTranscriptionBox
import androidx.compose.runtime.CompositionLocalProvider
import chat.simplex.common.platform.UsbPanicDetector
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

var showSingBoxDialogState = mutableStateOf(false)
var openByeDpiDialog: (() -> Unit)? = null

class MainActivity: FragmentActivity() {
  companion object {
    const val OLD_ANDROID_UI_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
  }

  private var usbWatcherJob: Job? = null

  private fun startForegroundUsbWatcher() {
    if (!UsbPanicDetector.isEnabled()) return
    usbWatcherJob?.cancel()
    usbWatcherJob = lifecycleScope.launch {
      while (isActive) {
        UsbPanicDetector.triggerIfConnected(applicationContext, chatModel)
        delay(350)
      }
    }
  }

  private fun stopForegroundUsbWatcher() {
    usbWatcherJob?.cancel()
    usbWatcherJob = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    if (UsbPanicDetector.checkStartupUsbBlock(this)) {
      return
    }

    mainActivity = WeakReference(this)
    super.onCreate(savedInstanceState)

    openByeDpiDialog = {
      showSingBoxDialogState.value = true
    }
    chat.simplex.common.views.chatlist.ByeDpiBridge.showDialog = {
      showSingBoxDialogState.value = true
    }
    chat.simplex.common.views.chatlist.ByeDpiBridge.isRunning.value = SingBoxService.isRunning

    MediaQualityManager.init(applicationContext)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      getMonetPalette = { isDark ->
        if (isDark) {
          val accentPrimary = Color(getColor(android.R.color.system_accent1_200))
          val accentSecondary = Color(getColor(android.R.color.system_accent2_200))
          val baseDark = Color(getColor(android.R.color.system_neutral1_900))
          val surfaceDark = Color(getColor(android.R.color.system_neutral1_800))

          MonetPalette(
            primary = accentPrimary,
            primaryVariant = Color(getColor(android.R.color.system_accent1_300)),
            background = lerp(baseDark, accentPrimary, 0.045f),
            surface = lerp(surfaceDark, accentPrimary, 0.12f),
            onPrimary = Color(getColor(android.R.color.system_accent1_900)),
            onBackground = Color(getColor(android.R.color.system_neutral1_50)),
            onSurface = Color(getColor(android.R.color.system_neutral1_50)),
            sentMessage = lerp(Color(getColor(android.R.color.system_accent1_800)), accentPrimary, 0.18f),
            sentQuote = Color(getColor(android.R.color.system_accent1_700)),
            receivedMessage = lerp(surfaceDark, accentSecondary, 0.14f),
            receivedQuote = lerp(surfaceDark, accentSecondary, 0.24f),
            primaryVariant2 = Color(getColor(android.R.color.system_accent2_700))
          )
        } else {
          val accentPrimary = Color(getColor(android.R.color.system_accent1_600))
          val accentSoft = Color(getColor(android.R.color.system_accent1_100))
          val accent2Soft = Color(getColor(android.R.color.system_accent2_100))

          MonetPalette(
            primary = accentPrimary,
            primaryVariant = Color(getColor(android.R.color.system_accent1_500)),
            background = lerp(Color.White, accentSoft, 0.35f),
            surface = lerp(Color.White, accentSoft, 0.65f),
            onPrimary = Color.White,
            onBackground = Color(getColor(android.R.color.system_neutral1_900)),
            onSurface = Color(getColor(android.R.color.system_neutral1_900)),
            sentMessage = lerp(Color.White, accentSoft, 0.85f),
            sentQuote = Color(getColor(android.R.color.system_accent1_200)),
            receivedMessage = lerp(Color.White, accent2Soft, 0.55f),
            receivedQuote = lerp(Color.White, accent2Soft, 0.85f),
            primaryVariant2 = accentSoft
          )
        }
      }
    }

    platform.androidSetNightModeIfSupported()
    val c = CurrentColors.value.colors
    platform.androidSetStatusAndNavigationBarAppearance(c.isLight, c.isLight)
    applyAppLocale(ChatModel.controller.appPrefs.appLanguage)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    if (savedInstanceState == null) {
      processNotificationIntent(intent)
      processIntent(intent)
      processExternalIntent(intent)
    }

    if (ChatController.appPrefs.privacyProtectScreen.get()) {
      window.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
      )
    }

    try {
      android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        try {
          SingBoxService.start(this@MainActivity)
        } catch (e: Throwable) {
          android.util.Log.e("SimpleXMod", "SingBox Startup Error", e)
        }
      }, 1000)
    } catch (e: Throwable) {
      android.util.Log.e("SimpleXMod", "Handler Call failure", e)
    }

    enableEdgeToEdge()

    setContent {
      CompositionLocalProvider(
        LocalVoiceTranscriptionWidget provides { cItem ->
          val fileMeta = cItem.file
          val fileSource = if (fileMeta != null) getLoadedFileSource(fileMeta) else null
          if (fileSource != null) {
            chat.simplex.app.ai.VoiceTranscriptionBox(
              fileSource = fileSource,
              isSent = cItem.chatDir.sent,
              modifier = androidx.compose.ui.Modifier.padding(
                start = if (cItem.chatDir.sent) 8.dp else 12.dp,
                end = if (cItem.chatDir.sent) 12.dp else 8.dp,
                top = 2.dp,
                bottom = 4.dp
              )
            )
          }
        }
      ) {
        SingBoxComposeDialogs(this@MainActivity)
        AppScreen()
      }
    }

    SimplexApp.context.schedulePeriodicServiceRestartWorker()
    SimplexApp.context.schedulePeriodicWakeUp()
    UsbPanicDetector.startMonitoring(applicationContext, chatModel)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    processIntent(intent)
    processExternalIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    UsbPanicDetector.triggerIfConnected(applicationContext, chatModel)
    startForegroundUsbWatcher()
    AppLock.recheckAuthState()
    chat.simplex.common.views.chatlist.ByeDpiBridge.isRunning.value = SingBoxService.isRunning
  }

  override fun onPause() {
    super.onPause()
    stopForegroundUsbWatcher()
    AppLock.appWasHidden()
  }

  override fun onStop() {
    super.onStop()
    VideoPlayerHolder.stopAll()
    AppLock.appWasHidden()
  }

  override fun onDestroy() {
    super.onDestroy()
    stopForegroundUsbWatcher()
    UsbPanicDetector.stopMonitoring(applicationContext)
    SingBoxService.stop()
  }

  override fun onBackPressed() {
    val canFinishActivity = (
      onBackPressedDispatcher.hasEnabledCallbacks()
        || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        || isTaskRoot 
    ) && SimplexApp.context.chatModel.sharedContent.value !is SharedContent.Forward
    if (canFinishActivity) {
      super.onBackPressed()
    }

    if (!onBackPressedDispatcher.hasEnabledCallbacks() && ChatController.appPrefs.performLA.get()) {
      AppLock.clearAuthState()
      AppLock.laFailed.value = true
    }
    if (!onBackPressedDispatcher.hasEnabledCallbacks()) {
      val sharedContent = chatModel.sharedContent.value
      chatModel.sharedContent.value = null
      if (sharedContent is SharedContent.Forward) {
        chatModel.chatId.value = sharedContent.fromChatInfo.id
      }
      if (canFinishActivity) {
        finish()
      }
    }
  }
}

// =====================================================================
// MONET COLORS FOR SINGBOX DIALOG (M3 EXPRESSIVE)
// =====================================================================
data class DirectMonetColors(
  val surface: Color,
  val background: Color,
  val onSurface: Color,
  val onSurfaceVariant: Color,
  val primary: Color,
  val onPrimary: Color,
  val primaryContainer: Color,
  val onPrimaryContainer: Color,
  val outline: Color
)

@Composable
fun rememberDirectMonetColors(context: android.content.Context = androidx.compose.ui.platform.LocalContext.current): DirectMonetColors {
  val isLight = CurrentColors.value.colors.isLight
  return remember(isLight) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (isLight) {
        val primary = Color(ContextCompat.getColor(context, android.R.color.system_accent1_600))
        val accentSoft = Color(ContextCompat.getColor(context, android.R.color.system_accent1_100))
        DirectMonetColors(
          surface = lerp(Color.White, accentSoft, 0.28f),
          background = lerp(Color.White, accentSoft, 0.55f),
          onSurface = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_900)),
          onSurfaceVariant = Color(ContextCompat.getColor(context, android.R.color.system_accent2_700)),
          primary = primary,
          onPrimary = Color.White,
          primaryContainer = accentSoft,
          onPrimaryContainer = Color(ContextCompat.getColor(context, android.R.color.system_accent1_900)),
          outline = primary.copy(alpha = 0.22f)
        )
      } else {
        val primary = Color(ContextCompat.getColor(context, android.R.color.system_accent1_200))
        val baseSurface = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_800))
        val baseBg = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_900))
        DirectMonetColors(
          surface = lerp(baseSurface, primary, 0.12f),
          background = lerp(baseBg, primary, 0.05f),
          onSurface = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_50)),
          onSurfaceVariant = Color(ContextCompat.getColor(context, android.R.color.system_accent2_200)),
          primary = primary,
          onPrimary = Color(ContextCompat.getColor(context, android.R.color.system_accent1_900)),
          primaryContainer = Color(ContextCompat.getColor(context, android.R.color.system_accent1_700)).copy(alpha = 0.55f),
          onPrimaryContainer = Color(ContextCompat.getColor(context, android.R.color.system_accent1_100)),
          outline = primary.copy(alpha = 0.25f)
        )
      }
    } else {
      if (isLight) {
        DirectMonetColors(
          surface = Color.White,
          background = Color(0xFFF6F2FA),
          onSurface = Color(0xFF1C1B1F),
          onSurfaceVariant = Color(0xFF49454F),
          primary = Color(0xFF6750A4),
          onPrimary = Color.White,
          primaryContainer = Color(0xFFEADDFF),
          onPrimaryContainer = Color(0xFF21005D),
          outline = Color(0xFF79747E).copy(alpha = 0.3f)
        )
      } else {
        DirectMonetColors(
          surface = Color(0xFF211F26),
          background = Color(0xFF141218),
          onSurface = Color(0xFFE6E1E5),
          onSurfaceVariant = Color(0xFFCAC4D0),
          primary = Color(0xFFD0BCFF),
          onPrimary = Color(0xFF381E72),
          primaryContainer = Color(0xFF4F378B),
          onPrimaryContainer = Color(0xFFEADDFF),
          outline = Color(0xFF938F99).copy(alpha = 0.3f)
        )
      }
    }
  }
}

// =====================================================================
// COMPOSE DIALOG COMPONENTS
// =====================================================================
@Composable
fun SingBoxComposeDialogs(activity: MainActivity) {
  if (!showSingBoxDialogState.value) return

  val monet = rememberDirectMonetColors(activity)
  var showCustomInputDialog by remember { mutableStateOf(false) }

  if (!showCustomInputDialog) {
    Dialog(onDismissRequest = { showSingBoxDialogState.value = false }) {
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = monet.surface,
        elevation = 6.dp,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, monet.outline, RoundedCornerShape(28.dp))
      ) {
        Column(modifier = Modifier.padding(24.dp)) {
          val modeLabel = if (SingBoxService.isCustomMode(activity)) "Custom VLESS" else "Auto Selection"
          val isRunning = SingBoxService.isRunning
          val statusText = if (isRunning) "● VLESS active ($modeLabel)" else "○ VLESS disabled"

          Text(
            text = "VLESS Proxy Settings",
            color = monet.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = statusText,
            color = if (isRunning) monet.primary else monet.onSurfaceVariant,
            fontSize = 14.sp
          )

          Spacer(modifier = Modifier.height(16.dp))

          val limits = listOf(25, 50, 100, 0, -1)
          val options = listOf(
            "25 Servers (Lite)",
            "50 Servers (Mid)",
            "100 Servers (High)",
            "All available (Ultra)",
            "Custom VLESS / Subscription"
          )

          var selectedIndex by remember {
            val isCustom = SingBoxService.isCustomMode(activity)
            val curLim = SingBoxService.getServerLimit(activity)
            mutableStateOf(if (isCustom) 4 else {
              val idx = limits.indexOf(curLim)
              if (idx == -1) 0 else idx
            })
          }

          options.forEachIndexed { index, text ->
            val isSelected = selectedIndex == index
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isSelected) monet.primaryContainer else Color.Transparent)
                .clickable { selectedIndex = index }
                .padding(vertical = 12.dp, horizontal = 12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(20.dp)
                  .border(
                    width = 2.dp,
                    color = if (isSelected) monet.primary else monet.outline,
                    shape = CircleShape
                  ),
                contentAlignment = Alignment.Center
              ) {
                if (isSelected) {
                  Box(modifier = Modifier.size(10.dp).background(monet.primary, CircleShape))
                }
              }
              Spacer(modifier = Modifier.width(14.dp))
              Text(
                text = text,
                color = if (isSelected) monet.onPrimaryContainer else monet.onSurface,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 15.sp
              )
            }
            if (index < options.lastIndex) {
              Spacer(modifier = Modifier.height(2.dp))
            }
          }

          Spacer(modifier = Modifier.height(24.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
          ) {
            TextButton(
              onClick = { 
                SingBoxService.stop()
                chat.simplex.common.views.chatlist.ByeDpiBridge.isRunning.value = false
                showSingBoxDialogState.value = false 
              },
              shape = CircleShape,
              colors = ButtonDefaults.textButtonColors(contentColor = monet.primary)
            ) {
              Text("Disable", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
              onClick = { showSingBoxDialogState.value = false },
              shape = CircleShape,
              colors = ButtonDefaults.textButtonColors(contentColor = monet.primary)
            ) {
              Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                if (selectedIndex == 4) {
                  showCustomInputDialog = true
                } else {
                  SingBoxService.setCustomMode(activity, false)
                  SingBoxService.setServerLimit(activity, limits[selectedIndex])
                  if (SingBoxService.isRunning) SingBoxService.restart(activity)
                  else SingBoxService.start(activity)
                  chat.simplex.common.views.chatlist.ByeDpiBridge.isRunning.value = true
                  showSingBoxDialogState.value = false
                }
              },
              shape = CircleShape,
              colors = ButtonDefaults.buttonColors(
                backgroundColor = monet.primary,
                contentColor = monet.onPrimary
              ),
              elevation = ButtonDefaults.elevation(0.dp, 0.dp)
            ) {
              Text(if (isRunning) "Apply" else "Turn On", fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  } else {
    Dialog(onDismissRequest = { showCustomInputDialog = false }) {
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = monet.surface,
        elevation = 6.dp,
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, monet.outline, RoundedCornerShape(28.dp))
      ) {
        Column(modifier = Modifier.padding(24.dp)) {
          Text(
            text = "Custom VLESS or link",
            color = monet.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(16.dp))

          var customKey by remember { mutableStateOf(SingBoxService.getCustomKey(activity)) }

          TextField(
            value = customKey,
            onValueChange = { customKey = it },
            placeholder = { Text("vless://... or http://...", color = monet.onSurfaceVariant.copy(alpha = 0.6f)) },
            colors = TextFieldDefaults.textFieldColors(
              textColor = monet.onSurface,
              placeholderColor = monet.onSurfaceVariant.copy(alpha = 0.6f),
              backgroundColor = monet.background,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              cursorColor = monet.primary
            ),
            modifier = Modifier
              .fillMaxWidth()
              .height(120.dp)
              .clip(RoundedCornerShape(16.dp))
              .border(1.dp, monet.outline, RoundedCornerShape(16.dp)),
            maxLines = 5
          )

          Spacer(modifier = Modifier.height(24.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
          ) {
            TextButton(
              onClick = { showCustomInputDialog = false },
              shape = CircleShape,
              colors = ButtonDefaults.textButtonColors(contentColor = monet.primary)
            ) {
              Text("Back", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                val key = customKey.trim()
                if (key.isNotEmpty()) {
                  SingBoxService.setCustomMode(activity, true)
                  SingBoxService.setCustomKey(activity, key)
                  if (SingBoxService.isRunning) SingBoxService.restart(activity)
                  else SingBoxService.start(activity)
                  chat.simplex.common.views.chatlist.ByeDpiBridge.isRunning.value = true
                  showCustomInputDialog = false
                  showSingBoxDialogState.value = false
                }
              },
              shape = CircleShape,
              colors = ButtonDefaults.buttonColors(
                backgroundColor = monet.primary,
                contentColor = monet.onPrimary
              ),
              elevation = ButtonDefaults.elevation(0.dp, 0.dp)
            ) {
              Text("Connect", fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  }
}

// =====================================================================
// APP INTENTS
// =====================================================================
fun processNotificationIntent(intent: Intent?) {
  val userId = getUserIdFromIntent(intent)
  when (intent?.action) {
    NtfManager.OpenChatAction -> {
      val chatId = intent.getStringExtra("chatId")
      Log.d("MainActivity", "processNotificationIntent: OpenChatAction $chatId")
      if (chatId != null) {
        ntfManager.openChatAction(userId, chatId)
      }
    }
    NtfManager.ShowChatsAction -> {
      Log.d("MainActivity", "processNotificationIntent: ShowChatsAction")
      ntfManager.showChatsAction(userId)
    }
    NtfManager.AcceptCallAction -> {
      val chatId = intent.getStringExtra("chatId")
      if (chatId == null || chatId == "") return
      Log.d("MainActivity", "processNotificationIntent: AcceptCallAction $chatId")
      ntfManager.acceptCallAction(chatId)
    }
  }
}

fun processIntent(intent: Intent?) {
  when (intent?.action) {
    "android.intent.action.VIEW" -> {
      val uri = intent.data
      if (uri != null) {
        chatModel.appOpenUrl.value = null to uri.toString()
      } else {
        AlertManager.shared.showAlertMsg(generalGetString(MR.strings.error_parsing_uri_title), generalGetString(MR.strings.error_parsing_uri_desc))
      }
    }
  }
}

fun processExternalIntent(intent: Intent?) {
  when (intent?.action) {
    Intent.ACTION_SEND -> {
      chatModel.chatId.value = null
      chatModel.clearOverlays.value = true
      when {
        intent.type == "text/plain" -> {
          val text = intent.getStringExtra(Intent.EXTRA_TEXT)
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.File(text ?: "", uri.toURI())
          } else if (text != null) {
            chatModel.sharedContent.value = SharedContent.Text(text)
          }
        }
        isMediaIntent(intent) -> {
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.Media(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", listOf(uri.toURI()))
          }
        }
        else -> {
          val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
          if (uri != null) {
            if (uri.scheme != "content") return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.File(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", uri.toURI())
          }
        }
      }
    }
    Intent.ACTION_SEND_MULTIPLE -> {
      chatModel.chatId.value = null
      chatModel.clearOverlays.value = true
      when {
        isMediaIntent(intent) -> {
          val uris = intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM) as? List<Uri>
          if (uris != null) {
            if (uris.any { it.scheme != "content" }) return showWrongUriAlert()
            chatModel.sharedContent.value = SharedContent.Media(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", uris.map { it.toURI() })
          }
        }
        else -> {}
      }
    }
  }
}

fun isMediaIntent(intent: Intent): Boolean =
  intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true
