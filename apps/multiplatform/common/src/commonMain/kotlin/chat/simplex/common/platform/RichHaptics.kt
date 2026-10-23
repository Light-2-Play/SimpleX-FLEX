package chat.simplex.common.platform

enum class HapticPattern {
  TICK,         // Лёгкий щелчок: скролл элементов, шаги ползунка
  CLICK,        // Стандартный клик: переключение табов, нажатие на кнопки-островки
  HEAVY_CLICK,  // Увесистый клик: долгое нажатие (открытие контекстного меню)
  DOUBLE_CLICK, // Двойной клик
  TOGGLE_ON,    // Включение свитча (нарастающий импульс)
  TOGGLE_OFF,   // Выключение свитча (затухающий импульс)
  SUCCESS,      // Успех: сообщение отправлено, подключение установлено
  WARNING,      // Предупреждение
  ERROR         // Ошибка: неверный ввод, сбой соединения
}

expect object RichHaptics {
  fun perform(pattern: HapticPattern)
}
