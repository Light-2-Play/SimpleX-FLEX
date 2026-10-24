package chat.simplex.common.platform

import chat.simplex.common.model.ChatModel
import kotlin.system.exitProcess

actual object EmergencyKill {
  actual fun purgeAndKill(
    chatModel: ChatModel,
    wipeTempMedia: Boolean,
    clearClipboard: Boolean
  ) {
    try {
      chatModel.controller.stopChat()
      ChatModel.filesToDelete.forEach { runCatching { it.delete() } }
      ChatModel.filesToDelete.clear()
    } catch (_: Exception) {}
    exitProcess(0)
  }
}
