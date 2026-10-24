package chat.simplex.common.platform

import chat.simplex.common.model.ChatModel
import kotlin.system.exitProcess

actual object EmergencyKill {
  actual fun purgeAndKill(
    chatModel: ChatModel?,
    wipeTempMedia: Boolean,
    clearClipboard: Boolean
  ) {
    exitProcess(0)
  }
}
