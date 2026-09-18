package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус позиции заказа на производство.
 *
 * Позиция — один выпускаемый экземпляр изделия, поэтому статусов
 * меньше, чем у заказа покупателя: «частично выполнено» позиции не
 * бывает, она либо в работе, либо собрана.
 */
@Getter
@RequiredArgsConstructor
public enum ProductionItemStatus {

    NEW("Новая", "draft"),
    IN_WORK("В работе", "work"),
    DONE("Выполнена", "done");

    private final String displayName;

    /** Модификатор CSS-класса бейджа статуса. */
    private final String badgeClass;
}
