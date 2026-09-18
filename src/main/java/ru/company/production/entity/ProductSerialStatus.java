package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус серийного номера в реестре.
 *
 * Номер заводится вместе с позицией заказа на производство и
 * проходит тот же путь, что и изделие: выпущен — в работе — собран.
 */
@Getter
@RequiredArgsConstructor
public enum ProductSerialStatus {

    NEW("Выпущен", "draft"),
    IN_WORK("В работе", "work"),
    DONE("Собран", "done");

    private final String displayName;

    /** Модификатор CSS-класса бейджа статуса. */
    private final String badgeClass;
}
