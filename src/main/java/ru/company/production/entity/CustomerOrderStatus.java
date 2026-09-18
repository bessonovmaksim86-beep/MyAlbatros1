package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус заказа покупателя.
 *
 * Набор отражает реальный цикл заказа: от поступления до закрытия,
 * включая состояния, которые диспетчер обязан видеть отдельно —
 * просрочку, приостановку и пометку об удалении.
 */
@Getter
@RequiredArgsConstructor
public enum CustomerOrderStatus {

    NEW("Новый", "draft"),
    IN_WORK("В работе", "work"),
    PARTIALLY_DONE("Частично исполнен", "work"),
    DONE("Завершён", "done"),
    OVERDUED("Просрочен", "overdued"),
    SUSPENDED("Приостановлен", "suspended"),
    DELETED("Удалён", "deleted"),
    CANCELLED("Отменён", "deleted");

    private final String displayName;

    /** Модификатор CSS-класса бейджа статуса. */
    private final String badgeClass;
}