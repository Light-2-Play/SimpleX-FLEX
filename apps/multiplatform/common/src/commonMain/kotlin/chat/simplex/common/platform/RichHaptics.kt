package chat.simplex.common.platform

enum class HapticPattern {
  TICK,             // Лёгкий щелчок: скролл, шаги ползунка
  CLICK,            // Клик по кнопкам, карточкам, табам
  HEAVY_CLICK,      // Долгое нажатие (контекстные меню)
  DOUBLE_CLICK,     // Двойной клик
  TOGGLE_ON,        // Включение свитча
  TOGGLE_OFF,       // Выключение свитча
  SUCCESS,          // Успех операции
  WARNING,          // Предупреждение / удаление
  ERROR,            // Ошибка валидации / сбой соединения
  SEND_MESSAGE,     // Выстреливающий импульс отправки сообщения
  RECORD_START,     // Тактильная фиксация старта записи войса
  RECORD_STOP,      // Чёткий щелчок окончания/отправки войса
  SWIPE_ACTION,     // Отдача при свайпе сообщения/шторки
  SELECTION_CHANGE  // Микро-отклик при выборе фото/файла/тега
}

expect object RichHaptics {
  var isAdvancedHapticsEnabled: () -> Boolean

  fun perform(pattern: HapticPattern)
}
