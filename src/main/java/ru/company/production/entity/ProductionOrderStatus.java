package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус заказа на производство.
 *
 * Набор короче, чем у заказа покупателя: производственный заказ
 * не может быть «просрочен» или «приостановлен» как отдельное
 * состояние — для диспетчера достаточно видеть, запущен ли выпуск
 * и на сколько он закрыт.
 */
@Getter
@RequiredArgsConstructor
public enum ProductionOrderStatus {

    NEW("Новый", "draft"),
    IN_WORK("В работе", "work"),
    PARTIALLY_DONE("Частично выполнен", "work"),
    DONE("Выполнен", "done"),
    CANCELLED("Отменён", "deleted");

    private final String displayName;

    /** Модификатор CSS-класса бейджа статуса. */
    private final String badgeClass;
}
