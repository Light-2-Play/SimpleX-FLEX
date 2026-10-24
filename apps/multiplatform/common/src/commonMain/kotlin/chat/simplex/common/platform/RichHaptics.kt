package chat.simplex.common.platform

enum class HapticPattern {
  CLICK,             // Обычный мягкий клик по кнопке/элементу
  HEAVY_CLICK,       // Плотный, весомый удар (долгое нажатие, важный выбор)
  DOUBLE_CLICK,      // Двойной упругий щелчок
  TICK,              // Микро-тик (скролл колеса, смена фокуса)
  SELECTION_CHANGE,  // Ультра-легкий отклик при листании списков/реакций
  TOGGLE_ON,         // Восходящий щелчок (включение тумблера)
  TOGGLE_OFF,        // Ниспадающий мягкий щелчок (выключение тумблера)
  SEND_MESSAGE,      // Тактильный "взлёт" при отправке сообщения
  RECORD_START,      // Плотный импульс старта записи голоса
  RECORD_STOP,       // Короткий сброс при остановке записи
  SUCCESS,           // Трёхступенчатый гармоничный аккорд подтверждения
  ERROR,             // Прерывистая стаккато-дробь ошибки
  WARNING,           // Тяжёлый предупреждающий импульс
  SWIPE_ACTION       // Фиксация порога свайпа
}

expect object RichHaptics {
  fun isEnabled(): Boolean
  fun setEnabled(enabled: Boolean)
  fun perform(pattern: HapticPattern)
}
