package chat.simplex.common.platform

import chat.simplex.common.model.ChatModel

expect object EmergencyKill {
  /**
   * Экстренная зачистка и жесткое завершение процесса:
   * @param chatModel ссылка на модель для остановки ядра и контроллеров
   * @param wipeTempMedia очистить временный кэш расшифрованных картинок/голоса
   * @param clearClipboard стереть данные из буфера обмена
   */
  fun purgeAndKill(
    chatModel: ChatModel,
    wipeTempMedia: Boolean = true,
    clearClipboard: Boolean = true
  )
}
