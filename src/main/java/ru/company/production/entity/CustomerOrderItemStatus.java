package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус отдельного изделия в заказе покупателя.
 */
@Getter
@RequiredArgsConstructor
public enum CustomerOrderItemStatus {

    NEW("Новое"),
    IN_WORK("В работе"),
    PARTIALLY_DONE("Частично готово"),
    DONE("Готово"),
    CANCELLED("Отменено");

    private final String displayName;
}
