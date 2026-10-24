package chat.simplex.common.platform

import chat.simplex.common.model.ChatModel

expect object EmergencyKill {
  fun purgeAndKill(
    chatModel: ChatModel? = null,
    wipeTempMedia: Boolean = true,
    clearClipboard: Boolean = true
  )
}
